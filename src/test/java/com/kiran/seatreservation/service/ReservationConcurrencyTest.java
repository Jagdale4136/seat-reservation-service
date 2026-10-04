package com.kiran.seatreservation.service;

import com.kiran.seatreservation.PostgresTestBase;
import com.kiran.seatreservation.dto.request.ReserveSeatRequest;
import com.kiran.seatreservation.entity.Reservation;
import com.kiran.seatreservation.entity.Seat;
import com.kiran.seatreservation.entity.Show;
import com.kiran.seatreservation.entity.enums.ReservationStatus;
import com.kiran.seatreservation.entity.enums.SeatStatus;
import com.kiran.seatreservation.exception.ReservationLimitExceededException;
import com.kiran.seatreservation.exception.SeatAlreadyReservedException;
import com.kiran.seatreservation.repository.ReservationRepository;
import com.kiran.seatreservation.repository.ReservationSeatRepository;
import com.kiran.seatreservation.repository.SeatRepository;
import com.kiran.seatreservation.repository.ShowRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReservationConcurrencyTest extends PostgresTestBase {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSeatRepository reservationSeatRepository;


    // =========================================================
    // HOT SEAT CONCURRENCY TEST
    // =========================================================

    @Test
    void concurrentRequestsForSameSeat_shouldAllowOnlyOneWinner()
            throws Exception {

        int numberOfUsers = 50;

        Show show = createShow("concurrency-test-show", 1, 4);
        UUID showId = show.getId();

        List<Result> results = runConcurrently(
                numberOfUsers,
                i -> () -> attemptReserve(
                        showId,
                        "concurrent-user-" + i,
                        UUID.randomUUID().toString(),
                        "A1"
                )
        );

        long successfulRequests =
                results.stream().filter(Result::isSuccess).count();

        long failedRequests =
                results.stream().filter(Result::isFailure).count();

        System.out.println("Successful requests = " + successfulRequests);
        System.out.println("Failed requests = " + failedRequests);

        printFailures(results);

        // Exactly one request must win.
        assertEquals(
                1,
                successfulRequests,
                "Exactly one user must reserve the hot seat"
        );

        assertEquals(numberOfUsers - 1, failedRequests);

        // Every loser must fail with the expected domain exception.
        results.stream()
                .filter(Result::isFailure)
                .forEach(result -> assertInstanceOf(
                        SeatAlreadyReservedException.class,
                        result.error(),
                        "Unexpected exception: " + result.error()
                ));

        // Database state: the seat is held by the winner.
        List<Seat> seats =
                seatRepository.findByShowIdAndSeatNumberInOrderBySeatNumber(
                        showId,
                        List.of("A1")
                );

        assertEquals(1, seats.size());

        Seat seat = seats.get(0);

        assertEquals(SeatStatus.HELD, seat.getStatus());
        assertNotNull(seat.getCurrentReservationId());

        // Losers rolled back, so exactly one reservation exists in total.
        List<Reservation> reservations =
                reservationRepository.findByShowId(showId);

        assertEquals(
                1,
                reservations.size(),
                "Only one reservation may exist for the hot seat"
        );

        Reservation reservation = reservations.get(0);

        assertEquals(ReservationStatus.HELD, reservation.getStatus());

        // The seat must point at the winning reservation.
        assertEquals(
                reservation.getId(),
                seat.getCurrentReservationId()
        );

        // Exactly one reservation-seat mapping.
        assertEquals(
                1,
                reservationSeatRepository
                        .findByIdReservationId(reservation.getId())
                        .size()
        );
    }


    // =========================================================
    // CONCURRENT IDEMPOTENCY TEST
    // =========================================================

    @Test
    void concurrentRequestsWithSameIdempotencyKey_shouldCreateOnlyOneReservation()
            throws Exception {

        int numberOfRequests = 30;

        Show show = createShow("idempotency-concurrency-show", 2, 4);
        UUID showId = show.getId();

        String userId = "same-user";
        String idempotencyKey = "same-idempotency-key";

        List<Result> results = runConcurrently(
                numberOfRequests,
                i -> () -> attemptReserve(
                        showId,
                        userId,
                        idempotencyKey,
                        "A1"
                )
        );

        printFailures(results);

        long successfulRequests =
                results.stream().filter(Result::isSuccess).count();

        // Every retry with the same key must return the original reservation.
        assertEquals(
                numberOfRequests,
                successfulRequests,
                "Same idempotency key should return the same reservation"
        );

        List<UUID> reservationIds = results.stream()
                .filter(Result::isSuccess)
                .map(Result::reservationId)
                .toList();

        assertTrue(
                reservationIds.stream().allMatch(Objects::nonNull),
                "Every successful response must carry a reservation ID"
        );

        assertEquals(
                1,
                reservationIds.stream().distinct().count(),
                "All retries must return the same reservation ID"
        );

        // Database must contain exactly one reservation, holding one seat.
        List<Reservation> reservations =
                reservationRepository.findByShowId(showId);

        assertEquals(1, reservations.size());

        assertEquals(
                reservations.get(0).getId(),
                reservationIds.get(0)
        );

        assertEquals(
                1,
                reservationSeatRepository
                        .findByIdReservationId(reservations.get(0).getId())
                        .size()
        );
    }


    // =========================================================
    // PER USER LIMIT CONCURRENCY
    // =========================================================

    @Test
    void concurrentRequestsForSameUser_shouldRespectUserLimit()
            throws Exception {

        int numberOfRequests = 10;
        int userLimit = 4;

        Show show = createShow(
                "user-limit-concurrency-show",
                numberOfRequests,
                userLimit
        );

        UUID showId = show.getId();
        String userId = "limited-user";

        List<Result> results = runConcurrently(
                numberOfRequests,
                i -> () -> attemptReserve(
                        showId,
                        userId,
                        "limit-key-" + i,
                        "A" + (i + 1)
                )
        );

        long successfulRequests =
                results.stream().filter(Result::isSuccess).count();

        long limitFailures = results.stream()
                .filter(Result::isFailure)
                .filter(r -> r.error()
                        instanceof ReservationLimitExceededException)
                .count();

        System.out.println("Successful requests = " + successfulRequests);
        System.out.println("Limit failures = " + limitFailures);

        printFailures(results);

        // User limit is 4.
        assertEquals(
                userLimit,
                successfulRequests,
                "User must never exceed the reservation limit"
        );

        // Remaining requests must be clean domain failures.
        assertEquals(
                numberOfRequests - userLimit,
                limitFailures,
                "All other requests must fail with ReservationLimitExceededException"
        );

        // Database state: exactly 'userLimit' HELD reservations for the user.
        List<Reservation> userReservations =
                reservationRepository.findByShowId(showId)
                        .stream()
                        .filter(r -> r.getUserId().equals(userId))
                        .toList();

        assertEquals(userLimit, userReservations.size());

        assertTrue(
                userReservations.stream()
                        .allMatch(r -> r.getStatus() == ReservationStatus.HELD)
        );

        // Exactly 'userLimit' seats are held; the rest stay available.
        long heldSeats = seatRepository.findByShowId(showId)
                .stream()
                .filter(s -> s.getStatus() == SeatStatus.HELD)
                .count();

        assertEquals(userLimit, heldSeats);
    }


    // =========================================================
    // HELPERS
    // =========================================================

    private Result attemptReserve(
            UUID showId,
            String userId,
            String idempotencyKey,
            String seatNumber
    ) {

        try {

            var response = reservationService.reserve(
                    showId,
                    userId,
                    idempotencyKey,
                    new ReserveSeatRequest(List.of(seatNumber))
            );

            return Result.success(
                    response.status(),
                    response.reservationId()
            );

        } catch (Exception e) {

            return Result.failure(e);
        }
    }

    private void printFailures(List<Result> results) {

        results.stream()
                .filter(Result::isFailure)
                .forEach(result -> System.out.println(
                        "Failure: "
                                + result.error().getClass().getSimpleName()
                                + " - "
                                + result.error().getMessage()
                ));
    }

    /**
     * Runs {@code count} tasks at the same instant.
     *
     * The pool size equals the task count on purpose: every task blocks on
     * the start latch, so a smaller pool would leave tasks queued, the ready
     * latch would never reach zero, and the test would time out.
     */
    private List<Result> runConcurrently(
            int count,
            IntFunction<Callable<Result>> taskFactory
    ) throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(count);

        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);

        try {

            List<Future<Result>> futures = new ArrayList<>();

            for (int i = 0; i < count; i++) {

                Callable<Result> task = taskFactory.apply(i);

                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Not all workers became ready"
            );

            start.countDown();

            List<Result> results = new ArrayList<>();

            for (Future<Result> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }

            return results;

        } finally {

            executor.shutdownNow();
        }
    }

    private Show createShow(
            String name,
            int seatCount,
            int perUserLimit
    ) {

        Show show = showRepository.save(
                Show.builder()
                        .name(name)
                        .pricePaise(25000L)
                        .perUserLimit(perUserLimit)
                        .build()
        );

        List<Seat> seats = new ArrayList<>();

        for (int i = 1; i <= seatCount; i++) {
            seats.add(
                    Seat.builder()
                            .show(show)
                            .seatNumber("A" + i)
                            .status(SeatStatus.AVAILABLE)
                            .build()
            );
        }

        seatRepository.saveAll(seats);

        return show;
    }


    // =========================================================
    // RESULT HOLDER
    // =========================================================

    private record Result(
            boolean success,
            ReservationStatus status,
            UUID reservationId,
            Exception error
    ) {

        static Result success(ReservationStatus status, UUID reservationId) {
            return new Result(true, status, reservationId, null);
        }

        static Result failure(Exception error) {
            return new Result(false, null, null, error);
        }

        boolean isSuccess() {
            return success;
        }

        boolean isFailure() {
            return !success;
        }
    }
}