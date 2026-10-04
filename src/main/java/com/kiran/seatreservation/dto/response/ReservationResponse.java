package com.kiran.seatreservation.dto.response;

import com.kiran.seatreservation.entity.enums.ReservationStatus;

import java.util.List;
import java.util.UUID;

public record ReservationResponse(

        UUID reservationId,

        UUID showId,

        String userId,

        List<String> seats,

        Long amountPaise,

        ReservationStatus status

) {
}