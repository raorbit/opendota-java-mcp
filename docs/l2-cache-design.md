# L2 Durable Cache Design (Sidecar Phase 2)

Status: **SPEC — deferred / not yet implemented.** This document is the design contract a later
commit implements. It describes a durable SQLite L2 cache tier that lives **inside the sidecar
only**. Nothing here touches the root `opendota-mcp` jar.

## 1. Goal and scope

The sidecar already gives co-running MCP processes a *shared* in-memory L1 cache
(`TtlCache`), single-flight de-duplication, and one rate limiter, all owned by a single
`OpenDotaClient` (see `SidecarHttpServer.handleApi -> client.getJson(path)`). L1 is volatile:
it is lost on every sidecar restart, and even while running it expires aggressively (30–60s
for most paths; see `OpenDotaClient.ttlFor`).

L2 adds a **durable, disk-backed** tier for the subset of OpenDota responses that are either
truly immutable (a fully-parsed match never changes) or only change on a game patch (the
static reference data: heroes, constants, hero stats). For those, L2 lets the sidecar serve a
hit *across restarts* and *long after* the L1 TTL has elapsed, eliminating the most wasteful
re-fetches against OpenDota's rate budget.

L2 is explicitly **not** a general write-through cache for volatile data (player profiles,
pro/public match lists, live games, searches). Those keep their existing L1-only behaviour.

### Non-goals
- No change to L1 (`TtlCache`), single-flight, or the rate limiter — they stay inside
  `OpenDotaClient`, unchanged.
- No DTO mapping. Responses remain raw JSON strings end to end.
- No second writer. The sidecar is one process per machine; SQLite is single-writer here.
- No Flyway / Liquibase / migration framework (see §6.3).

## 2. The drift constraint (why L2 wraps the client instead of living inside it)

`OpenDotaClient`, `TtlCache`, `RateLimiter`, and `OpenDotaException` are **byte-for-byte copies**
of the root `opendota-mcp` sources, enforced by `ClientCopyDriftTest` (string-equality over the
four files). This is a hard, load-bearing constraint:

> If L2 logic were added inside `OpenDotaClient.getJson` or `ttlFor`, the identical edit would
> have to be made in the **root** copy to keep the drift test green. That would drag the *concept*
> of an L2/SQLite tier into the MCP server jar — and risk the root needing `sqlite-jdbc` — which
> the locked-stack rules forbid (`sqlite-jdbc` stays in the sidecar pom only; no fat-jar; root pom
> untouched).

**Resolution: L2 is a decorator that wraps the client; it never lives inside it.**

- The four copied client classes stay **byte-identical**. `ClientCopyDriftTest` keeps passing.
- L1 `TtlCache`, single-flight, and the `RateLimiter` continue to run *inside*
  `client.getJson(...)`, exactly as today.
- L2 is consulted *around* `client.getJson(...)`, in a new sidecar-only class.
- `OpenDotaClient.ttlFor` remains the **L1** TTL source of truth and is **not** touched or
  generalized. L2's `classify(path)` is a **separate, new** sidecar class — not a refactor of
  `ttlFor`.

```
inbound GET /api/<path>?q
        |
SidecarHttpServer.handleApi
        |
        v
  CachingGateway.get(path)            <-- NEW sidecar-only L2 decorator
        |
        |  classify(path) -> PERMANENT | TTL | NO_STORE
        |
        |  PERMANENT/TTL: L2 lookup (SQLite)
        |     hit  -> return stored body  (counts l2Hit)
        |     miss -> delegate ↓, then maybe store
        |  NO_STORE: delegate ↓, never store
        v
  client.getJson(path)                <-- UNCHANGED: L1 TtlCache + single-flight + RateLimiter
        |
        v
  OpenDota (HTTP)
```

The gateway calls the *same* `client.getJson(path)` the sidecar calls today, so single-flight is
preserved automatically: an L2 miss falls through to the one client whose `inFlight` map collapses
concurrent identical misses into a single upstream call (see §7.2 for the ordering subtlety).

## 3. Component: `CachingGateway`

New class, e.g. `com.raorbit.opendota.sidecar.L2CachingGateway` (sidecar package, sibling to
`SidecarHttpServer`). Responsibilities:

1. Own the `L2Store` (SQLite-backed; §6) and the `classify` decision (§4).
2. On each request: classify the path, consult L2 for PERMANENT/TTL classes, delegate misses to
   `client.getJson`, then conditionally store the result (§5, §7).
