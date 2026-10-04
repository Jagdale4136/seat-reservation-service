package com.kiran.seatreservation.exception;

import org.springframework.http.HttpStatus;

public class SeatAlreadyReservedException extends BusinessException {

    public SeatAlreadyReservedException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}