package com.kiran.seatreservation.dto;

import com.kiran.seatreservation.entity.enums.SeatStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SeatResponse {

    private String seat;
    private SeatStatus status;
}