3. Expose hit/miss/store counters for a future `/stats` endpoint (§8).
4. Run patch-bust invalidation when the configured/observed patch id changes (§5.2).

Proposed surface (illustrative, not frozen):

```java
final class L2CachingGateway {
    L2CachingGateway(OpenDotaClient client, L2Store store, L2Config config) { ... }

    /** Returns the raw JSON body, serving from L2 when possible, else delegating to the client. */
    String get(String path) throws OpenDotaException;

    L2Stats stats();           // snapshot of counters for /stats
    void close();              // closes the SQLite connection
}
```

`SidecarHttpServer` is given the gateway instead of the bare client, and `handleApi` calls
`gateway.get(openDotaPath)` where it currently calls `client.getJson(openDotaPath)`. The gateway
holds the client reference and is responsible for closing both on shutdown (mirroring today's
`server.close()` -> `client.close()` chain).

### Feature flag
L2 is **off by default** in this first cut. A single env/system-property flag
(`OPENDOTA_SIDECAR_L2=true` / `-Dopendota.sidecar.l2=true`) selects whether `SidecarMain` wraps
the client in `L2CachingGateway` or passes the bare client straight through. When off, the
sidecar behaves byte-for-byte as it does today and never opens a SQLite file. This keeps the risky
new tier opt-in until it is operationally trusted.

## 4. `classify(path)` — the L2 decision table

`classify(String path) -> {PERMANENT, TTL, NO_STORE}`. Strip the query string first (mirror
`ttlFor`: cut at the first `?`), then match by prefix, **most specific first**. The path is the
OpenDota path the sidecar already forwards (e.g. `/matches/123`, `/players/456/recentMatches`).

| Prefix (after stripping `?…`)                          | Classification | Rationale |
|--------------------------------------------------------|----------------|-----------|
| `/matches/{id}` (i.e. `/matches/` + digits, no further `/`) | **PERMANENT (parse-gated)** | A finished, fully-parsed match is immutable. Store permanently **only if parsed** (§5.1). An unparsed match is treated as **TTL** until it parses. |
| `/heroes`                                              | **PERMANENT (patch-scoped)** | Static hero list; changes only on a patch. Invalidated by patch bump (§5.2). |
| `/heroStats`                                           | **PERMANENT (patch-scoped)** | Hero aggregate reference data; patch-scoped. |
| `/constants/`                                          | **PERMANENT (patch-scoped)** | Game constants (items, abilities, patch, etc.); patch-scoped. |
| `/players/`                                            | **TTL** | Player profiles / recent matches change as the player plays. Mirror L1 horizon. |
| `/proMatches`                                          | **TTL** | A rolling recent-match feed. |
| `/publicMatches`                                       | **TTL** | A rolling recent-match feed. |
| `/rankings`                                            | **TTL** | Aggregate, changes continuously. |
| `/search`                                              | **TTL** | Search results drift. |
| `/live`                                                | **NO_STORE** | Live game state — never durable. |
| *anything else (default)*                              | **NO_STORE** | Conservative default: an unrecognised path is never persisted. |

Notes:
- **PERMANENT** = eligible for durable storage with **no TTL expiry** (subject only to the
  patch-bust trigger for static data and the overall cap in §6.4). A PERMANENT match row never
  expires by time.
- **TTL** = L2 *may* store the body with the same TTL `ttlFor` would assign, giving durability
  across restart within that short window. This is a modest win; an implementer may legitimately
  treat TTL as "L2 does not store, fall through to L1 only" in the first cut to keep L2 strictly
  about the high-value immutable data. **Decision for the first implementation: TTL paths are
  NOT stored in L2** (they fall through to the existing L1-only path). The TTL column still exists
  in the table so the policy can be widened later without a schema change. The table above
  documents intent; only PERMANENT actually writes L2 rows in v1.
- **NO_STORE** = never read from or written to L2; pure passthrough to `client.getJson`.
- The match-id discrimination (`/matches/{id}` vs hypothetical future `/matches/...` subpaths)
  must be precise: classify PERMANENT only when the segment after `/matches/` is a non-empty run
  of digits and there is no further `/`. Any other `/matches/...` shape falls to the default
  (NO_STORE), so a future endpoint can't be silently mis-stored.

## 5. Parse-gating and invalidation

### 5.1 Parse-gating for `/matches/{id}` (the false-permanent risk)

OpenDota returns a match object whether or not the replay has been parsed. An **unparsed** match
is missing the rich parsed fields and will be *backfilled later* once OpenDota parses the replay.
Storing an unparsed match as PERMANENT would pin the stale, half-null version **forever** — the
single most dangerous failure mode of this cache.

