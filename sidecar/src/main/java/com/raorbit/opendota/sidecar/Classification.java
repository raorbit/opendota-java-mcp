package com.raorbit.opendota.sidecar;

/**
 * The L2 durability class of an OpenDota path, decided by {@link L2CachingGateway#classify}.
 *
 * <p>See {@code docs/l2-cache-design.md} §4 for the full decision table. Both {@link #PERMANENT}
 * and {@link #TTL} write L2 rows (TTL with an {@code expires_at}); {@link #NO_STORE} is a pure
 * passthrough that never touches L2.
 */
public enum Classification {
    /**
     * Eligible for durable storage with no TTL expiry. A parsed match is stored forever; static
     * reference data (heroes / heroStats / constants) is stored until the game patch changes.
     */
    PERMANENT,
    /**
     * Volatile data with a short horizon. Stored durably with {@code expires_at = stored_at +
     * ttlFor(path)} (the {@link com.raorbit.opendota.client.OpenDotaClient#ttlFor} horizon) and
     * {@code patch_id = NULL}, so it survives a sidecar restart within its TTL window; the read-side
     * {@code expires_at} predicate treats an elapsed row as a miss.
     */
    TTL,
    /** Never read from or written to L2 — pure passthrough to the client. */
    NO_STORE
}
