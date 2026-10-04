package com.kiran.seatreservation.exception;

import org.springframework.http.HttpStatus;

public class ReservationLimitExceededException extends BusinessException {

    public ReservationLimitExceededException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}