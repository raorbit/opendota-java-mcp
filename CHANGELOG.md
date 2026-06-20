# Changelog

All notable changes to **opendota-mcp** are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); the project aims to follow semantic versioning.

## [Unreleased]

A large read-only tool expansion (18 → ~43 tools), plus one opt-in write surface. The version is
still `1.0.0`; bump it (and move this section under the new version heading) when cutting the next
release — **note the breaking input-schema changes below**, as they require a minor/major bump.

### Added

- **SQL analytics** — `run_sql_explorer` (a guarded read-only SQL query over OpenDota's `/explorer`,
  with SELECT/WITH-only + single-statement + keyword-blocklist guardrails and an enforced row cap)
  and `get_sql_schema`.
- **Hero deep-dives** — `get_hero_matchups`, `get_hero_item_popularity`, `get_hero_durations`,
  `get_hero_players`, `get_hero_matches`.
- **Teams / leagues / pro** — `get_team`, `get_team_matches`, `get_league`, `get_league_matches`,
  `get_pro_players`.
- **Player sub-resources** — `get_player_ratings`, `get_player_rankings`, `get_player_counts`,
  `get_player_histograms`, `get_player_pros`, `get_player_wardmap`, `get_player_wordcloud`.
- **Misc / diagnostic** — `get_records`, `get_parsed_matches`, `get_metadata`, `get_health`.
- **Full filter set** on the player aggregation tools (`get_player_wl`, `get_player_peers`,
  `get_player_totals`, `get_player_heroes`), and the four previously-missing filters (`region`,
  `excluded_account_id`, `significant`, `having`) on `get_player_matches`.
- **Opt-in write tools** behind `opendota.write-tools-enabled=true` (off by default; the server is
  read-only otherwise) — `request_parse`, `get_parse_request`, `refresh_player`.

### Changed — breaking (tool input schemas)

- `get_hero_rankings` and `get_benchmarks`: `hero_id` is now an **integer** (was a string), aligning
  it with every other tool. A caller sending `hero_id` as a quoted JSON string will now fail schema
  validation.
- `get_player_matches`: the array filters `included_account_id`, `with_hero_id`, and
  `against_hero_id` are now **comma-separated strings** (were single integers) so multiple values can
  be passed. A single value (e.g. `"5"`) still works.

## [1.0.0] - 2026-06-20

Initial release: the OpenDota MCP **stdio** server (18 read-only tools over the OpenDota REST API),
plus the optional shared localhost **sidecar** (shared rate limiter + L1 cache + a durable L2 SQLite
cache, off unless `OPENDOTA_SIDECAR_L2=true`).
