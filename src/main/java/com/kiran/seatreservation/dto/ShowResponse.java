package com.kiran.seatreservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class ShowResponse {

    private UUID id;
    private String name;
    private Long pricePaise;
    private Integer perUserLimit;

    private int totalSeats;
    private int availableSeats;
    private int heldSeats;
    private int confirmedSeats;

    private List<SeatResponse> seats;
}