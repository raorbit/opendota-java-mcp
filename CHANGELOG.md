# Changelog

All notable changes to **opendota-mcp** are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); the project aims to follow semantic versioning.

## [1.3.0] - 2026-07-16

The opt-in **HTTP (remote-MCP) transport**, the hardening from three review rounds, a
protocol-level test safety net, and a single-sourced release version.

### Added

- **Opt-in HTTP (remote-MCP) transport.** `mvn -Phttp package` builds a separate
  `opendota-mcp-1.3.0-http.jar` that serves the same 47 read tools over **Streamable HTTP** at
  `/mcp`, so the server can be added through Claude's "Add custom connector → Remote MCP server
  URL" dialog instead of being spawned over stdio. Activated at runtime with
  `spring.profiles.active=http`; binds loopback (`127.0.0.1:8080`) and requires
  `Authorization: Bearer <token>` (`OPENDOTA_HTTP_BEARER_TOKEN`, constant-time compared) by
  default. `auth-mode=none` instances are protected by an anti-DNS-rebinding `Host` guard **and a
  browser cross-origin (CSRF) guard** (`Sec-Fetch-Site` / `Origin`), and a **fail-closed startup
  guard** refuses a non-loopback bind without a bearer token. TLS is terminated by a fronting
  proxy/tunnel, never in the JVM. The **default build is untouched** — plain `mvn package` stays
  pure stdio with no web server on the classpath, now enforced by a maven-enforcer ban. See
  `docs/remote-connector.md` and `docker-compose.http.yml`.
- **End-to-end MCP protocol smoke test.** A new test boots the real server as a child JVM on the
  actual stdio transport and drives `initialize → tools/list → tools/call` over real pipes,
  covering the MCP SDK wire path, the generated tool schemas, the json-schema-validator pin, and
  stdout cleanliness at the protocol level — none of which the wiring test exercised.
- `GET /stats` now reports **`l2OutageServe`**: requests served the retained watched-archive body
  because the forced re-fetch failed (previously such a request double-counted as both an
  `l2Miss` and an `l2Hit`, skewing the hit ratio during outages).

### Changed

- **The release version is single-sourced from the poms.** `spring.ai.mcp.server.version` is
  resource-filtered from the pom at build time; the client `User-Agent` derives from the jar
  manifest's `Implementation-Version` (with a dev-time fallback literal); and the release
  workflow now refuses a tag unless the poms, the filtered property, and both fallback literals
  all agree — the embedded version strings had silently drifted twice.
- The proactive watched auto-parse sweep is **capped at 10 parse requests per sweep** (each is a
  synchronous POST charged ~10× a normal call), so a fresh backlog drains across sweeps instead
  of bursting hundreds of charged calls that compete with agent reads for rate permits.

### Fixed

- **A read-only sidecar no longer issues writes on its own initiative.**
  `OPENDOTA_SIDECAR_ALLOW_WRITES=false` previously gated only inbound `POST`s: with watched
  players configured, the auto-parser still POSTed `/request/{id}` under the shared key (both
  from the background poll and the access-driven trigger). The lever now suppresses the
  auto-parser entirely; the archive stays read-only.
- **A parsed archived match of an un-watched player is reclaimed.** Un-watching a player only
  reclaimed their *unparsed* archived matches; a *parsed* `PINNED` row always hit L2, so it
  stayed pinned (and exempt from the main cap) forever. It is now re-classed `PERMANENT` on the
  next access and governed by the main cache cap like any other match.
- The sidecar warns when `OPENDOTA_SIDECAR_L2_WATCHED_REFETCH_MILLIS` is below the 60s L1 TTL of
  `/matches/{id}` — the forced re-fetch reads through the client's L1 cache, so a smaller
  interval is silently floored and cannot observe a parse any faster (mirrors the existing
  patch-check floor warning).
- Patch-scoped reads no longer serialize on the SQLite **write** connection: the not-due path of
  the patch check reads a cached patch id instead of the meta table on every request, so L2-hit
  reads stay parallel across the read pool.
- A failed read-pragma during store open no longer leaks its SQLite connection.
- L2 expiry reclamation (`evictExpired`) now uses a partial index on `expires_at` instead of a
  full-table scan on every store, and the L1 `TtlCache` no longer allocates a throwaway encoded
  copy of each body just to count its bytes.

### Security

