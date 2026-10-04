package com.kiran.seatreservation.service;

import com.kiran.seatreservation.dto.request.ReserveSeatRequest;
import com.kiran.seatreservation.dto.response.CancellationResponse;
import com.kiran.seatreservation.dto.response.ReservationResponse;
import com.kiran.seatreservation.entity.*;
import com.kiran.seatreservation.entity.enums.ReservationStatus;
import com.kiran.seatreservation.entity.enums.SeatStatus;
import com.kiran.seatreservation.exception.*;
import com.kiran.seatreservation.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationService {

    private static final long HOLD_DURATION_MINUTES = 5;

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final UserShowLimitRepository userShowLimitRepository;


    // =========================================================
    // RESERVE
    //
    // Lock order (everywhere): reservation -> user limit -> seats
    // (reserve has no reservation yet: idempotency -> user limit -> seats)
    // =========================================================

    @Transactional
    public ReservationResponse reserve(
            UUID showId,
            String userId,
            String idempotencyKey,
            ReserveSeatRequest request
    ) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidRequestException(
                    "Idempotency-Key header is required"
            );
        }

        List<String> seatNumbers = normalizeSeats(request.seats());

        Show show = showRepository.findById(showId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Show not found: " + showId
                        )
                );

        String requestHash = generateRequestHash(seatNumbers);

        /*
         * Create idempotency record if it does not already exist.
         */
        idempotencyKeyRepository.insertIfAbsent(
                UUID.randomUUID(),
                showId,
                userId,
                idempotencyKey,
                requestHash
        );

        /*
         * Lock idempotency record.
         */
        IdempotencyKey idempotency =
                idempotencyKeyRepository.findForUpdate(
                        showId,
                        userId,
                        idempotencyKey
                ).orElseThrow(() ->
                        new IllegalStateException(
                                "Unable to create idempotency record"
                        )
                );

        /*
         * Same idempotency key was already processed.
         */
        if (idempotency.getReservation() != null) {

            if (!idempotency.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(
                        "Idempotency key was already used with a different request"
                );
            }

            log.info(
                    "Returning existing reservation for idempotency key: showId={}, userId={}",
                    showId,
                    userId
            );

            return toResponse(idempotency.getReservation());
        }

        /*
         * FAST-FAIL (no locks).
         *
         * Under a hot-seat storm almost every request loses. Rejecting those
         * here, before the user-limit row and the seat rows are locked, keeps
         * them out of the seat-lock queue.
         *
         * This is only an optimisation: the locked checks below remain the
         * authoritative decision. It is a projection query on purpose, so no
         * Seat entities (and no stale copies) enter the persistence context.
         */
        List<SeatStatus> seatStatuses =
                seatRepository.findStatusesByShowIdAndSeatNumbers(
                        showId,
                        seatNumbers
                );

        if (seatStatuses.size() != seatNumbers.size()) {
            throw new ResourceNotFoundException(
                    "One or more requested seats do not exist"
            );
        }

        if (seatStatuses.stream().anyMatch(s -> s != SeatStatus.AVAILABLE)) {
            throw new SeatAlreadyReservedException(
                    "One or more requested seats are already reserved"
            );
        }

        /*
         * Lock user's limit row.
         */
        userShowLimitRepository.insertIfAbsent(
                showId,
                userId
        );

        UserShowLimit userLimit =
                userShowLimitRepository.findForUpdate(
                        showId,
                        userId
                ).orElseThrow(() ->
                        new IllegalStateException(
                                "Unable to create user show limit"
                        )
                );

        /*
         * Lock requested seats.
         */
        List<Seat> seats =
                seatRepository.findSeatsForUpdate(
                        showId,
                        seatNumbers
                );

        if (seats.size() != seatNumbers.size()) {
            throw new ResourceNotFoundException(
                    "One or more requested seats do not exist"
            );
        }

        int requestedCount = seats.size();

        /*
         * Check per-user active reservation limit.
         *
         * HELD + CONFIRMED reservations are both considered active.
         */
        if (userLimit.getActiveSeatCount() + requestedCount
                > show.getPerUserLimit()) {

            throw new ReservationLimitExceededException(
                    "User reservation limit exceeded"
            );
        }

        /*
         * Authoritative seat availability check (rows are locked).
         */
        for (Seat seat : seats) {

            if (seat.getStatus() != SeatStatus.AVAILABLE) {

                throw new SeatAlreadyReservedException(
                        "Seat already reserved: "
                                + seat.getSeatNumber()
                );
            }
        }

        /*
         * Calculate reservation amount.
         */
        long amountPaise =
                show.getPricePaise() * requestedCount;

        /*
         * Create reservation as HELD.
         *
         * Payment/confirmation happens separately.
         */
        Instant expiresAt =
                Instant.now()
                        .plus(HOLD_DURATION_MINUTES, ChronoUnit.MINUTES);

        Reservation reservation = Reservation.builder()
                .show(show)
                .userId(userId)
                .amountPaise(amountPaise)
                .status(ReservationStatus.HELD)
                .expiresAt(expiresAt)
                .build();

        reservation = reservationRepository.save(reservation);

        /*
         * Mark seats as HELD.
         */
        for (Seat seat : seats) {

            seat.setStatus(SeatStatus.HELD);

            seat.setCurrentReservationId(
                    reservation.getId()
            );
        }

        seatRepository.saveAll(seats);

        /*
         * Increase user's active seat count.
         */
        userLimit.setActiveSeatCount(
                userLimit.getActiveSeatCount()
                        + requestedCount
        );

        userShowLimitRepository.save(userLimit);

        /*
         * Create reservation-seat mappings.
         */
        for (Seat seat : seats) {

            ReservationSeat reservationSeat =
                    ReservationSeat.builder()
                            .id(
                                    new ReservationSeatId(
                                            reservation.getId(),
                                            seat.getId()
                                    )
                            )
                            .reservation(reservation)
                            .seat(seat)
                            .build();

            reservationSeatRepository.save(reservationSeat);
        }

        /*
         * Link reservation to idempotency record.
         */
        idempotency.setReservation(reservation);

        idempotencyKeyRepository.save(idempotency);

        log.info(
                "Seats held successfully: reservationId={}, userId={}, showId={}, seats={}, expiresAt={}",
                reservation.getId(),
                userId,
                showId,
                requestedCount,
                expiresAt
        );

        return toResponse(reservation);
    }


    // =========================================================
    // CONFIRM RESERVATION
    // =========================================================

    @Transactional
    public ReservationResponse confirmReservation(
            UUID reservationId,
            String userId
    ) {

        log.info(
                "Confirming reservation: reservationId={}, userId={}",
                reservationId,
                userId
        );

        /*
         * Lock reservation so two confirmation requests
         * cannot modify it simultaneously.
         */
        Reservation reservation =
                reservationRepository.findForUpdate(reservationId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Reservation not found: "
                                                + reservationId
                                )
                        );

        /*
         * Verify ownership.
         */
        if (!reservation.getUserId().equals(userId)) {

            throw new AccessDeniedException(
                    "You are not allowed to confirm this reservation"
            );
        }

        /*
         * Reservation must currently be HELD.
         */
        if (reservation.getStatus() != ReservationStatus.HELD) {

            throw new InvalidReservationStateException(
                    "Reservation cannot be confirmed because its status is "
                            + reservation.getStatus()
            );
        }

        /*
         * Check whether the hold has expired. This is exact and does not
         * depend on the sweeper having run yet.
         */
        if (reservation.getExpiresAt() != null
                && reservation.getExpiresAt().isBefore(Instant.now())) {

            throw new InvalidReservationStateException(
                    "Reservation hold has expired"
            );
        }

        /*
         * Lock reservation seats.
         */
        List<ReservationSeat> reservationSeats =
                reservationSeatRepository
                        .findByReservationIdForUpdate(
                                reservationId
                        );

        if (reservationSeats.isEmpty()) {

            throw new InvalidReservationStateException(
                    "Reservation has no seats"
            );
        }

        /*
         * Convert HELD seats to CONFIRMED.
         */
        for (ReservationSeat reservationSeat : reservationSeats) {

            Seat seat = reservationSeat.getSeat();

            if (seat.getStatus() != SeatStatus.HELD
                    || !reservationId.equals(
                    seat.getCurrentReservationId()
            )) {

                throw new InvalidReservationStateException(
                        "Seat "
                                + seat.getSeatNumber()
                                + " is not held by this reservation"
                );
            }

            seat.setStatus(SeatStatus.CONFIRMED);
        }

        reservation.setStatus(
                ReservationStatus.CONFIRMED
        );

        reservation.setExpiresAt(null);

        reservationRepository.save(reservation);

        seatRepository.saveAll(
                reservationSeats.stream()
                        .map(ReservationSeat::getSeat)
                        .toList()
        );

        log.info(
                "Reservation confirmed successfully: reservationId={}, seats={}",
                reservationId,
                reservationSeats.size()
        );

        return toResponse(reservation);
    }


    // =========================================================
    // CANCEL RESERVATION
    // =========================================================

    @Transactional
    public CancellationResponse cancelReservation(
            UUID reservationId,
            String userId
    ) {

        log.info(
                "Cancelling reservation: reservationId={}, userId={}",
                reservationId,
                userId
        );

        /*
         * Lock reservation.
         */
        Reservation reservation =
                reservationRepository.findForUpdate(reservationId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Reservation not found: "
                                                + reservationId
                                )
                        );

        /*
         * Verify ownership.
         */
        if (!reservation.getUserId().equals(userId)) {

            throw new AccessDeniedException(
                    "You are not allowed to cancel this reservation"
            );
        }

        /*
         * Both HELD and CONFIRMED reservations
         * can be cancelled.
         */
        if (reservation.getStatus() != ReservationStatus.HELD
                && reservation.getStatus() != ReservationStatus.CONFIRMED) {

            throw new InvalidReservationStateException(
                    "Reservation cannot be cancelled because its status is "
                            + reservation.getStatus()
            );
        }

        List<ReservationSeat> releasedSeats =
                releaseReservation(reservation);

        log.info(
                "Reservation cancelled successfully: reservationId={}, seats={}",
                reservationId,
                releasedSeats.size()
        );

        return CancellationResponse.builder()
                .reservationId(reservation.getId())
                .showId(reservation.getShow().getId())
                .userId(reservation.getUserId())
                .seats(
                        releasedSeats.stream()
                                .map(rs ->
                                        rs.getSeat().getSeatNumber()
                                )
                                .sorted()
                                .toList()
                )
                .amountPaise(
                        reservation.getAmountPaise()
                )
                .status(
                        reservation.getStatus().name()
                )
                .build();
    }


    // =========================================================
    // EXPIRE HOLD (called by HoldExpiryJob, one transaction per hold)
    // =========================================================

    /**
     * Releases a HELD reservation whose TTL has passed.
     *
     * Everything is re-checked under the reservation row lock, so racing with
     * a confirm, a cancel or another sweeper instance is safe: whoever locks
     * second sees the new state and does nothing.
     *
     * @return true if the hold was released
     */
    @Transactional
    public boolean expireReservation(UUID reservationId) {

        Reservation reservation =
                reservationRepository.findForUpdate(reservationId)
                        .orElse(null);

        if (reservation == null) {
            return false;
        }

        if (reservation.getStatus() != ReservationStatus.HELD
                || reservation.getExpiresAt() == null
                || reservation.getExpiresAt().isAfter(Instant.now())) {

            return false;
        }

        List<ReservationSeat> releasedSeats =
                releaseReservation(reservation);

        log.info(
                "Hold expired and released: reservationId={}, userId={}, seats={}",
                reservationId,
                reservation.getUserId(),
                releasedSeats.size()
        );

        return true;
    }


    // =========================================================
    // HELPERS
    // =========================================================

    /**
     * Frees every seat of a locked reservation, decrements the user's active
     * seat count and marks the reservation CANCELLED.
     *
     * Lock order matches reserve: user limit first, then the seats.
     * The caller must already hold the reservation row lock.
     */
    private List<ReservationSeat> releaseReservation(
            Reservation reservation
    ) {

        UUID reservationId = reservation.getId();

        /*
         * Lock user's active seat count.
         */
        UserShowLimit userShowLimit =
                userShowLimitRepository.findForUpdate(
                        reservation.getShow().getId(),
                        reservation.getUserId()
                ).orElseThrow(() ->
                        new ResourceNotFoundException(
                                "User show limit record not found"
                        )
                );

        /*
         * Lock reservation seats.
         */
        List<ReservationSeat> reservationSeats =
                reservationSeatRepository
                        .findByReservationIdForUpdate(
                                reservationId
                        );

        if (reservationSeats.isEmpty()) {

            throw new InvalidReservationStateException(
                    "Reservation has no seats"
            );
        }

        /*
         * Release seats.
         */
        for (ReservationSeat reservationSeat : reservationSeats) {

            Seat seat = reservationSeat.getSeat();

            if (!reservationId.equals(
                    seat.getCurrentReservationId()
            )) {

                throw new InvalidReservationStateException(
                        "Seat "
                                + seat.getSeatNumber()
                                + " is not associated with this reservation"
                );
            }

            if (seat.getStatus() != SeatStatus.HELD
                    && seat.getStatus() != SeatStatus.CONFIRMED) {

                throw new InvalidReservationStateException(
                        "Seat "
                                + seat.getSeatNumber()
                                + " cannot be released"
                );
            }

            seat.setStatus(SeatStatus.AVAILABLE);
            seat.setCurrentReservationId(null);
        }

        int newActiveCount =
                userShowLimit.getActiveSeatCount()
                        - reservationSeats.size();

        if (newActiveCount < 0) {

            throw new IllegalStateException(
                    "User active seat count cannot become negative"
            );
        }

        userShowLimit.setActiveSeatCount(newActiveCount);

        /*
         * Mark reservation cancelled (expired holds are recorded the same way).
         */
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(Instant.now());
        reservation.setExpiresAt(null);

        /*
         * Persist changes.
         */
        reservationRepository.save(reservation);

        seatRepository.saveAll(
                reservationSeats.stream()
                        .map(ReservationSeat::getSeat)
                        .toList()
        );

        userShowLimitRepository.save(userShowLimit);

        return reservationSeats;
    }


    private List<String> normalizeSeats(
            List<String> seats
    ) {

        if (seats == null || seats.isEmpty()) {

            throw new InvalidRequestException(
                    "At least one seat is required"
            );
        }

        List<String> normalized =
                seats.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .distinct()
                        .sorted()
                        .toList();

        if (normalized.size() != seats.size()) {

            throw new InvalidRequestException(
                    "Duplicate or invalid seat numbers"
            );
        }

        return normalized;
    }


    private String generateRequestHash(
            List<String> seats
    ) {

        String canonical =
                String.join(",", seats);

        try {

            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash =
                    digest.digest(
                            canonical.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            StringBuilder result =
                    new StringBuilder();

            for (byte b : hash) {

                result.append(
                        String.format("%02x", b)
                );
            }

            return result.toString();

        } catch (NoSuchAlgorithmException e) {

            throw new IllegalStateException(
                    "SHA-256 algorithm not available",
                    e
            );
        }
    }


    private ReservationResponse toResponse(
            Reservation reservation
    ) {

        List<String> seats =
                reservationSeatRepository
                        .findByIdReservationId(
                                reservation.getId()
                        )
                        .stream()
                        .map(rs ->
                                rs.getSeat().getSeatNumber()
                        )
                        .sorted()
                        .toList();

        return new ReservationResponse(
                reservation.getId(),
                reservation.getShow().getId(),
                reservation.getUserId(),
                seats,
                reservation.getAmountPaise(),
                reservation.getStatus()
        );
    }
}