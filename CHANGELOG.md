# Changelog

All notable changes to **opendota-mcp** are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); the project aims to follow semantic versioning.

## [Unreleased]

## [1.2.0] - 2026-06-24

Container packaging for both the MCP server and the shared sidecar, published to GHCR, plus a
configurable sidecar bind host gated by a fail-closed token requirement.

### Added

- **Docker images.** A multi-stage `Dockerfile` for the stdio MCP server and a `sidecar/Dockerfile`
  for the shared sidecar, plus a `docker-compose.yml` (durable L2 volume, host-loopback publish,
  token-gated) and a committed `.env.example`. The release workflow now builds and pushes both
  images multi-arch (`linux/amd64`, `linux/arm64`) to GHCR, tagged from the release version.
- **`OPENDOTA_SIDECAR_BIND`** (system property `opendota.sidecar.bind` / `opendota.sidecar-bind`)
  to set the sidecar's bind host; defaults to `127.0.0.1` (loopback-only, unchanged).

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
