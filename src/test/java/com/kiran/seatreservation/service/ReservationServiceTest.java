package com.kiran.seatreservation.service;

import com.kiran.seatreservation.dto.request.ReserveSeatRequest;
import com.kiran.seatreservation.dto.response.CancellationResponse;
import com.kiran.seatreservation.dto.response.ReservationResponse;
import com.kiran.seatreservation.entity.*;
import com.kiran.seatreservation.entity.enums.ReservationStatus;
import com.kiran.seatreservation.entity.enums.SeatStatus;
import com.kiran.seatreservation.exception.*;
import com.kiran.seatreservation.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

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
    private UUID seatId;
    private UUID reservationId;

    private String userId;

    private Show show;
    private Seat seat;
    private UserShowLimit userLimit;

    @BeforeEach
    void setUp() {

        showId = UUID.randomUUID();
        seatId = UUID.randomUUID();
        reservationId = UUID.randomUUID();

        userId = "user-123";

        show = mock(Show.class);
        seat = mock(Seat.class);
        userLimit = mock(UserShowLimit.class);
    }

    // =========================================================
    // RESERVATION TESTS
    // =========================================================

    @Test
    void reserve_shouldCreateHeldReservationForAvailableSeat() {

        when(show.getId()).thenReturn(showId);
        when(show.getPricePaise()).thenReturn(25000L);
        when(show.getPerUserLimit()).thenReturn(4);

        when(seat.getId()).thenReturn(seatId);
        when(seat.getStatus()).thenReturn(SeatStatus.AVAILABLE);

        when(userLimit.getActiveSeatCount()).thenReturn(0);

        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(idempotencyKeyRepository.findForUpdate(
                eq(showId),
                eq(userId),
                eq("key-1")
        )).thenReturn(Optional.of(newIdempotencyKey()));

        when(seatRepository.findStatusesByShowIdAndSeatNumbers(
                showId,
                List.of("A1")
        )).thenReturn(List.of(SeatStatus.AVAILABLE));

        when(userShowLimitRepository.findForUpdate(
                showId,
                userId
        )).thenReturn(Optional.of(userLimit));

        when(seatRepository.findSeatsForUpdate(
                eq(showId),
                eq(List.of("A1"))
        )).thenReturn(List.of(seat));

        Reservation savedReservation = Reservation.builder()
                .id(reservationId)
                .show(show)
                .userId(userId)
                .amountPaise(25000L)
                .status(ReservationStatus.HELD)
                .build();

        when(reservationRepository.save(any(Reservation.class)))
                .thenReturn(savedReservation);

        when(reservationSeatRepository.findByIdReservationId(
                reservationId
        )).thenReturn(List.of());

        ReservationResponse response =
                reservationService.reserve(
                        showId,
                        userId,
                        "key-1",
                        new ReserveSeatRequest(List.of("A1"))
                );

        assertNotNull(response);
        assertEquals(reservationId, response.reservationId());
        assertEquals(showId, response.showId());
        assertEquals(userId, response.userId());
        assertEquals(25000L, response.amountPaise());
        assertEquals(ReservationStatus.HELD, response.status());

        verify(reservationRepository).save(any(Reservation.class));
        verify(seatRepository).saveAll(anyList());
        verify(reservationSeatRepository).save(any(ReservationSeat.class));
        verify(idempotencyKeyRepository).save(any(IdempotencyKey.class));
        verify(userShowLimitRepository).save(userLimit);

        // userLimit is a mock: verify the setter call instead of reading the getter
        verify(userLimit).setActiveSeatCount(1);
    }

    @Test
    void reserve_shouldReserveMultipleSeats() {

        Seat seatA1 = mock(Seat.class);
        Seat seatA2 = mock(Seat.class);

        when(show.getId()).thenReturn(showId);
        when(show.getPricePaise()).thenReturn(25000L);
        when(show.getPerUserLimit()).thenReturn(4);

        when(userLimit.getActiveSeatCount()).thenReturn(0);

        when(seatA1.getId()).thenReturn(UUID.randomUUID());
        when(seatA1.getStatus()).thenReturn(SeatStatus.AVAILABLE);

        when(seatA2.getId()).thenReturn(UUID.randomUUID());
        when(seatA2.getStatus()).thenReturn(SeatStatus.AVAILABLE);

        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(idempotencyKeyRepository.findForUpdate(
                eq(showId),
                eq(userId),
                eq("key-2")
        )).thenReturn(Optional.of(newIdempotencyKey()));

        when(seatRepository.findStatusesByShowIdAndSeatNumbers(
                showId,
                List.of("A1", "A2")
        )).thenReturn(List.of(SeatStatus.AVAILABLE, SeatStatus.AVAILABLE));

        when(userShowLimitRepository.findForUpdate(
                showId,
                userId
        )).thenReturn(Optional.of(userLimit));

        when(seatRepository.findSeatsForUpdate(
                eq(showId),
                eq(List.of("A1", "A2"))
        )).thenReturn(List.of(seatA1, seatA2));

        Reservation savedReservation =
                Reservation.builder()
                        .id(reservationId)
                        .show(show)
                        .userId(userId)
                        .amountPaise(50000L)
                        .status(ReservationStatus.HELD)
                        .build();

        when(reservationRepository.save(any(Reservation.class)))
                .thenReturn(savedReservation);

        when(reservationSeatRepository.findByIdReservationId(
                reservationId
        )).thenReturn(List.of());

        ReservationResponse response =
                reservationService.reserve(
                        showId,
                        userId,
                        "key-2",
                        new ReserveSeatRequest(List.of("A1", "A2"))
                );

        assertNotNull(response);
        assertEquals(50000L, response.amountPaise());
        assertEquals(ReservationStatus.HELD, response.status());

        verify(reservationSeatRepository, times(2))
                .save(any(ReservationSeat.class));

        verify(userLimit).setActiveSeatCount(2);
    }

    /**
     * Fast-fail path: the unlocked status read already shows the seat taken,
     * so the request must be rejected without touching any lock.
     */
    @Test
    void reserve_shouldFastFailWhenSeatAlreadyHeld() {

        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(idempotencyKeyRepository.findForUpdate(
                eq(showId),
                eq(userId),
                eq("key-3")
        )).thenReturn(Optional.of(newIdempotencyKey()));

        when(seatRepository.findStatusesByShowIdAndSeatNumbers(
                showId,
                List.of("A1")
        )).thenReturn(List.of(SeatStatus.HELD));

        assertThrows(
                SeatAlreadyReservedException.class,
                () -> reservationService.reserve(
                        showId,
                        userId,
                        "key-3",
                        new ReserveSeatRequest(List.of("A1"))
                )
        );

        verify(userShowLimitRepository, never())
                .findForUpdate(any(), any());

        verify(seatRepository, never())
                .findSeatsForUpdate(any(), anyList());

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    /**
     * Race path: the unlocked read said AVAILABLE, but another transaction
     * took the seat before our FOR UPDATE. The locked check must still reject.
     */
    @Test
    void reserve_shouldRejectSeatTakenAfterFastCheck() {

        when(show.getPerUserLimit()).thenReturn(4);

        when(userLimit.getActiveSeatCount()).thenReturn(0);

        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(idempotencyKeyRepository.findForUpdate(
                eq(showId),
                eq(userId),
                eq("key-3b")
        )).thenReturn(Optional.of(newIdempotencyKey()));

        when(seatRepository.findStatusesByShowIdAndSeatNumbers(
                showId,
                List.of("A1")
        )).thenReturn(List.of(SeatStatus.AVAILABLE));

        when(userShowLimitRepository.findForUpdate(
                showId,
                userId
        )).thenReturn(Optional.of(userLimit));

        when(seat.getSeatNumber()).thenReturn("A1");
        when(seat.getStatus()).thenReturn(SeatStatus.HELD);

        when(seatRepository.findSeatsForUpdate(
                eq(showId),
                eq(List.of("A1"))
        )).thenReturn(List.of(seat));

        assertThrows(
                SeatAlreadyReservedException.class,
                () -> reservationService.reserve(
                        showId,
                        userId,
                        "key-3b",
                        new ReserveSeatRequest(List.of("A1"))
                )
        );

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    void reserve_shouldRejectNonExistingSeat() {

        // The fast check finds no matching seat, so the service throws
        // before locking anything.
        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(idempotencyKeyRepository.findForUpdate(
                eq(showId),
                eq(userId),
                eq("key-4")
        )).thenReturn(Optional.of(newIdempotencyKey()));

        when(seatRepository.findStatusesByShowIdAndSeatNumbers(
                showId,
                List.of("A99")
        )).thenReturn(List.of());

        assertThrows(
                ResourceNotFoundException.class,
                () -> reservationService.reserve(
                        showId,
                        userId,
                        "key-4",
                        new ReserveSeatRequest(List.of("A99"))
                )
        );

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    void reserve_shouldRejectWhenUserLimitIsExceeded() {

        when(show.getPerUserLimit()).thenReturn(2);

        when(userLimit.getActiveSeatCount()).thenReturn(2);

        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(idempotencyKeyRepository.findForUpdate(
                eq(showId),
                eq(userId),
                eq("key-5")
        )).thenReturn(Optional.of(newIdempotencyKey()));

        when(seatRepository.findStatusesByShowIdAndSeatNumbers(
                showId,
                List.of("A1")
        )).thenReturn(List.of(SeatStatus.AVAILABLE));

        when(userShowLimitRepository.findForUpdate(
                showId,
                userId
        )).thenReturn(Optional.of(userLimit));

        // Seats are locked BEFORE the limit check, so the seat must exist
        when(seatRepository.findSeatsForUpdate(
                eq(showId),
                eq(List.of("A1"))
        )).thenReturn(List.of(seat));

        assertThrows(
                ReservationLimitExceededException.class,
                () -> reservationService.reserve(
                        showId,
                        userId,
                        "key-5",
                        new ReserveSeatRequest(List.of("A1"))
                )
        );

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    void reserve_shouldRejectEmptySeatList() {

        assertThrows(
                InvalidRequestException.class,
                () -> reservationService.reserve(
                        showId,
                        userId,
                        "key-6",
                        new ReserveSeatRequest(List.of())
                )
        );

        verifyNoInteractions(showRepository);
        verifyNoInteractions(reservationRepository);
    }

    @Test
    void reserve_shouldRejectDuplicateSeats() {

        assertThrows(
                InvalidRequestException.class,
                () -> reservationService.reserve(
                        showId,
                        userId,
                        "key-7",
                        new ReserveSeatRequest(List.of("A1", "A1"))
                )
        );

        verifyNoInteractions(showRepository);
        verifyNoInteractions(reservationRepository);
    }

    @Test
    void reserve_shouldRejectMissingIdempotencyKey() {

        assertThrows(
                InvalidRequestException.class,
                () -> reservationService.reserve(
                        showId,
                        userId,
                        null,
                        new ReserveSeatRequest(List.of("A1"))
                )
        );

        verifyNoInteractions(showRepository);
        verifyNoInteractions(reservationRepository);
    }

    // =========================================================
    // IDEMPOTENCY TESTS
    // =========================================================

    @Test
    void reserve_shouldReturnExistingReservationForSameIdempotencyKey() {

        when(show.getId()).thenReturn(showId);

        Reservation existingReservation =
                Reservation.builder()
                        .id(reservationId)
                        .show(show)
                        .userId(userId)
                        .amountPaise(25000L)
                        .status(ReservationStatus.HELD)
                        .build();

        IdempotencyKey key = newIdempotencyKey();

        key.setReservation(existingReservation);
        key.setRequestHash(sha256("A1"));

        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(idempotencyKeyRepository.findForUpdate(
                showId,
                userId,
                "key-9"
        )).thenReturn(Optional.of(key));

        when(reservationSeatRepository
                .findByIdReservationId(reservationId))
                .thenReturn(List.of());

        ReservationResponse response =
                reservationService.reserve(
                        showId,
                        userId,
                        "key-9",
                        new ReserveSeatRequest(List.of("A1"))
                );

        assertNotNull(response);
        assertEquals(reservationId, response.reservationId());
        assertEquals(ReservationStatus.HELD, response.status());

        verify(reservationRepository, never()).save(any(Reservation.class));

        // A replay must be served before the seat fast-check: the seat is
        // legitimately HELD by this very reservation.
        verify(seatRepository, never())
                .findStatusesByShowIdAndSeatNumbers(any(), anyList());
    }

    @Test
    void reserve_shouldRejectDifferentRequestWithSameIdempotencyKey() {

        Reservation existingReservation =
                Reservation.builder()
                        .id(reservationId)
                        .show(show)
                        .userId(userId)
                        .amountPaise(25000L)
                        .status(ReservationStatus.HELD)
                        .build();

        IdempotencyKey key = newIdempotencyKey();

        key.setReservation(existingReservation);

        // Original request was A1
        key.setRequestHash(sha256("A1"));

        when(showRepository.findById(showId))
                .thenReturn(Optional.of(show));

        when(idempotencyKeyRepository.findForUpdate(
                showId,
                userId,
                "key-10"
        )).thenReturn(Optional.of(key));

        assertThrows(
                IdempotencyConflictException.class,
                () -> reservationService.reserve(
                        showId,
                        userId,
                        "key-10",
                        new ReserveSeatRequest(List.of("A2"))
                )
        );

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    // =========================================================
    // CONFIRM TESTS
    // =========================================================

    @Test
    void confirm_shouldConfirmHeldReservation() {

        when(show.getId()).thenReturn(showId);

        Reservation reservation =
                Reservation.builder()
                        .id(reservationId)
                        .show(show)
                        .userId(userId)
                        .amountPaise(25000L)
                        .status(ReservationStatus.HELD)
                        .build();

        Seat reservationSeatEntity = mock(Seat.class);

        when(reservationSeatEntity.getSeatNumber()).thenReturn("A1");
        when(reservationSeatEntity.getStatus()).thenReturn(SeatStatus.HELD);
        when(reservationSeatEntity.getCurrentReservationId())
                .thenReturn(reservationId);

        ReservationSeat reservationSeat = mock(ReservationSeat.class);

        when(reservationSeat.getSeat()).thenReturn(reservationSeatEntity);

        when(reservationRepository.findForUpdate(reservationId))
                .thenReturn(Optional.of(reservation));

        when(reservationSeatRepository
                .findByReservationIdForUpdate(reservationId))
                .thenReturn(List.of(reservationSeat));

        when(reservationRepository.save(reservation))
                .thenReturn(reservation);

        when(reservationSeatRepository
                .findByIdReservationId(reservationId))
                .thenReturn(List.of(reservationSeat));

        ReservationResponse response =
                reservationService.confirmReservation(
                        reservationId,
                        userId
                );

        assertNotNull(response);
        assertEquals(ReservationStatus.CONFIRMED, response.status());
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());

        verify(reservationRepository).save(reservation);
        verify(reservationSeatEntity).setStatus(SeatStatus.CONFIRMED);
        verify(seatRepository).saveAll(anyList());
    }

    @Test
    void confirm_shouldRejectWrongUser() {

        Reservation reservation =
                Reservation.builder()
                        .id(reservationId)
                        .userId("actual-user")
                        .status(ReservationStatus.HELD)
                        .build();

        when(reservationRepository.findForUpdate(reservationId))
                .thenReturn(Optional.of(reservation));

        assertThrows(
                AccessDeniedException.class,
                () -> reservationService.confirmReservation(
                        reservationId,
                        userId
                )
        );

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    void confirm_shouldRejectNonHeldReservation() {

        Reservation reservation =
                Reservation.builder()
                        .id(reservationId)
                        .userId(userId)
                        .status(ReservationStatus.CONFIRMED)
                        .build();

        when(reservationRepository.findForUpdate(reservationId))
                .thenReturn(Optional.of(reservation));

        assertThrows(
                InvalidReservationStateException.class,
                () -> reservationService.confirmReservation(
                        reservationId,
                        userId
                )
        );

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    // =========================================================
    // CANCEL TESTS
    // =========================================================

    @Test
    void cancel_shouldCancelHeldReservation() {

        when(show.getId()).thenReturn(showId);

        Reservation reservation =
                Reservation.builder()
                        .id(reservationId)
                        .show(show)
                        .userId(userId)
                        .amountPaise(25000L)
                        .status(ReservationStatus.HELD)
                        .build();

        Seat reservationSeatEntity = mock(Seat.class);

        when(reservationSeatEntity.getSeatNumber()).thenReturn("A1");
        when(reservationSeatEntity.getStatus()).thenReturn(SeatStatus.HELD);
        when(reservationSeatEntity.getCurrentReservationId())
                .thenReturn(reservationId);

        ReservationSeat reservationSeat = mock(ReservationSeat.class);

        when(reservationSeat.getSeat()).thenReturn(reservationSeatEntity);

        when(reservationRepository.findForUpdate(reservationId))
                .thenReturn(Optional.of(reservation));

        when(reservationSeatRepository
                .findByReservationIdForUpdate(reservationId))
                .thenReturn(List.of(reservationSeat));

        when(userShowLimitRepository.findForUpdate(showId, userId))
                .thenReturn(Optional.of(userLimit));

        when(userLimit.getActiveSeatCount()).thenReturn(1);

        CancellationResponse response =
                reservationService.cancelReservation(
                        reservationId,
                        userId
                );

        assertNotNull(response);
        assertEquals(reservationId, response.reservationId());
        assertEquals(ReservationStatus.CANCELLED.name(), response.status());
        assertEquals(ReservationStatus.CANCELLED, reservation.getStatus());

        verify(reservationRepository).save(reservation);
        verify(seatRepository).saveAll(anyList());
        verify(userShowLimitRepository).save(userLimit);

        verify(reservationSeatEntity).setStatus(SeatStatus.AVAILABLE);
        verify(reservationSeatEntity).setCurrentReservationId(null);

        // 1 active seat - 1 cancelled = 0
        verify(userLimit).setActiveSeatCount(0);
    }

    @Test
    void cancel_shouldRejectWrongUser() {

        Reservation reservation =
                Reservation.builder()
                        .id(reservationId)
                        .userId("actual-user")
                        .status(ReservationStatus.HELD)
                        .build();

        when(reservationRepository.findForUpdate(reservationId))
                .thenReturn(Optional.of(reservation));

        assertThrows(
                AccessDeniedException.class,
                () -> reservationService.cancelReservation(
                        reservationId,
                        userId
                )
        );

        verify(reservationRepository, never()).save(any(Reservation.class));
    }

    @Test
    void cancel_shouldRejectAlreadyCancelledReservation() {

        Reservation reservation =
                Reservation.builder()
                        .id(reservationId)
                        .userId(userId)
                        .status(ReservationStatus.CANCELLED)
                        .build();

        when(reservationRepository.findForUpdate(reservationId))
                .thenReturn(Optional.of(reservation));

        assertThrows(
                InvalidReservationStateException.class,
                () -> reservationService.cancelReservation(
                        reservationId,
                        userId
                )
        );

        verify(reservationSeatRepository, never())
                .findByReservationIdForUpdate(any());
    }

    // =========================================================
    // HELPER METHODS
    // =========================================================

    private IdempotencyKey newIdempotencyKey() {

        IdempotencyKey key = new IdempotencyKey();

        key.setId(UUID.randomUUID());
        key.setUserId(userId);
        key.setIdempotencyKey(UUID.randomUUID().toString());

        return key;
    }

    private String sha256(String value) {

        try {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            StringBuilder result = new StringBuilder();

            for (byte b : hash) {
                result.append(String.format("%02x", b));
            }

            return result.toString();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}