package com.kiran.seatreservation.repository;

import com.kiran.seatreservation.entity.Seat;
import com.kiran.seatreservation.entity.enums.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findByShowIdOrderBySeatNumber(UUID showId);

    List<Seat> findByShowIdAndSeatNumberInOrderBySeatNumber(
            UUID showId,
            List<String> seatNumbers
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s
            FROM Seat s
            WHERE s.show.id = :showId
              AND s.seatNumber IN :seatNumbers
            ORDER BY s.seatNumber
            """)
    List<Seat> findSeatsForUpdate(
            @Param("showId") UUID showId,
            @Param("seatNumbers") List<String> seatNumbers
    );

    List<Seat> findByShowId(UUID showId);


    @Query("""
            SELECT s.status
            FROM Seat s
            WHERE s.show.id = :showId
              AND s.seatNumber IN :seatNumbers
            """)
    List<SeatStatus> findStatusesByShowIdAndSeatNumbers(
            @Param("showId") UUID showId,
            @Param("seatNumbers") List<String> seatNumbers
    );
}