package com.kiran.seatreservation.service;

import com.kiran.seatreservation.entity.enums.ReservationStatus;
import com.kiran.seatreservation.repository.ReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Releases holds whose TTL has passed.
 *
 * Safe to run on several instances at once: every release re-checks the
 * reservation under a row lock, so the second instance simply finds nothing
 * left to do.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HoldExpiryJob {

    private static final int BATCH_SIZE = 100;

    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final MeterRegistry meterRegistry;

    @Scheduled(
            initialDelayString = "10000",
            fixedDelayString = "${reservation.hold.sweep-interval-ms:5000}"
    )
    public void sweep() {

        try {

            int released = releaseExpiredHolds();

            if (released > 0) {
                log.info("Released {} expired holds", released);
            }

        } catch (RuntimeException e) {

            log.error("Hold expiry sweep failed", e);
        }
    }

    /**
     * @return number of holds released
     */
    public int releaseExpiredHolds() {

        int total = 0;

        while (true) {

            List<UUID> ids = reservationRepository.findExpiredIds(
                    ReservationStatus.HELD,
                    Instant.now(),
                    PageRequest.of(0, BATCH_SIZE)
            );

            if (ids.isEmpty()) {
                break;
            }

            int releasedInBatch = 0;

            for (UUID id : ids) {

                try {

                    if (reservationService.expireReservation(id)) {
                        releasedInBatch++;
                    }

                } catch (RuntimeException e) {

                    log.error("Failed to expire reservation {}", id, e);
                }
            }

            total += releasedInBatch;

            // Nothing progressed (all failed or lost a race): retry next tick.
            if (releasedInBatch == 0) {
                break;
            }
        }

        if (total > 0) {
            meterRegistry.counter("reservation.holds.expired").increment(total);
        }

        return total;
    }
}