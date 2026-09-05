package dev.authsys;

/**
 * Exception thrown when an API call fails or a cryptographic operation errors.
 * Carries both a machine-readable error code and a human-readable message.
 */
public class AuthException extends Exception {

    private final String code;

    /**
     * @param code    machine-readable error code (e.g. "INVALID_CREDENTIALS", "BANNED")
     * @param message human-readable description
     */
    public AuthException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @param code    machine-readable error code
     * @param message human-readable description
     * @param cause   underlying exception
     */
    public AuthException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Machine-readable error code from the server (e.g. "INVALID_CREDENTIALS"). */
    public String getCode() {
        return code;
    }

    @Override
    public String toString() {
        return "AuthException{code='" + code + "', message='" + getMessage() + "'}";
    }
}
