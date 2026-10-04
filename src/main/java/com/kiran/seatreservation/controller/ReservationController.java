package com.kiran.seatreservation.controller;

import com.kiran.seatreservation.dto.request.ReserveSeatRequest;
import com.kiran.seatreservation.dto.response.CancellationResponse;
import com.kiran.seatreservation.dto.response.ReservationResponse;
import com.kiran.seatreservation.metrics.ReservationMetrics;
import com.kiran.seatreservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.function.Supplier;

@RestController
@RequestMapping("/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationMetrics metrics;

    @PostMapping("/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID showId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReserveSeatRequest request,
            Authentication authentication
    ) {

        String userId = authentication.getName();

        ReservationResponse response = tracked("reserve", () ->
                reservationService.reserve(
                        showId,
                        userId,
                        idempotencyKey,
                        request
                )
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/{reservationId}/confirm")
    public ResponseEntity<ReservationResponse> confirmReservation(
            @PathVariable UUID reservationId,
            Authentication authentication
    ) {

        String userId = authentication.getName();

        ReservationResponse response = tracked("confirm", () ->
                reservationService.confirmReservation(
                        reservationId,
                        userId
                )
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{reservationId}/cancel")
    public ResponseEntity<CancellationResponse> cancelReservation(
            @PathVariable UUID reservationId,
            Authentication authentication
    ) {

        String userId = authentication.getName();

        CancellationResponse response = tracked("cancel", () ->
                reservationService.cancelReservation(
                        reservationId,
                        userId
                )
        );

        return ResponseEntity.ok(response);
    }

    private <T> T tracked(String operation, Supplier<T> action) {

        try {

            T result = action.get();
            metrics.success(operation);
            return result;

        } catch (RuntimeException e) {

            metrics.failure(operation, e);
            throw e;
        }
    }
}