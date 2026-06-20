package com.raorbit.opendota.tools;

import com.raorbit.opendota.client.OpenDotaClient;

/**
 * Shared helpers for building an OpenDota query string: append a parameter only when its value is
 * non-null, using {@code '?'} for the first parameter on the path and {@code '&'} thereafter (tracked
 * via a one-element {@code boolean[]} sentinel so callers can chain calls against a shared
 * {@link StringBuilder}). Values are URL-encoded via {@link OpenDotaClient#encode(String)}.
 *
 * <p>The single home for the query-building logic the player, match and team/league tools share.
 */
final class Query {

    private Query() {
    }

    /** Append {@code name=value} when {@code value != null}, picking {@code '?'} / {@code '&'} via {@code started}. */
    static void append(StringBuilder sb, boolean[] started, String name, Object value) {
        if (value == null) {
            return;
        }
        sb.append(started[0] ? '&' : '?');
        started[0] = true;
        sb.append(name).append('=').append(OpenDotaClient.encode(String.valueOf(value)));
    }

    /**
     * Append a repeatable query parameter once per non-blank comma-separated token in {@code csv}
     * (e.g. {@code "kills,deaths"} → {@code project=kills&project=deaths}), the shape OpenDota expects
     * for array-valued query params. A {@code null} csv is skipped.
     */
    static void appendRepeated(StringBuilder sb, boolean[] started, String name, String csv) {
        if (csv == null) {
            return;
        }
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                append(sb, started, name, t);
            }
        }
    }
}
