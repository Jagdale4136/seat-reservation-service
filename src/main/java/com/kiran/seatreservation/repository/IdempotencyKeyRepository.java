package com.kiran.seatreservation.repository;

import com.kiran.seatreservation.entity.IdempotencyKey;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository
        extends JpaRepository<IdempotencyKey, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT i
            FROM IdempotencyKey i
            WHERE i.show.id = :showId
              AND i.userId = :userId
              AND i.idempotencyKey = :idempotencyKey
            """)
    Optional<IdempotencyKey> findForUpdate(
            @Param("showId") UUID showId,
            @Param("userId") String userId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys
                (id, show_id, user_id, idempotency_key, request_hash, created_at)
            VALUES
                (:id, :showId, :userId, :idempotencyKey, :requestHash, CURRENT_TIMESTAMP)
            ON CONFLICT (show_id, user_id, idempotency_key)
            DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("showId") UUID showId,
            @Param("userId") String userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash
    );
}