- The **token-less sidecar** refuses browser cross-origin requests — a page could previously
  drive reads and parse/refresh `POST`s at `http://127.0.0.1:31337` under the sidecar's key via
  `fetch(..., {mode:'no-cors'})` — plus non-loopback `Host` headers (DNS rebinding) and literal or
  percent-encoded path traversal. The `auth-mode=none` HTTP instance enforces the same Host and
  cross-origin policy on its `/mcp` endpoint.
- A caller-supplied **`api_key` query parameter is stripped** before the sidecar forwards a
  request, so it can neither duplicate nor substitute the sidecar's own key on the shared hop.
- The whole HTTP exchange is now **time-bounded** (request timeout only covered time-to-headers),
  so an upstream that stalls mid-body can no longer wedge the single-flight path and every caller
  queued behind it.
- Bodies larger than the whole L2 byte budget are never stored (storing one would have evicted
  the entire tier, or the entire watched archive, before the oversized row itself went).

### Internal

- Review-round hardening (PRs #29-#31), condensed: rows stored under an outdated classification
  are treated as misses; patch-scoped stores are held off while L1 may still serve pre-bust
  bodies; watched re-fetches and patch checks back off during upstream outages; `/heroStats` is
  classified TTL rather than patch-pinned; `LIMIT ALL` / `FETCH FIRST n ROWS` are clamped in
  place instead of appending a second limiter; forwarding-client timeouts cover the sidecar's
  real service time; log purging skips live sibling processes; the wiring and client-copy guards
  are exhaustive; docker base images are pinned by digest; CI covers the `-Phttp` profile.
- New tests pin the `WITH`/CTE SQL guardrails (data-modifying CTEs are rejected), the
  `/explorer` non-JSON passthrough, the async parse-request path and its shutdown race, the
  outage re-serve with a 0 re-fetch interval, and the token gate on `/stats`.

## [1.2.0] - 2026-06-24

Container packaging for both the MCP server and the shared sidecar, published to GHCR; a configurable
sidecar bind host gated by a fail-closed token requirement; the watched-player match archive; and
sidecar write forwarding.

### Added

- **Watched-player match archive (sidecar L2).** The durable L2 cache can permanently archive every
  watched match it fetches — any `/matches/{id}` whose body mentions a configured player (access-driven,
  not a history backfill) — governed by its own retention budget separate from the ordinary cache cap. Set `OPENDOTA_SIDECAR_L2_WATCHED_PLAYERS` (comma-separated Steam32 `account_id`s)
  with optional `OPENDOTA_SIDECAR_L2_WATCHED_MAX_ROWS` / `…_WATCHED_MAX_BYTES` caps (default unlimited =
  never delete). Watched matches are stored as a new `PINNED` class — exempt from the main cap, saved
  immediately even when unparsed, then served from L2 and re-checked upstream for the parsed body at
  most once per hour (tunable via `OPENDOTA_SIDECAR_L2_WATCHED_REFETCH_MILLIS`), upgrading in place once
  it parses; a failed re-check serves the retained body rather than erroring. Un-watching a player
  reclaims their archived unparsed matches on next access. The sidecar also **auto-requests parses** of
  watched players' unparsed matches (on by default, `OPENDOTA_SIDECAR_L2_WATCHED_AUTO_PARSE`) — both
  access-driven and via a background poll (`…_WATCHED_PARSE_POLL_MILLIS`, default 1h) over each player's
  recent matches — so they actually become parsed (a `GET` alone never triggers a parse). New `/stats`
  counters: `l2WatchedStore`, `pinnedRows`, `pinnedBytes`, `parseRequested`, `parseErrors`.
- **Sidecar write forwarding.** The shared sidecar now forwards write requests (`POST /api/*` — parse
  requests / player refreshes) to OpenDota under its key, alongside the existing `GET` proxying. Writes
  bypass the cache and are rate-limited like any other call; the token gate (when set) applies to them
  too.

- **Docker images.** A multi-stage `Dockerfile` for the stdio MCP server and a `sidecar/Dockerfile`
  for the shared sidecar, plus a `docker-compose.yml` (durable L2 volume, host-loopback publish,
  token-gated) and a committed `.env.example`. The release workflow now builds and pushes both
  images multi-arch (`linux/amd64`, `linux/arm64`) to GHCR, tagged from the release version.
- **`OPENDOTA_SIDECAR_BIND`** (system property `opendota.sidecar.bind` / `opendota.sidecar-bind`)
  to set the sidecar's bind host; defaults to `127.0.0.1` (loopback-only, unchanged).

### Changed

- The opt-in write tools now work over sidecar forwarding: enabling `opendota.write-tools-enabled=true`
  together with `opendota.sidecar-enabled=true` is supported (previously it failed fast at startup, as
  the sidecar was GET-only).

### Security

- The sidecar now **refuses to start** when it binds a non-loopback address without a token, and
  `docker compose up` fails fast if `OPENDOTA_SIDECAR_TOKEN` is unset — closing the fail-open gap
  where a network-reachable sidecar would serve `/api` and `/stats` (and the API key it holds)
  unauthenticated.

### Fixed

- Corrected stale `opendota-sidecar-1.0.0.jar` references in the sidecar lifecycle scripts.

## [1.1.0] - 2026-06-20

A large read-only tool expansion (18 → ~49 tools), one opt-in write surface, and a round of
robustness/observability fixes. **Note the breaking input-schema changes below.**

### Added

- **SQL analytics** — `run_sql_explorer` (a guarded read-only SQL query over OpenDota's `/explorer`,
  with SELECT/WITH-only + single-statement + keyword-blocklist guardrails and an enforced row cap)
  and `get_sql_schema`.
- **Hero deep-dives** — `get_hero_matchups`, `get_hero_item_popularity`, `get_hero_durations`,
  `get_hero_players`, `get_hero_matches`.
- **Teams / leagues / pro** — `get_team`, `get_team_matches`, `get_team_players`, `get_team_heroes`,
  `get_teams`, `get_league`, `get_league_matches`, `get_league_teams`, `get_leagues`,
  `get_pro_players`, `get_top_players`.
- **Player sub-resources** — `get_player_ratings`, `get_player_rankings`, `get_player_counts`,
  `get_player_histograms`, `get_player_pros`, `get_player_wardmap`, `get_player_wordcloud`.
- **Misc / diagnostic** — `get_records`, `get_parsed_matches`, `get_metadata`, `get_health`.
- **Full filter set** on the player aggregation tools (`get_player_wl`, `get_player_peers`,
  `get_player_totals`, `get_player_heroes`), and the four previously-missing filters (`region`,
  `excluded_account_id`, `significant`, `having`) on `get_player_matches`.
- **Opt-in write tools** behind `opendota.write-tools-enabled=true` (off by default; the server is
  read-only otherwise) — `request_parse`, `get_parse_request`, `refresh_player`.
- The sidecar `/health` and `/stats` now report a `version` (wire-contract) field.

### Changed

- `run_sql_explorer` now returns a **slimmed** response — `{command, rowCount, fields, rows,
  sql_executed}` — instead of node-postgres's raw result object (which carried a ~600-token `_types`
  blob and other internals) on every call.