**Rule: a `/matches/{id}` body is stored PERMANENT only when it is detected as FULLY PARSED.
Otherwise it is treated as TTL (i.e. in v1, not stored in L2 at all) and re-fetched next time.**

**Detection signal (cheap, no DTO mapping).** The sidecar is dependency-free except (now)
`sqlite-jdbc`; it has no Jackson on the classpath and must not gain one. Use a **minimal, robust
JSON probe** on the raw string. OpenDota's parsed-match indicator is the top-level `version`
field: it is `null` (or absent) for an unparsed match and a non-null integer once parsed. The
detection is therefore: *the response contains a top-level `"version"` key whose value is a
number (not `null`)*.

Recommended implementation — a tiny hand-rolled check, in order of preference:

1. **Primary signal — `version` is a non-null number.** Scan for the key token `"version"`,
   then check the next non-whitespace value token is a digit/number rather than `null`. A
   regex such as `"version"\s*:\s*\d` (anchored to a JSON value position) is acceptable given
   the dep-free constraint. This is the same signal OpenDota's own UI uses to decide "parsed".
2. **Corroborating signal (defence in depth, optional).** Presence of a non-null `od_data`
   object, or a non-empty `players[].purchase_log`, or non-null `objectives`. These only appear
   on parsed matches. Use one of them as a secondary guard so a future API quirk where `version`
   is populated but the parse payload is absent doesn't cause a false-permanent store.

**Risk of a false-permanent store, and how the signal bounds it:**
- *False negative* (parsed match read as unparsed): harmless — we simply don't durably store it
  this time and re-fetch later. Costs one extra upstream call.
- *False positive* (unparsed match stored as PERMANENT): the harmful case — pins a stale body
  forever. The `version`-is-a-number check is conservative precisely because OpenDota populates
  `version` *only* after parsing; the optional corroborating field makes a false positive require
  *two* independent fields to be wrongly present. Combined with the schema_version rebuild escape
  hatch (§6.3) and the absence of any unbounded harm (a stale match is still a valid past match,
  just less detailed), the residual risk is acceptable. **Do not** attempt a full structural
  validation; the cheap probe plus the rebuild escape hatch is the right cost/robustness balance
  for a dep-free sidecar.

A substring probe must tolerate JSON with arbitrary whitespace and key ordering; it must **not**
assume `version` is the first key. The probe operates on the already-size-capped body
(`CappedBodySubscriber` bounds it to `maxResponseBytes`), so it cannot be driven to pathological
cost by a huge body.

### 5.2 Patch-busting for static data (`/heroes`, `/heroStats`, `/constants/*`)

Matches are immutable once parsed, so PERMANENT match rows **never** need busting. The static
reference data, however, changes when Dota ships a game patch. L2 must drop stale static rows on
a patch change.

**Trigger.** Store the current patch identity as a single bookkeeping row and compare on a
configurable cadence:
- Source of truth for "current patch": the latest entry from `/constants/patch` (OpenDota exposes
  the patch list there), or a `OPENDOTA_SIDECAR_PATCH_ID` override the operator can set when they
  know a patch dropped. The gateway records the observed patch id in a `meta` row
  (`meta(key='patch_id', value=<id>)`).
- **When to check:** lazily, on the first request that classifies as PERMANENT *patch-scoped*
  after process start, and at most once per a configurable interval (default 6h — the same
  horizon `ttlFor` uses for `/heroes` and `/constants/*`, so L2 never serves staler static data
  than L1 would have). The check itself fetches `/constants/patch` through the *normal* client
  path (so it is L1-cached and rate-limited like any other call).

**What a patch bump evicts.** When the observed patch id differs from the stored `patch_id`:
1. Delete every L2 row whose `classification = PERMANENT` **and** `patch_id IS NOT NULL`
   (i.e. all patch-scoped static rows). Match rows have `patch_id = NULL` and are **not** evicted
   — they remain immutable across patches.
2. Update the `meta` `patch_id` row to the new value.

Patch-scoped static rows are written with their `patch_id` column set to the patch id observed at
store time, so a stale row left behind by a crashed eviction is also caught by a per-row
`patch_id != current` check on read (defence in depth: a read of a static row whose stored
`patch_id` ≠ current patch is treated as a miss and re-fetched).

## 6. SQLite storage

