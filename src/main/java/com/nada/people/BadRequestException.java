package com.nada.people;

/** The caller sent something invalid; maps to HTTP 400. */
final class BadRequestException extends RuntimeException {
    BadRequestException(String message) {
        super(message);
    }
}
