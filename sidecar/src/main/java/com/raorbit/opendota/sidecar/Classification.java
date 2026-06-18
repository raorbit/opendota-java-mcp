package com.raorbit.opendota.sidecar;

/**
 * The L2 durability class of an OpenDota path, decided by {@link L2CachingGateway#classify}.
 *
 * <p>See {@code docs/l2-cache-design.md} §4 for the full decision table. In v1 only
 * {@link #PERMANENT} actually writes L2 rows; {@link #TTL} falls through to the client's
 * L1-only path, and {@link #NO_STORE} is a pure passthrough that never touches L2.
 */
public enum Classification {
    /**
     * Eligible for durable storage with no TTL expiry. A parsed match is stored forever; static
     * reference data (heroes / heroStats / constants) is stored until the game patch changes.
     */
    PERMANENT,
    /**
     * Volatile data with a short L1 horizon. The L2 schema retains the {@code expires_at} column for
     * this class so the policy can widen later, but v1 does not store TTL rows — they pass through to
     * the client's existing L1-only path.
     */
    TTL,
    /** Never read from or written to L2 — pure passthrough to the client. */
    NO_STORE
}
