package com.kiran.seatreservation.repository;

import com.kiran.seatreservation.entity.Show;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ShowRepository extends JpaRepository<Show, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s
        FROM Show s
        WHERE s.id = :showId
        """)
    Optional<Show> findByIdForUpdate(
            @Param("showId") UUID showId
    );
}