`sqlite-jdbc` is added to **`sidecar/pom.xml` only** (a runtime dependency; the only non-test
runtime dependency the sidecar gains). It must **never** appear in the root pom. No shade/fat-jar:
the dependency rides as an ordinary jar on the sidecar's classpath; the runnable jar stays a
plain `maven-jar-plugin` artifact (the launch command may need `-cp` rather than `-jar`, or the
manifest `Class-Path` set — an implementation detail for the build commit, not a fat-jar).

### 6.1 Database location
A single file, default `${user.home}/.opendota-sidecar/l2-cache.db`, overridable via
`OPENDOTA_SIDECAR_L2_DB`. The sidecar is one-per-machine, so one file with one writer.

### 6.2 Schema

```sql
-- One row per cached response.
CREATE TABLE IF NOT EXISTS cache_entries (
    path           TEXT    PRIMARY KEY,   -- the OpenDota path incl. query, == the L1 cache key
    body           TEXT    NOT NULL,      -- raw JSON response, verbatim (TEXT; bodies are UTF-8 JSON)
    classification TEXT    NOT NULL,      -- 'PERMANENT' | 'TTL'  (NO_STORE never reaches here)
    stored_at      INTEGER NOT NULL,      -- epoch millis, for LRU-ish eviction + TTL math
    expires_at     INTEGER,               -- epoch millis for TTL rows; NULL for PERMANENT
    schema_version INTEGER NOT NULL,      -- stamped per row; lets a mismatch trigger rebuild
    patch_id       TEXT                   -- set for patch-scoped static rows; NULL for matches
);

CREATE INDEX IF NOT EXISTS idx_cache_entries_stored_at ON cache_entries (stored_at);

-- Bookkeeping (patch id, etc.).
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT
);
```

- `path` is the same key L1 uses (path **before** any `api_key` is appended — the sidecar never
  forwards a key in the path anyway). Query string is part of the key.
- `body` stored as TEXT (JSON is UTF-8 text; storing BLOB buys nothing and complicates reads).
- `expires_at` is `NULL` for PERMANENT so a single read predicate
  (`expires_at IS NULL OR expires_at > now`) covers both classes; PERMANENT is thus exempt from
  TTL expiry by construction.

### 6.3 Pragmas and migration

On open:
```sql
PRAGMA journal_mode = WAL;       -- single writer + concurrent readers; durable across restart
PRAGMA busy_timeout = 5000;      -- 5s: tolerate brief lock contention from virtual-thread readers
PRAGMA synchronous = NORMAL;     -- WAL + NORMAL is the standard durable-but-fast pairing
PRAGMA foreign_keys = ON;        -- harmless; future-proofs if relations are added
```

`busy_timeout` matters because `SidecarHttpServer` dispatches each request on its own virtual
thread, so many readers can hit the database concurrently (see §7.1 for the concurrency model
that keeps writes single-threaded and reads parallel). The read-pool connections (§7.1) additionally
set `PRAGMA query_only = true` as a defensive guard so a read path can never write, and `busy_timeout`
to ride out brief WAL contention; WAL itself is a database-level setting established by the write
connection, so read connections inherit it without re-declaring it.

**Migration policy — hand-rolled drop+rebuild, NO Flyway.** A `SCHEMA_VERSION` integer constant
lives in the gateway. On open, read `meta.value WHERE key='schema_version'`:
- If absent (fresh db): create tables, write `schema_version`.
- If present and **equal** to `SCHEMA_VERSION`: use as-is.
- If present and **different**: `DROP TABLE cache_entries`, recreate it, and update the stored
  `schema_version`. The cache is a *pure performance optimization* over an idempotent upstream —
  dropping it loses nothing but a one-time re-fetch — so a destructive rebuild is the correct,
  simplest migration. (The `meta` patch row may be preserved or also reset; resetting it just
  forces one patch re-check.)

This is why every row also carries `schema_version`: a belt-and-braces guard so any row that
somehow survives a partial rebuild is still self-describing and can be treated as a miss if its
stamp is stale.

### 6.4 Eviction / cap

PERMANENT rows are exempt from *TTL* expiry but **not** from the overall size cap — an unbounded
match history would grow the file without limit.

- **Cap:** a configurable maximum row count (`OPENDOTA_SIDECAR_L2_MAX_ROWS`, default e.g. 50,000)
  and/or an approximate maximum total body bytes (default e.g. 512 MB). Both mirror `TtlCache`'s
  two-axis bounding philosophy (count + bytes), since a single parsed match body can be large.
