package com.eventledger.gateway.exception;

public class AccountServiceClientException extends RuntimeException {
    private final int statusCode;

    public AccountServiceClientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() { return statusCode; }
}
