package com.kiran.seatreservation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record CreateShowRequest(

        @NotBlank(message = "Show name is required")
        String name,

        @NotEmpty(message = "At least one seat is required")
        List<@NotBlank(message = "Seat number cannot be blank") String> seats,

        @NotNull(message = "Price in paise is required")
        @Positive(message = "Price in paise must be greater than zero")
        Long price_paise,

        @Positive(message = "Per user limit must be greater than zero")
        Integer per_user_limit

) {
}