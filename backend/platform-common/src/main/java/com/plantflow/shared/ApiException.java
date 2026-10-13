package com.plantflow.shared;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public ApiException(HttpStatus status, String message) { super(message); this.status = status; }
    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
    public static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "You do not have permission for this action."); }
    public static ApiException missing(String resource) { return new ApiException(HttpStatus.NOT_FOUND, resource + " was not found."); }
}
