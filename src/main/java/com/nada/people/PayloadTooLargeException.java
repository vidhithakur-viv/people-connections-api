package com.nada.people;

/** The request body exceeds the size limit; maps to HTTP 413. */
final class PayloadTooLargeException extends RuntimeException {
    PayloadTooLargeException(String message) {
        super(message);
    }
}
