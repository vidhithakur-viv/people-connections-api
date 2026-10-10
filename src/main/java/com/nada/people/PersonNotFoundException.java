package com.nada.people;

/** A referenced person does not exist; maps to HTTP 404. */
final class PersonNotFoundException extends RuntimeException {
    PersonNotFoundException(long id) {
        super("no person " + id);
    }
}
