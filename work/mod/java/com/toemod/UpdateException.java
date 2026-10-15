package com.toemod;

/** An update was refused by policy or could not be completed; the message is shown to the user. */
final class UpdateException extends Exception {
    private static final long serialVersionUID = 1L;

    UpdateException(String message) {
        super(message);
    }
}
