package com.kiran.seatreservation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReserveSeatRequest(

        @NotEmpty(message = "At least one seat must be requested")
        List<@NotBlank(message = "Seat number cannot be blank") String> seats

//        @NotBlank(message = "Idempotency key is required")
//        String idempotency_key

) {
}