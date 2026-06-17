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
`ToolCallbackProvider` bean (built with `MethodToolCallbackProvider`). All
responses are returned as raw JSON strings straight from OpenDota.

The build produces a single runnable Spring Boot jar
(`target/opendota-mcp-1.0.0.jar`) that MCP clients launch via `java -jar`.

## Tools

The server exposes 14 tools in three groups.

### Player

| Tool | Description |
| --- | --- |
| `search_players` | Search players by name, returning matching `account_id`s. |
| `get_player` | Get a player's profile, rank, and estimated MMR. |
| `get_player_wl` | Get a player's win/loss record. |
| `get_player_recent_matches` | Get a player's ~20 most recent matches (no filters). |
| `get_player_matches` | Get a player's match history with filters and paging. |
| `get_player_heroes` | Get a player's per-hero statistics. |

### Match

| Tool | Description |
| --- | --- |
| `get_match` | Get full detail for a match (advanced fields are null for unparsed matches). |
| `get_pro_matches` | Get recent professional matches (paginate via `less_than_match_id`). |
| `get_public_matches` | Get recent public matches with an optional rank window (10-15 Herald .. 80 Immortal). |
| `get_live_games` | Get games that are currently live. |

### Hero / static

| Tool | Description |
| --- | --- |
| `list_heroes` | List all heroes. |
| `get_hero_stats` | Get hero pick / win / ban rates by skill tier. |
| `get_hero_rankings` | Get the top-ranked players for a given `hero_id`. |
| `get_constants` | Get game constants by resource name. |

## Prerequisites

- **JDK 21** (the project targets Java 21).
- **Maven** (the Maven Wrapper is not used; a local `mvn` is required).

## Build

```sh
mvn clean package
```

This produces the runnable jar at `target/opendota-mcp-1.0.0.jar`.

## Run

```sh
java -jar target/opendota-mcp-1.0.0.jar
```

The server speaks the MCP **stdio** transport: it reads JSON-RPC requests on
stdin and writes responses on stdout. It is intended to be launched by an MCP
client, not run interactively.

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

Two client internals can be tuned via standard Spring properties (environment
variables, JVM `-D` flags, or an external `application.properties`):

| Property | Default | Meaning |
| --- | --- | --- |
| `opendota.cache-max-entries` | `4096` | Max cached responses retained before the nearest-to-expiry entry is evicted. |
| `opendota.rate-limit-budget` | `10s` | Max time a request waits for a rate-limit permit before returning a rate-limited error. |

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
- all logs are written to `./logs/opendota-mcp.log` instead.

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
