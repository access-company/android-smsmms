package com.google.android.mms;

public class UnsupportedPartStructureException extends MmsException {
    public UnsupportedPartStructureException() {
    }

    public UnsupportedPartStructureException(String message) {
        super(message);
    }

    public UnsupportedPartStructureException(Throwable cause) {
        super(cause);
    }

    public UnsupportedPartStructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
