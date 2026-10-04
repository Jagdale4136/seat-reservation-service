package com.kiran.seatreservation.exception;

import org.springframework.http.HttpStatus;

public class IdempotencyConflictException extends BusinessException {

    public IdempotencyConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}