package com.kiran.seatreservation.exception;

import org.springframework.http.HttpStatus;

public class InvalidReservationStateException extends BusinessException {

    public InvalidReservationStateException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}