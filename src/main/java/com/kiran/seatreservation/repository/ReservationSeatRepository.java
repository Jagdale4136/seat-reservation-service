package com.kiran.seatreservation.repository;

import com.kiran.seatreservation.entity.ReservationSeat;
import com.kiran.seatreservation.entity.ReservationSeatId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReservationSeatRepository
        extends JpaRepository<ReservationSeat, ReservationSeatId> {

    List<ReservationSeat> findByIdReservationId(UUID reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT rs
            FROM ReservationSeat rs
            JOIN FETCH rs.seat
            WHERE rs.id.reservationId = :reservationId
            ORDER BY rs.seat.seatNumber
            """)
    List<ReservationSeat> findByReservationIdForUpdate(
            @Param("reservationId") UUID reservationId
    );
}