- **Eviction trigger:** checked after each successful store (and optionally on open). When over a
  cap, delete rows **LRU-ish by `stored_at` ascending** (oldest first). PERMANENT rows are *not*
  exempt here — once the cap is hit, the oldest entries go regardless of class, because the
  alternative (an unbounded file) is worse and a re-fetch is always possible.
- Eviction is best-effort and need not be transactional with the store; a transiently over-cap
  file is acceptable (same soft-bound stance as `TtlCache`).

A single-statement bulk delete is preferred, e.g.
`DELETE FROM cache_entries WHERE path IN (SELECT path FROM cache_entries ORDER BY stored_at ASC LIMIT ?)`
with the limit computed from the overage.

## 7. Concurrency and integration ordering

### 7.1 Connection model — single writer, pooled readers
**Writes** use a **single JDBC connection** with all *write* operations (`put`, evict, patch-bust,
rebuild) serialized on the store's monitor; WAL + `busy_timeout` cover reader/writer overlap. Given
low write volume (only PERMANENT matches and occasional static data), one serialized write connection
is sufficient and avoids a write-side pool.

**Reads** use a **bounded pool of dedicated read-only connections** (`OPENDOTA_SIDECAR_L2_READ_POOL`,
default 4, clamped to `[1, 64]`). The hot `get()` path borrows a connection from the pool, runs its
single-row lookup, and returns it; since WAL allows many concurrent readers alongside the one writer,
up to *pool-size* L2-hit reads execute **in parallel** — not merely decoupled from writes, but parallel
with each other. Borrowing is best-effort (spec §7.2): if the pool is saturated past a short timeout
(near-impossible for microsecond point lookups), `get()` degrades to a cache miss and the gateway fetches
upstream rather than blocking the request.

> **Decision note.** An earlier revision of this section specified a single shared connection for both
> reads and writes ("measure before adding a pool"). We have since deliberately adopted the read pool:
> the sidecar fans every request onto its own virtual thread, so cache-hit reads are the dominant,
> highly-concurrent path, and serializing them behind one connection/lock left obvious read throughput
> on the table. The pool is hand-rolled over an `ArrayBlockingQueue` (no connection-pool dependency —
> the locked stack still permits only `sqlite-jdbc`).

**In-memory exception.** For a `:memory:` db there is no pool: a second `:memory:` connection is a
separate, empty database, so `get()` shares the single connection under the store monitor (reads then
serialize with writes — correct for one db, and in-memory is a test/degenerate mode, not the production
file-backed path).

### 7.2 Where the gateway sits relative to single-flight (the ordering subtlety)
The gateway wraps `client.getJson`, so the request order per call is:

1. `classify(path)`.
2. If NO_STORE (or TTL in v1): `return client.getJson(path)` directly — pure passthrough; L1 +
   single-flight + rate limiter all still apply inside the client.
3. If PERMANENT: **L2 read first.**
   - Hit (and, for static, `patch_id` current): return stored body. `l2Hit++`. No client call,
     no permit consumed, no upstream hit — the whole point.
   - Miss: call `client.getJson(path)` (this is where L1/single-flight/rate limiting happen),
     then parse-gate (§5.1) and, if eligible, store to L2. `l2Miss++`, and `l2Store++` if stored.

Single-flight is preserved because **all** L2 misses funnel into the *same* `client.getJson`,
whose `inFlight` map collapses concurrent identical misses into one upstream call. A subtle
consequence: N concurrent L2 misses for the same parsed match will all fall through, share one
upstream call via single-flight, and then each attempt to store the same body to L2. That is
benign — the store is an idempotent `INSERT OR REPLACE` on the `path` primary key. (An optional
optimization: have the gateway dedupe stores by path, but it is not required for correctness.)

L2 reads/stores happen on the **request's own virtual thread**, off the hot path of other
requests. A SQLite error during an L2 read or store must **never** fail the request: on any
`SQLException`, log at warning and fall back to / proceed with the upstream result (treat L2 as a
best-effort accelerator, never a dependency). This keeps a corrupt or locked db from taking the
sidecar down.

### 7.3 Store uses `INSERT OR REPLACE`
`INSERT OR REPLACE INTO cache_entries(path, body, classification, stored_at, expires_at, schema_version, patch_id) VALUES (...)`
so a re-store (e.g. an unparsed match that later parses, or a static refresh after a patch)
overwrites cleanly on the `path` primary key.

## 8. Counters for a future `/stats` endpoint

`L2CachingGateway` maintains in-memory counters (e.g. `AtomicLong`s), exposed via `stats()`:

- `l2Hit` — served from L2 without calling the client.
- `l2Miss` — classified PERMANENT but not in L2 (fell through to the client).
- `l2Store` — bodies written to L2 (post parse-gate).
- `l2StoreSkippedUnparsed` — `/matches/{id}` misses that were *not* stored because unparsed
  (the parse-gate's observability — a high value here means lots of in-flight unparsed matches).
- `l2PatchBust` — number of patch-bust evictions performed.
- `l2Error` — SQLite errors swallowed (read or write); a non-zero value flags a degraded db.
- `noStore` / `passthrough` — requests classified NO_STORE (optional, for completeness).

A future `GET /stats` handler in `SidecarHttpServer` serializes this snapshot to JSON (alongside,
later, L1/rate-limiter stats). This spec only requires the gateway to *maintain and expose* the
counters; wiring the endpoint can be a follow-up.

## 9. Constraints recap (must hold)

- `sqlite-jdbc` is declared in **`sidecar/pom.xml` only**, never the root pom.
- No shade / no fat-jar; `spring-boot-maven-plugin` (root) and `maven-jar-plugin` (sidecar) stay
  as-is.
- The four copied client classes (`OpenDotaClient`, `TtlCache`, `RateLimiter`, `OpenDotaException`)
  remain **byte-identical** to the root copies — `ClientCopyDriftTest` must stay green. **No L2
  code goes into any of them**, and `ttlFor` is not modified.
- No new reactor parent; the sidecar stays a standalone build.
- The MCP server keeps stdout clean (irrelevant to the sidecar process, but the root is untouched
  so it remains true).
- The sidecar stays dependency-free apart from `sqlite-jdbc` (no Jackson, no JSON lib — hence the
  hand-rolled parse probe in §5.1).

## 10. Gate tests to write (sidecar module)

These are the contract tests that must accompany the implementation. They run in the sidecar
build (`mvn -B -f sidecar/pom.xml clean verify`) and should use a temp-file or in-memory SQLite db.

1. **Unparsed match is NOT stored permanent.** Feed a `/matches/{id}` body with `version: null`
   (and no parse fields). Assert: classified PERMANENT, but after a miss+fetch the row is **not**
   in L2 (and `l2StoreSkippedUnparsed` incremented). A second request re-fetches.
2. **Parsed match IS stored permanent and survives a simulated restart.** Body with
   `version: 21` (and `od_data`/`objectives` present). Assert it is stored, then a new gateway
   instance over the *same db file* serves it from L2 without calling the client.
3. **PERMANENT survives TTL expiry / never expires by time.** A stored parsed match remains an
   L2 hit even after advancing well past any L1 TTL horizon (PERMANENT rows have
   `expires_at IS NULL`).
4. **Patch bump invalidates static caches but not matches.** Store `/heroes` + `/constants/items`
   under patch `A`, and a parsed match. Change observed patch to `B`, trigger the bust. Assert the
   two static rows are gone (re-fetched on next request) and the match row remains.
5. **Per-row stale `patch_id` is treated as a miss.** A static row whose stored `patch_id` ≠
   current patch is not served from L2 (defence-in-depth read guard), even before a bulk bust runs.
6. **`schema_version` mismatch rebuilds.** Open a db, insert a row, then reopen with a bumped
   `SCHEMA_VERSION`. Assert `cache_entries` was dropped+recreated and the old row is gone.
7. **NO_STORE is pure passthrough.** `/live` (and an unrecognised path) are never read from or
   written to L2; the client is always called and no row appears.
8. **classify() table.** Parameterised test over the §4 table: each listed prefix maps to its
   expected class, query strings are stripped, `/matches/{id}` requires digits-only id, and
   `/matches/<non-id>` falls to NO_STORE.
9. **Cap eviction.** With a tiny `maxRows` (e.g. 3), store more than the cap and assert the oldest
   `stored_at` rows are evicted and the count stays within the cap.
10. **SQLite error is non-fatal.** Inject a store/read failure (e.g. close the connection or point
    at an unwritable path) and assert `gateway.get` still returns the upstream body (L2 degrades to
    passthrough; `l2Error` incremented), never throwing.
11. **Single-flight preserved across L2 misses.** N concurrent requests for the same uncached
    parsed match result in exactly one `client.getJson` upstream call (assert via a counting fake
    client) and a single stored row.
12. **L2 disabled by default.** With the feature flag off, no SQLite file is created and behaviour
    matches the bare-client sidecar (a regression guard so the deferred tier stays inert until
    enabled).