- **Breaking (tool input schemas):** `get_hero_rankings` and `get_benchmarks` now type `hero_id` as an
  **integer** (was a string); and `get_player_matches`'s array filters `included_account_id`,
  `with_hero_id`, `against_hero_id` are now **comma-separated strings** (were single integers) so
  multiple values can be passed. A single value (e.g. `"5"`) still works.

### Fixed

- Single-flight: a shared upstream call that fails with an `Error` no longer leaves concurrently
  waiting callers hung indefinitely.
- `run_sql_explorer` no longer reports a successful query as an error (a top-level `"err":null` is a
  success, not a failure).
- Unexpected internal tool errors are now logged (to the file log) instead of being silently swallowed.
- The durable L2 cache reclaims expired rows before cap eviction (so a dead row can't evict a live
  one); the client-side rate-limit wait is bounded per call rather than per retry attempt; orphaned
  per-PID log files are purged on startup.

### Internal

- Release artifacts are now derived from the git tag (the workflow no longer hardcodes the version);
  enabling write tools together with the sidecar now fails fast at startup (the sidecar is GET-only).

## [1.0.0] - 2026-06-20

Initial release: the OpenDota MCP **stdio** server (18 read-only tools over the OpenDota REST API),
plus the optional shared localhost **sidecar** (shared rate limiter + L1 cache + a durable L2 SQLite
cache, off unless `OPENDOTA_SIDECAR_L2=true`).
