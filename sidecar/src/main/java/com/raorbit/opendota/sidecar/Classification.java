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
    /**
     * A watched-player match row — a store-time refinement of {@link #PERMANENT} for a
     * {@code /matches/{id}} body that mentions a configured watched {@code account_id}. Permanent
     * ({@code expires_at = NULL}, {@code patch_id = NULL}), but unlike PERMANENT it is <em>exempt
     * from the main row/byte caps</em> and governed instead by the separate watched budget
     * ({@link L2Config.Watched}); see {@code docs/l2-cache-design.md} §6.5. It is also stored even
     * when unparsed (save now, upgrade later — {@link L2CachingGateway#lookup} forces a re-fetch of an
     * unparsed PINNED row so it upgrades in place once OpenDota parses it). Written only by the
     * watched-player upgrade in {@link L2CachingGateway#maybeStore}.
     */
    PINNED,
    /** Never read from or written to L2 — pure passthrough to the client. */
    NO_STORE
}
