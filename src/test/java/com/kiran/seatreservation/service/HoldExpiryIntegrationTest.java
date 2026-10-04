package com.kiran.seatreservation.service;

import com.kiran.seatreservation.PostgresTestBase;
import com.kiran.seatreservation.dto.request.ReserveSeatRequest;
import com.kiran.seatreservation.dto.response.ReservationResponse;
import com.kiran.seatreservation.entity.Reservation;
import com.kiran.seatreservation.entity.Seat;
import com.kiran.seatreservation.entity.Show;
import com.kiran.seatreservation.entity.enums.ReservationStatus;
import com.kiran.seatreservation.entity.enums.SeatStatus;
import com.kiran.seatreservation.exception.InvalidReservationStateException;
import com.kiran.seatreservation.repository.ReservationRepository;
import com.kiran.seatreservation.repository.SeatRepository;
import com.kiran.seatreservation.repository.ShowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class HoldExpiryIntegrationTest extends PostgresTestBase {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private HoldExpiryJob holdExpiryJob;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void expiredHold_shouldFreeSeatAndUserQuota() {

        // per-user limit of 1: a second reservation only works if the
        // expired hold gave its quota back
        Show show = showRepository.save(
                Show.builder()
                        .name("expiry-test-show")
                        .pricePaise(25000L)
                        .perUserLimit(1)
                        .build()
        );

        seatRepository.saveAll(List.of(
                seat(show, "A1"),
                seat(show, "A2")
        ));

        UUID showId = show.getId();

        ReservationResponse held = reservationService.reserve(
                showId,
                "user-a",
                UUID.randomUUID().toString(),
                new ReserveSeatRequest(List.of("A1"))
        );

        // Force the hold into the past
        jdbcTemplate.update(
                "UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                held.reservationId()
        );

        // Confirm must already refuse a lapsed hold, before any sweep
        assertThrows(
                InvalidReservationStateException.class,
                () -> reservationService.confirmReservation(
                        held.reservationId(),
                        "user-a"
                )
        );

        int released = holdExpiryJob.releaseExpiredHolds();

        assertTrue(released >= 1);

        Reservation expired =
                reservationRepository.findById(held.reservationId()).orElseThrow();

        assertEquals(ReservationStatus.CANCELLED, expired.getStatus());

        Seat a1 = seatRepository
                .findByShowIdAndSeatNumberInOrderBySeatNumber(showId, List.of("A1"))
                .get(0);

        assertEquals(SeatStatus.AVAILABLE, a1.getStatus());
        assertNull(a1.getCurrentReservationId());

        // Quota was given back: user-a can reserve again despite limit 1
        ReservationResponse again = reservationService.reserve(
                showId,
                "user-a",
                UUID.randomUUID().toString(),
                new ReserveSeatRequest(List.of("A2"))
        );

        assertEquals(ReservationStatus.HELD, again.status());

        // The freed seat can be taken by someone else
        ReservationResponse other = reservationService.reserve(
                showId,
                "user-b",
                UUID.randomUUID().toString(),
                new ReserveSeatRequest(List.of("A1"))
        );

        assertEquals(ReservationStatus.HELD, other.status());

        // A second sweep has nothing left to release for this show
        assertEquals(0, holdExpiryJob.releaseExpiredHolds());
    }

    private Seat seat(Show show, String seatNumber) {

        return Seat.builder()
                .show(show)
                .seatNumber(seatNumber)
                .status(SeatStatus.AVAILABLE)
                .build();
    }
}