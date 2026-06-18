# Registering the OpenDota MCP server with a client

The OpenDota MCP server speaks the MCP **stdio** transport, so any MCP client
launches it as a subprocess via `java -jar` and talks to it over stdin/stdout.

Build the jar first (see the project [README](../README.md)):

```sh
mvn clean package
# -> target/opendota-mcp-1.0.0.jar
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
        "C:\\Users\\raorb\\Projects\\opendota-java-mcp\\target\\opendota-mcp-1.0.0.jar"
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
        "C:\\Users\\raorb\\Projects\\opendota-java-mcp\\target\\opendota-mcp-1.0.0.jar"
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

1. Build the sidecar (a separate, dependency-light project under `sidecar/`):
   ```sh
   mvn -f sidecar/pom.xml clean package
   # -> sidecar/target/opendota-sidecar-1.0.0.jar
   ```
2. Run it once per machine, giving it the key (the agents then do not need one):
   ```powershell
   # PowerShell
   $env:OPENDOTA_API_KEY = '<uuid>'; java -jar sidecar\target\opendota-sidecar-1.0.0.jar
   ```
   ```sh
   # bash
   OPENDOTA_API_KEY=<uuid> java -jar sidecar/target/opendota-sidecar-1.0.0.jar
   ```
   It binds `127.0.0.1:31337` (override with `OPENDOTA_SIDECAR_PORT` or
   `-Dopendota.sidecar.port=`) and serves `GET /health`. It is a plain HTTP process,
   not the stdio transport, so it logs to the console — redirect it to a file if you
   run it in the background.
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
           "C:\\Users\\raorb\\Projects\\opendota-java-mcp\\target\\opendota-mcp-1.0.0.jar"
         ]
       }
     }
   }
   ```
   (Or set `opendota.sidecar-enabled=true` via the environment or an external
   `application.properties`; JVM `-D` flags must come before `-jar`.) An agent that
   starts before the sidecar retries the connection briefly; if the sidecar is down,
   tool calls return a clean error rather than failing the session.

## Running the server directly

You can also launch the jar yourself, for example to smoke-test it before
wiring up a client.

```powershell
# PowerShell (with a key)
$env:OPENDOTA_API_KEY = '00000000-0000-0000-0000-000000000000'; java -jar target\opendota-mcp-1.0.0.jar

# PowerShell (keyless)
java -jar target\opendota-mcp-1.0.0.jar
```

```sh
# bash (with a key)
OPENDOTA_API_KEY=00000000-0000-0000-0000-000000000000 java -jar target/opendota-mcp-1.0.0.jar

# bash (keyless)
java -jar target/opendota-mcp-1.0.0.jar
```

Because this is a stdio server, it will sit waiting for JSON-RPC input on stdin;
that is expected when launched outside an MCP client.
