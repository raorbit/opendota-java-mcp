# opendota-mcp

An [OpenDota](https://docs.opendota.com/) [Model Context Protocol (MCP)](https://modelcontextprotocol.io)
server implemented in Java with [Spring AI](https://docs.spring.io/spring-ai/reference/).
It exposes read-only OpenDota / Dota 2 data (players, matches, heroes, game
constants) to MCP clients such as Claude over the **stdio** transport.

## Overview

The server is a Spring Boot application that registers a set of MCP tools, each
backed by a `GET` request to the public OpenDota REST API
(`https://api.opendota.com/api`). Tool methods are plain Java methods annotated
with `org.springframework.ai.tool.annotation.@Tool` and exposed through a
`ToolCallbackProvider` bean (built with `MethodToolCallbackProvider`). Almost all
responses are returned as raw JSON strings straight from OpenDota — the one
exception is `run_sql_explorer`, which slims OpenDota's verbose `/explorer` result
down to `{command, rowCount, fields, rows, sql_executed}`.

The build produces a single runnable Spring Boot jar
(`target/opendota-mcp-1.1.0.jar`) that MCP clients launch via `java -jar`.

## Tools

The server exposes **50 tools**: 47 read-only `GET` wrappers (always on) plus 3
opt-in **write** tools (off unless `opendota.write-tools-enabled=true`).

### Player

| Tool | Description |
| --- | --- |
| `search_players` | Search players by name, returning matching `account_id`s. |
| `get_player` | A player's profile, rank, and estimated MMR. |
| `get_player_wl` | Win/loss record (accepts the full filter set). |
| `get_player_recent_matches` | The ~20 most recent matches (no filters). |
| `get_player_matches` | Match history with the full filter set and paging. |
| `get_player_heroes` | Per-hero stats — a row per hero (accepts the full filter set). |
| `get_player_peers` | Teammates played with most, with win rates. |
| `get_player_totals` | Career totals/averages (kills, GPM, XPM, …). |
| `get_player_ratings` | MMR rating history over time. |
| `get_player_rankings` | Per-hero ranking percentiles. |
| `get_player_counts` | Match counts grouped by category (mode, lane, region, …). |
| `get_player_histograms` | Distribution of a single match stat (by `field`). |
| `get_player_pros` | Pro players this account has played with/against. |
| `get_player_wardmap` | Ward-placement heatmap data. |
| `get_player_wordcloud` | Chat-word frequencies. |

The four aggregation tools (`get_player_wl`, `get_player_peers`,
`get_player_totals`, `get_player_heroes`) and `get_player_matches` share the full
OpenDota filter set (`win`, `hero_id`, `lane_role`, `patch`, `date`, `region`,
`significant`, `sort`, …). The array filters (`included_account_id`,
`excluded_account_id`, `with_hero_id`, `against_hero_id`) are **comma-separated
strings**, expanded into repeated query params.

### Match

| Tool | Description |
| --- | --- |
| `get_match` | Full detail for a match (advanced fields are null for unparsed matches). |
| `get_pro_matches` | Recent professional matches (paginate via `less_than_match_id`). |
| `get_public_matches` | Recent public matches with an optional rank window (10-15 Herald .. 80 Immortal). |
| `get_live_games` | Games that are currently live. |

### Hero

| Tool | Description |
| --- | --- |
| `list_heroes` | List all heroes. |
| `get_hero_stats` | Hero pick / win / ban rates by skill tier. |
| `get_hero_rankings` | Top-ranked players for a `hero_id` (integer). |
| `get_benchmarks` | Percentile benchmarks (GPM, XPM, …) for a `hero_id` (integer). |
| `get_hero_matchups` | How a hero fares vs every other hero — counter-pick data. |
| `get_hero_item_popularity` | Item builds by game phase, from pro games. |
| `get_hero_durations` | Win rate bucketed by match length. |
| `get_hero_players` | Players who have played the hero. |
| `get_hero_matches` | Recent pro matches featuring the hero. |
| `get_distributions` | MMR / rank / country distributions across the player base. |
| `get_constants` | Static game constants by resource name. |

### SQL analytics

| Tool | Description |
| --- | --- |
| `run_sql_explorer` | Run a guarded read-only SQL query against OpenDota's `/explorer` data warehouse, for analytical questions the fixed endpoints can't answer. `SELECT`/`WITH` only; a row `LIMIT` is enforced; the result is slimmed to `{command, rowCount, fields, rows, sql_executed}`. |
| `get_sql_schema` | List the tables/columns available to `run_sql_explorer`. |

### Teams & leagues

| Tool | Description |
| --- | --- |
| `get_teams` | Professional teams ranked by rating (paginated via `page`). |
| `get_team` | A team's profile (name, tag, rating, W/L) by `team_id`. |
| `get_team_matches` | Recent matches played by a `team_id`. |
| `get_team_players` | Players who have played for a `team_id`. |
| `get_team_heroes` | Heroes a `team_id` has drafted. |
| `get_leagues` | List of all leagues / tournaments. |
| `get_league` | A league's info by `league_id`. |
| `get_league_matches` | Matches played in a `league_id`. |
| `get_league_teams` | Teams that competed in a `league_id`. |

### Pro scene

| Tool | Description |
| --- | --- |
| `get_pro_players` | Known professional players — resolve a pro's name to an `account_id`. |
| `get_top_players` | Highest-rated players by estimated MMR (top of the ladder). |

### Misc / diagnostic

| Tool | Description |
| --- | --- |
| `get_records` | All-time record holders for a match field. |
| `get_parsed_matches` | The recent parsed-match feed (paginated). |
| `get_metadata` | Site-wide metadata (current patch, etc.). |
| `get_health` | OpenDota API health snapshot. |

**Write tools (opt-in):** three more tools — `request_parse`,
`get_parse_request`, `refresh_player` — are registered only when
`opendota.write-tools-enabled=true` (off by default; see
[Write tools (opt-in)](#write-tools-opt-in) under Configuration).

> **Breaking input-schema changes since the first release:** `get_hero_rankings`
> and `get_benchmarks` now take `hero_id` as an **integer** (was a string); and the
> `get_player_matches` array filters (`included_account_id`, `with_hero_id`,
> `against_hero_id`) are now **comma-separated strings** (were single integers — a
> single value still works).

## Prerequisites

- **JDK 21** (the project targets Java 21).
- **Maven** (the Maven Wrapper is not used; a local `mvn` is required).

## Build

```sh
mvn clean package
```

This produces the runnable jar at `target/opendota-mcp-1.1.0.jar`.

## Run

```sh
java -jar target/opendota-mcp-1.1.0.jar
```

The server speaks the MCP **stdio** transport: it reads JSON-RPC requests on
stdin and writes responses on stdout. It is intended to be launched by an MCP
client, not run interactively.

## Run with Docker

Each release publishes two images to GHCR — `ghcr.io/raorbit/opendota-mcp` and
`ghcr.io/raorbit/opendota-sidecar`, tagged with the release version — or build them
locally as shown below. Requires **Docker Engine ≥ 28.0.1** (for the host-loopback
port-publish guarantee the sidecar relies on).

### MCP server image

```sh
docker build -t opendota-mcp:1.1.0 .
# or: docker pull ghcr.io/raorbit/opendota-mcp:1.1.0
```

The server is a **stdio** process, so an MCP client launches it with `docker run -i --rm`
(interactive stdin; **never** `-t` — a TTY corrupts the JSON-RPC framing). Register it like
the `java -jar` form but with `"command": "docker"`. The bare `-e OPENDOTA_API_KEY` only
*forwards* the variable — its value must also be supplied in the `env` block, because MCP
clients do not pass the host environment through:

```json
{
  "mcpServers": {
    "opendota": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "--init", "-e", "OPENDOTA_API_KEY", "ghcr.io/raorbit/opendota-mcp:1.1.0"],
      "env": { "OPENDOTA_API_KEY": "<optional-uuid-or-omit>" }
    }
  }
}
```

For keyless operation, drop both the `-e OPENDOTA_API_KEY` arg and the `env` entry. **Build
or pull the image before first use** — otherwise the client's first launch blocks on a
multi-hundred-megabyte image pull and may trip its startup timeout. For interactive local use
the plain jar (`java -jar`) starts faster; Docker is for distribution / running without a host
JDK.

### Sidecar (shared, durable) via Compose

The shared sidecar (see [Configuration](#configuration)) runs as a long-lived service:

```sh
cp .env.example .env        # fill in OPENDOTA_API_KEY and a strong OPENDOTA_SIDECAR_TOKEN
docker compose up -d        # builds the image, starts the sidecar on 127.0.0.1:31337
```

The compose service binds `0.0.0.0` *inside* the container but publishes only to **host
loopback** (`127.0.0.1:31337`); enables the durable **L2 SQLite cache** on a named volume
(`l2data:/data`) that survives restarts; and **requires** `OPENDOTA_SIDECAR_TOKEN` — on a
shared docker network the token is the only thing protecting the API key from co-located
containers. This is enforced, not just advised: `docker compose up` refuses to start without
a token, and the sidecar itself refuses to start on a non-loopback bind unless one is set
(confirm `auth=true` in `docker compose logs sidecar`).

Point agents at it in one of two ways:

1. **Host jar → sidecar** (simplest): run the MCP server as a jar with
   `OPENDOTA_SIDECAR_ENABLED=true`; it reaches the published `127.0.0.1:31337`.
2. **Containerized server → sidecar**: run the server image on the compose network and set
   `OPENDOTA_SIDECAR_HOST=sidecar` (plus the matching token):
   ```sh
   docker run -i --rm --init \
     --network opendota-java-mcp_opendota \
     -e OPENDOTA_SIDECAR_ENABLED=true -e OPENDOTA_SIDECAR_HOST=sidecar \
     -e OPENDOTA_SIDECAR_TOKEN -e OPENDOTA_API_KEY \
     ghcr.io/raorbit/opendota-mcp:1.1.0
   ```
   (Find the real network name with `docker network ls`; Compose names it
   `<project>_opendota`.) The main server is intentionally **not** a Compose service — it is a
   stdio process a client must spawn, so a long-lived service would have nothing driving its
   stdio.

## Develop

```sh
mvn spring-boot:run
```

## Test

```sh
# run the full test suite
mvn test

# run a single test class
mvn -Dtest=OpenDotaClientTest test

# run a single test method
mvn -Dtest=OpenDotaClientTest#methodName test
```

## Configuration

The OpenDota API is read-only and free, and the server works with no
configuration at all (keyless). An optional API key raises the rate limit:

| Mode | Rate limit |
| --- | --- |
| Keyless (default) | 60 requests/minute, 3,000 requests/day |
| Keyed (`OPENDOTA_API_KEY` set) | 300 requests/minute, no daily cap |

To use a key, set the `OPENDOTA_API_KEY` environment variable to your OpenDota
API key (a UUID):

```sh
# PowerShell
$env:OPENDOTA_API_KEY = '00000000-0000-0000-0000-000000000000'

# bash
export OPENDOTA_API_KEY=00000000-0000-0000-0000-000000000000
```

> **Treat the API key as a secret.** Supply it via the environment variable
> only. Never commit it to source control or paste it into a config file that is
> checked in.

### Client tuning (optional)

Client internals can be tuned via standard Spring properties (environment
variables, JVM `-D` flags, or an external `application.properties`):

| Property | Default | Meaning |
| --- | --- | --- |
| `opendota.cache-max-entries` | `4096` | Max cached responses retained before the nearest-to-expiry entry is evicted. |
| `opendota.rate-limit-budget` | `10s` | Max time a request waits for a rate-limit permit before returning a rate-limited error. |
| `opendota.rate-limit-permits-per-minute` | `0` | Outbound permits/minute (`0` = tier default: 300 keyed / 60 keyless). When several server processes share one API key, set this to `tier_budget / process_count` so their combined rate stays within OpenDota's real per-key ceiling. |
| `opendota.log-retention-days` | `7` | Days of per-process `opendota-mcp-<pid>.log` files to keep; older orphaned files are purged on startup (the current process's file is always kept). |
| `OPENDOTA_LOG_DIR` (env var) | `~/.opendota-mcp/logs` | Directory for the per-process log files. Absolute by default (so logs never land in the client's working directory); override to relocate them. |
| `opendota.sidecar-enabled` | `false` | Forward every OpenDota call to a shared local sidecar instead of calling OpenDota directly — see [Running several agents](#running-several-agents-shared-sidecar). |
| `opendota.sidecar-host` / `opendota.sidecar-port` | `127.0.0.1` / `31337` | Address of the shared sidecar (used only when `sidecar-enabled` is `true`). |
| `opendota.write-tools-enabled` | `false` | Register the opt-in write tools — see [Write tools](#write-tools-opt-in). Off by default; the server is read-only unless you set this. |

### Write tools (opt-in)

The server is **read-only by default**. Setting `opendota.write-tools-enabled=true` additionally
registers three tools that POST to OpenDota to queue work:

| Tool | Endpoint | Effect |
| --- | --- | --- |
| `request_parse` | `POST /request/{match_id}` | Queue a full replay parse of a match so its advanced fields become available. Returns a job id. Costs ~10× a normal call. |
| `get_parse_request` | `GET /request/{job_id}` | Poll the status of a parse job. |
| `refresh_player` | `POST /players/{account_id}/refresh` | Refresh a player's recent match history from Steam. |

When the flag is unset these tools are not created and never appear in `tools/list`. Writes are
rate-limited but never cached. (The shared sidecar only proxies `GET`s, so enable write tools on a
**direct** server rather than a forwarding one.)

### Running several agents (shared sidecar)

Each MCP client launches its own `java -jar` subprocess, so running *N* agents on
one machine means *N* independent servers — each with its own rate limiter. Since
OpenDota enforces its limit per **API key**, *N* servers sharing one key can jointly
overshoot the real ceiling and start getting `429`s. The **sidecar** fixes this: one
small local process owns the single rate limiter and the shared cache, and every
agent forwards its calls to it, so the real budget is honoured exactly once.

```sh
# build it (a separate, dependency-light project under sidecar/)
mvn -f sidecar/pom.xml clean package    # -> sidecar/target/opendota-sidecar-1.1.0.jar

# run it once per machine (it holds the key; the agents then don't need one)
OPENDOTA_API_KEY=<uuid> java -jar sidecar/target/opendota-sidecar-1.1.0.jar
```

Then point each agent at it by setting `opendota.sidecar-enabled=true` (and leaving
`OPENDOTA_API_KEY` out of the per-client config). The sidecar binds `127.0.0.1` only,
exposes `GET /health`, and defaults to port `31337` (override with
`OPENDOTA_SIDECAR_PORT` or `-Dopendota.sidecar.port=<port>`, and point agents at the same
port with `opendota.sidecar-port`). If an agent starts before the sidecar, it retries the
connection briefly. The sidecar has no authentication, so only run it on a host where every
local user is trusted. See
[`docs/mcp-registration.md`](docs/mcp-registration.md#shared-sidecar-for-multiple-agents).

### Steam32 account IDs

OpenDota identifies players by their **Steam32** `account_id`, which is the
SteamID64 minus `76561197960265728`. If you only have a player's name, use
`search_players` to resolve it to one or more `account_id`s first.

## stdout safety (important)

Because the stdio transport carries JSON-RPC framing **on stdout**, anything
else written to stdout would corrupt the protocol stream and break the client
connection. The application is configured to keep stdout clean:

- the Spring Boot startup banner is disabled;
- the console log appender is turned off (`logging.threshold.console=OFF`), so no
  application logs are written to stdout;
- all logs are written to `~/.opendota-mcp/logs/opendota-mcp-<pid>.log` instead
  (an absolute, working-directory-independent path so logs never pollute the
  client's launch directory; per-process, so several co-running servers don't
  interleave into one file). Override the directory with `OPENDOTA_LOG_DIR`.

When modifying this project, **never** call `System.out.println` (or otherwise
write to stdout) from application code. Route diagnostics through the logger so
they land in the log file.

## MCP client registration

See [`docs/mcp-registration.md`](docs/mcp-registration.md) for step-by-step
instructions on registering the server with Claude Desktop and with a
project-scoped `.mcp.json`. A ready-to-edit template is provided in
[`.mcp.json.example`](.mcp.json.example).

## License

MIT
