package dev.deekshita.payments;

import org.springframework.http.HttpStatus;

/** Domain rule violation that maps to a specific HTTP status and a stable error code. */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static BusinessException notFound(String what, Object id) {
        return new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " was not found");
    }

    public static BusinessException unprocessable(String code, String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
