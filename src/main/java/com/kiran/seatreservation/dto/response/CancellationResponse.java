package com.kiran.seatreservation.dto.response;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder
public record CancellationResponse(
        UUID reservationId,
        UUID showId,
        String userId,
        List<String> seats,
        Long amountPaise,
        String status
) {
}