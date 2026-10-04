package com.kiran.seatreservation.repository;

import com.kiran.seatreservation.entity.Reservation;
import com.kiran.seatreservation.entity.enums.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository
        extends JpaRepository<Reservation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r
            FROM Reservation r
            WHERE r.id = :reservationId
            """)
    Optional<Reservation> findForUpdate(
            @Param("reservationId") UUID reservationId
    );

    List<Reservation> findByShowId(UUID showId);

    /**
     * Candidate ids only (no locks). Each candidate is re-checked under a
     * row lock before it is released.
     */
    @Query("""
            SELECT r.id
            FROM Reservation r
            WHERE r.status = :status
              AND r.expiresAt < :now
            ORDER BY r.expiresAt
            """)
    List<UUID> findExpiredIds(
            @Param("status") ReservationStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );
}