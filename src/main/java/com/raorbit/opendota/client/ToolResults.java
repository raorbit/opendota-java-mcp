package com.raorbit.opendota.client;

/**
 * Helpers for building the JSON error envelopes returned to MCP clients.
 *
 * <p>JSON is produced manually (no Jackson). String fields are escaped per the
 * JSON spec: backslash, double-quote, the named short escapes ({@code \n \r \t}),
 * and any other control character below {@code 0x20} as {@code \\u00XX}.
 */
public final class ToolResults {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ToolResults() {
    }

    /**
     * Build the JSON error envelope for a failed OpenDota interaction.
     *
     * <p>Shape: {@code {"error":..,"status":..,"endpoint":..,"upstream":..,"isError":true}}
     * where {@code status} is a JSON number and {@code upstream} is the response
     * body as a JSON string (or the JSON {@code null} literal when none).
     */
    public static String fromException(OpenDotaException e) {
        String upstream = e.responseBody();
        String upstreamJson = upstream == null ? "null" : "\"" + escape(upstream) + "\"";
        return "{\"error\":\"" + escape(e.getMessage())
                + "\",\"status\":" + e.statusCode()
                + ",\"endpoint\":\"" + escape(e.endpoint())
                + "\",\"upstream\":" + upstreamJson
                + ",\"isError\":true}";
    }

    /** Build the JSON error envelope for a client-side bad-argument error (status 400). */
    public static String badArg(String endpoint, String message) {
        return "{\"error\":\"" + escape(message)
                + "\",\"status\":400,\"endpoint\":\"" + escape(endpoint)
                + "\",\"isError\":true}";
    }

    /**
     * Build the JSON error envelope for an unexpected internal failure (status 500).
     * The exception itself is intentionally not surfaced, so an unexpected
     * unchecked error cannot leak internal details to the client.
     */
    public static String internalError(String endpoint) {
        return "{\"error\":\"internal error\",\"status\":500,\"endpoint\":\""
                + escape(endpoint) + "\",\"isError\":true}";
    }

    /**
     * Escape a string for inclusion inside a JSON string literal (without the
     * surrounding quotes). Handles {@code null} defensively by returning an
     * empty string.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u00")
                          .append(HEX[(c >> 4) & 0xF])
                          .append(HEX[c & 0xF]);
                    } else if (Character.isHighSurrogate(c)) {
                        // Keep a well-formed pair together; replace an unpaired high
                        // surrogate (e.g. from a snippet truncated mid-pair) with
                        // U+FFFD so the envelope is always valid Unicode.
                        if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                            sb.append(c).append(value.charAt(++i));
                        } else {
                            sb.append((char) 0xFFFD);
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        sb.append((char) 0xFFFD);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
