package com.kiran.seatreservation.dto;

import com.kiran.seatreservation.dto.response.SeatResponse;
import com.kiran.seatreservation.dto.response.ShowResponse;
import com.kiran.seatreservation.entity.Seat;
import com.kiran.seatreservation.entity.Show;

import java.util.List;

public final class ShowMapper {

    private ShowMapper() {
    }

    public static ShowResponse toResponse(
            Show show,
            List<Seat> seats
    ) {

        List<SeatResponse> seatResponses = seats.stream()
                .map(seat -> new SeatResponse(
                        seat.getSeatNumber(),
                        seat.getStatus().name()
                ))
                .toList();

        int available = (int) seats.stream()
                .filter(seat -> seat.getStatus().name().equals("AVAILABLE"))
                .count();

        int held = (int) seats.stream()
                .filter(seat -> seat.getStatus().name().equals("HELD"))
                .count();

        int confirmed = (int) seats.stream()
                .filter(seat -> seat.getStatus().name().equals("CONFIRMED"))
                .count();

        return new ShowResponse(
                show.getId(),
                show.getName(),
                show.getPricePaise(),
                show.getPerUserLimit(),
                seats.size(),
                available,
                held,
                confirmed,
                seatResponses
        );
    }
}