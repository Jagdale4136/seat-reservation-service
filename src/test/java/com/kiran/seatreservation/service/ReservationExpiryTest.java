package com.kiran.seatreservation.service;

import com.kiran.seatreservation.entity.*;
import com.kiran.seatreservation.entity.enums.ReservationStatus;
import com.kiran.seatreservation.entity.enums.SeatStatus;
import com.kiran.seatreservation.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationExpiryTest {

    @Mock
    private ShowRepository showRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationSeatRepository reservationSeatRepository;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private UserShowLimitRepository userShowLimitRepository;

    @InjectMocks
    private ReservationService reservationService;

    private UUID showId;
    private UUID reservationId;
    private String userId;
    private Show show;

    @BeforeEach
    void setUp() {

        showId = UUID.randomUUID();
        reservationId = UUID.randomUUID();
        userId = "user-123";

        show = mock(Show.class);
    }

    @Test
    void expire_shouldReleaseLapsedHold() {

        when(show.getId()).thenReturn(showId);

        Reservation reservation = reservation(
                ReservationStatus.HELD,
                Instant.now().minusSeconds(60)
        );

        Seat seat = mock(Seat.class);
        when(seat.getStatus()).thenReturn(SeatStatus.HELD);
        when(seat.getCurrentReservationId()).thenReturn(reservationId);

        ReservationSeat reservationSeat = mock(ReservationSeat.class);
        when(reservationSeat.getSeat()).thenReturn(seat);

        UserShowLimit userLimit = mock(UserShowLimit.class);
        when(userLimit.getActiveSeatCount()).thenReturn(1);

        when(reservationRepository.findForUpdate(reservationId))
                .thenReturn(Optional.of(reservation));

        when(userShowLimitRepository.findForUpdate(showId, userId))
                .thenReturn(Optional.of(userLimit));

        when(reservationSeatRepository.findByReservationIdForUpdate(reservationId))
                .thenReturn(List.of(reservationSeat));

        boolean released = reservationService.expireReservation(reservationId);

        assertTrue(released);
        assertEquals(ReservationStatus.CANCELLED, reservation.getStatus());
        assertNull(reservation.getExpiresAt());

        verify(seat).setStatus(SeatStatus.AVAILABLE);
        verify(seat).setCurrentReservationId(null);
        verify(userLimit).setActiveSeatCount(0);
        verify(reservationRepository).save(reservation);
        verify(userShowLimitRepository).save(userLimit);
    }

    @Test
    void expire_shouldSkipHoldThatHasNotExpired() {

        Reservation reservation = reservation(
                ReservationStatus.HELD,
                Instant.now().plusSeconds(60)
        );

        when(reservationRepository.findForUpdate(reservationId))
                .thenReturn(Optional.of(reservation));

        assertFalse(reservationService.expireReservation(reservationId));

        verify(userShowLimitRepository, never()).findForUpdate(any(), any());
        verify(reservationSeatRepository, never()).findByReservationIdForUpdate(any());
        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    void expire_shouldSkipReservationThatIsNoLongerHeld() {

        // e.g. the user confirmed it just before the sweeper got the lock
        Reservation reservation = reservation(
                ReservationStatus.CONFIRMED,
                null
        );

        when(reservationRepository.findForUpdate(reservationId))
                .thenReturn(Optional.of(reservation));

        assertFalse(reservationService.expireReservation(reservationId));

        verify(reservationSeatRepository, never()).findByReservationIdForUpdate(any());
        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    void expire_shouldSkipMissingReservation() {

        when(reservationRepository.findForUpdate(reservationId))
                .thenReturn(Optional.empty());

        assertFalse(reservationService.expireReservation(reservationId));
    }

    private Reservation reservation(ReservationStatus status, Instant expiresAt) {

        return Reservation.builder()
                .id(reservationId)
                .show(show)
                .userId(userId)
                .amountPaise(25000L)
                .status(status)
                .expiresAt(expiresAt)
                .build();
    }
}