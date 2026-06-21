# Registering the OpenDota MCP server with a client

The OpenDota MCP server speaks the MCP **stdio** transport, so any MCP client
launches it as a subprocess via `java -jar` and talks to it over stdin/stdout.

Build the jar first (see the project [README](../README.md)):

```sh
mvn clean package
# -> target/opendota-mcp-1.1.0.jar
```

The OpenDota API key is **optional** — the server works fully keyless. If you
have a key (a UUID), provide it via the `OPENDOTA_API_KEY` environment variable
to raise the rate limit (300 requests/minute, no daily cap). Otherwise omit it
and the server runs at the keyless limit (60 requests/minute, 3,000/day). Never
commit the key.

## Claude Desktop

Claude Desktop reads a JSON config file at:

- **Windows:** `%APPDATA%\Claude\claude_desktop_config.json`
- **macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`

Add (or merge) an entry under `mcpServers`:

```json
{
  "mcpServers": {
    "opendota": {
      "command": "java",
      "args": [
        "-jar",
        "C:\\Users\\raorb\\Projects\\opendota-java-mcp\\target\\opendota-mcp-1.1.0.jar"
      ],
      "env": {
        "OPENDOTA_API_KEY": "<optional-uuid-or-omit>"
      }
    }
  }
}
```

Replace the jar path with the absolute path on your machine. If you do not have
an API key, either set `OPENDOTA_API_KEY` to an empty string or drop the `env`
block entirely. Restart Claude Desktop after editing the file.

## Project-scoped `.mcp.json`

For clients that support a project-scoped configuration (including Claude Code),
place a `.mcp.json` file at the project root. The structure is identical to the
`mcpServers` block above:

```json
{
  "mcpServers": {
    "opendota": {
      "command": "java",
      "args": [
        "-jar",
        "C:\\Users\\raorb\\Projects\\opendota-java-mcp\\target\\opendota-mcp-1.1.0.jar"
      ],
      "env": {
        "OPENDOTA_API_KEY": "<optional-uuid-or-omit>"
      }
    }
  }
}
```

A ready-to-edit template lives at [`.mcp.json.example`](../.mcp.json.example) in
the repository root — copy it to `.mcp.json` and fill in (or clear) the key.

`.mcp.json` (and `claude_desktop_config.json`) are git-ignored so a key pasted
into them is never accidentally committed. For the strongest hygiene, prefer
exporting `OPENDOTA_API_KEY` as a real environment variable in your shell or OS
keychain and leaving the value in the JSON empty, rather than inlining the secret
into a file at all.

## Shared sidecar for multiple agents

If you run several agents on one machine that share a single `OPENDOTA_API_KEY`,
their independent rate limiters can jointly exceed OpenDota's real per-key limit
(it is enforced per key, not per process). Run the **sidecar** so one local process
owns the single rate limiter and a shared cache, and have each server forward to it.

1. Build the sidecar (a separate, dependency-light project under `sidecar/`). It is its
   own Maven build — the root `mvn package` does **not** build or test it, so build and
   test it on its own:
   ```sh
   mvn -f sidecar/pom.xml clean package   # build + test
   # -> sidecar/target/opendota-sidecar-1.1.0.jar
   ```
2. Run it once per machine, giving it the key (the agents then do not need one):
   ```powershell
   # PowerShell
   $env:OPENDOTA_API_KEY = '<uuid>'; java -jar sidecar\target\opendota-sidecar-1.1.0.jar
   ```
   ```sh
   # bash
   OPENDOTA_API_KEY=<uuid> java -jar sidecar/target/opendota-sidecar-1.1.0.jar
   ```
   It binds `127.0.0.1:31337` (override with `OPENDOTA_SIDECAR_PORT` or
   `-Dopendota.sidecar.port=<port>`) and serves `GET /health` (liveness) plus `GET /stats`
   (cache hit/miss and rate-limiter counters). It is a plain HTTP process,
   not the stdio transport, so it logs to the console — redirect it to a file if you
   run it in the background.

   > **Security / trust:** the sidecar binds loopback only (never the network), but by default
   > it has **no authentication**, so any local process on the machine can use the API key it
   > holds — read-only OpenDota calls under your key and the shared rate budget. On a host where
   > not every local user is trusted, require a shared secret: start the sidecar with
   > `OPENDOTA_SIDECAR_TOKEN=<secret>` and set `opendota.sidecar-token=<secret>` on each agent.
   > `GET /api/*` then requires a matching `X-Sidecar-Token` header (constant-time compared);
   > `GET /health` stays open. If the sidecar starts without `OPENDOTA_API_KEY` it logs a warning
   > and runs **keyless**, which caps all agents at the 60/min keyless limit.

   An optional **durable L2 cache** (disk-backed SQLite, off by default) lets the sidecar serve
   immutable data — fully-parsed matches and per-patch static data — across restarts. Enable it with
   `OPENDOTA_SIDECAR_L2=true` (or `-Dopendota.sidecar.l2=true`); the SQLite driver ships in the
   sidecar's `lib/` directory next to its jar. Design and tuning knobs are in
   [`docs/l2-cache-design.md`](l2-cache-design.md).
3. Point each client's server at the sidecar by enabling forwarding and **omitting**
   the key from that client's config (the sidecar holds it):
   ```json
   {
     "mcpServers": {
       "opendota": {
         "command": "java",
         "args": [
           "-Dopendota.sidecar-enabled=true",
           "-jar",
           "C:\\Users\\raorb\\Projects\\opendota-java-mcp\\target\\opendota-mcp-1.1.0.jar"
         ]
       }
     }
   }
   ```
   (Or set `opendota.sidecar-enabled=true` via the environment or an external
   `application.properties`; JVM `-D` flags must come before `-jar`.) An agent that
   starts before the sidecar retries the connection briefly; if the sidecar is down or the
   port is wrong, tool calls return a clean error (after a short retry) rather than failing
   the session.

   To use a **non-default port**, set it on *both* sides: start the sidecar with
   `OPENDOTA_SIDECAR_PORT` (or `-Dopendota.sidecar.port=<port>`) and point each agent at the
   same port with `opendota.sidecar-port`. Note the agent's Spring property is dashed
   (`opendota.sidecar-port`) while the sidecar's own override is dotted
   (`opendota.sidecar.port`); the sidecar also accepts the dashed `-Dopendota.sidecar-port=<port>`
   so the two cannot be silently mismatched by separator.

> **Do not combine the write tools with the sidecar.** Setting
> `opendota.write-tools-enabled=true` together with `opendota.sidecar-enabled=true`
> **fails fast at startup** — the shared sidecar only proxies `GET`s. Run the write
> tools on a direct (non-forwarding) server instead.

## Running the server directly

You can also launch the jar yourself, for example to smoke-test it before
wiring up a client.

```powershell
# PowerShell (with a key)
$env:OPENDOTA_API_KEY = '00000000-0000-0000-0000-000000000000'; java -jar target\opendota-mcp-1.1.0.jar

# PowerShell (keyless)
java -jar target\opendota-mcp-1.1.0.jar
```

```sh
# bash (with a key)
OPENDOTA_API_KEY=00000000-0000-0000-0000-000000000000 java -jar target/opendota-mcp-1.1.0.jar

# bash (keyless)
java -jar target/opendota-mcp-1.1.0.jar
```

Because this is a stdio server, it will sit waiting for JSON-RPC input on stdin;
that is expected when launched outside an MCP client.

### Opt-in write tools

The server is read-only by default. Add `-Dopendota.write-tools-enabled=true`
(before `-jar`) to register three additional **write** tools — `request_parse`,
`get_parse_request`, `refresh_player` — which POST to OpenDota to queue a match
parse or refresh a player. They are absent from `tools/list` unless the flag is
set, and (as noted above) cannot be combined with sidecar forwarding.
