package com.kiran.seatreservation.repository;

import com.kiran.seatreservation.entity.UserShowLimit;
import com.kiran.seatreservation.entity.UserShowLimitId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface UserShowLimitRepository
        extends JpaRepository<UserShowLimit, UserShowLimitId> {

    @Modifying
    @Query(value = """
            INSERT INTO user_show_limits
                (show_id, user_id, active_seat_count)
            VALUES
                (:showId, :userId, 0)
            ON CONFLICT (show_id, user_id)
            DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("showId") UUID showId,
            @Param("userId") String userId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT u
            FROM UserShowLimit u
            WHERE u.id.showId = :showId
              AND u.id.userId = :userId
            """)
    Optional<UserShowLimit> findForUpdate(
            @Param("showId") UUID showId,
            @Param("userId") String userId
    );
}