package com.kiran.seatreservation.dto.response;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder
public record ShowResponse(

        UUID id,

        String name,

        Long price_paise,

        Integer per_user_limit,

        Integer total_seats,

        Integer available_seats,

        Integer held_seats,

        Integer confirmed_seats,

        List<SeatResponse> seats

) {
}