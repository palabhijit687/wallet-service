package com.paytm.pml.wallet.web;

import org.springframework.http.HttpStatus;

/**
 * Carries an HTTP status + a stable machine-readable error code to the client.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
