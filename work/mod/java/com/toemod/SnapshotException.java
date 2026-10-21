package com.toemod;

/** A backup could not be made or restored; the message is shown to the player as is. */
final class SnapshotException extends Exception {
    private static final long serialVersionUID = 1L;

    SnapshotException(String message) {
        super(message);
    }
}
