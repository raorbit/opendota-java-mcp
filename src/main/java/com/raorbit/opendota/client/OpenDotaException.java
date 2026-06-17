package com.raorbit.opendota.client;

/**
 * Checked exception representing a failed OpenDota API interaction.
 *
 * <p>A {@link #statusCode()} of {@code 0} indicates a transport-level failure
 * (connection error, timeout) rather than an HTTP error response.
 */
public class OpenDotaException extends Exception {

    private final int statusCode;
    private final String endpoint;
    private final String responseBody;

    public OpenDotaException(int statusCode, String endpoint, String responseBody, Throwable cause) {
        super(statusCode + " " + endpoint + ": " + responseBody, cause);
        this.statusCode = statusCode;
        this.endpoint = endpoint;
        this.responseBody = responseBody;
    }

    public OpenDotaException(int statusCode, String endpoint, String responseBody) {
        this(statusCode, endpoint, responseBody, null);
    }

    /** HTTP status code, or {@code 0} for transport/timeout failures. */
    public int statusCode() {
        return statusCode;
    }

    public String endpoint() {
        return endpoint;
    }

    public String responseBody() {
        return responseBody;
    }
}
