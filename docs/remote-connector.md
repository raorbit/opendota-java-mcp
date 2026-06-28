# Remote MCP connector (HTTP transport)

This guide covers the **opt-in HTTP transport** that lets the OpenDota MCP server
be added through Claude's **"Add custom connector → Remote MCP server URL"** dialog,
instead of being launched locally over stdio.

> **The default build is unaffected.** Everything here is opt-in: it requires the
> `-Phttp` Maven build and the `http` Spring profile at runtime. Plain `mvn package`
> still produces the pure-stdio `target/opendota-mcp-1.2.0.jar` with no web server,
> and existing Claude Desktop / Claude Code stdio users are unchanged. See
> [`mcp-registration.md`](mcp-registration.md) for the stdio setup.

## How it fits together

```
Claude (Anthropic cloud)
        │  https://opendota.example.com/mcp   (public, CA-trusted TLS)
        ▼
  TLS proxy / tunnel  (Cloudflare Tunnel / Caddy / nginx)   ← terminates TLS,
        │  http://127.0.0.1:8080/mcp                          injects Bearer token
        ▼
  opendota-mcp (http profile)  ── opendota.sidecar-enabled=true ──▶  sidecar
        │  Streamable-HTTP MCP endpoint /mcp                          (API key,
        │  BearerAuthFilter + fail-closed bind guard                   rate limiter,
        ▼                                                              TtlCache, L2,
  47 @Tool methods (same ToolCallbackProvider as stdio)               watched archive)
```

- **Transport:** Spring AI's webmvc MCP server starter, configured for the modern
  single-endpoint **Streamable HTTP** transport at `/mcp`
  (`spring.ai.mcp.server.protocol=STREAMABLE`). This is what Claude's remote-connector
  dialog expects (legacy HTTP+SSE is deprecated).
- **Tools:** the existing single `ToolCallbackProvider` bean is reused unchanged — no
  `@Tool` changes. The same 47 read tools (plus the opt-in write tools) are exposed.
- **Upstream:** with `opendota.sidecar-enabled=true` (the http-profile default) the
  http instance forwards every OpenDota call to the shared **sidecar**, which holds
  the API key and owns the one rate limiter / cache / L2 / watched archive. Run the
  sidecar alongside it (see [`mcp-registration.md`](mcp-registration.md#shared-sidecar-for-multiple-agents)).
  If you run the http instance **without** a sidecar, set
  `opendota.sidecar-enabled=false` and give it `OPENDOTA_API_KEY` so it talks to
  OpenDota directly with its own per-process limiter.

## 1. Build the HTTP jar

The HTTP transport is built under the `http` Maven profile, which adds the Spring AI
webmvc starter (embedded Tomcat + the Streamable-HTTP transport) and attaches a
distinct `http`-classified jar:

```sh
mvn -Phttp clean package
# -> target/opendota-mcp-1.2.0-http.jar   (and the plain stdio jar alongside it)
```

The first `-Phttp` build downloads `spring-ai-starter-mcp-server-webmvc` and
`spring-boot-starter-web` from Maven Central, so it needs network access.

## 2. Run in http mode

Activate the `http` Spring profile (loads `application-http.properties`) and provide
the bearer token. By default it binds **loopback** `127.0.0.1:8080` and routes through
the sidecar:

```sh
# bash
SPRING_PROFILES_ACTIVE=http OPENDOTA_HTTP_BEARER_TOKEN=<secret> \
  java -jar target/opendota-mcp-1.2.0-http.jar
```

```powershell
# PowerShell
$env:SPRING_PROFILES_ACTIVE = 'http'
$env:OPENDOTA_HTTP_BEARER_TOKEN = '<secret>'
java -jar target\opendota-mcp-1.2.0-http.jar
```

The Streamable-HTTP MCP endpoint is then at `http://127.0.0.1:8080/mcp`.

### Configuration knobs

All are standard `opendota.*` / Spring properties; supply them via env
(`OPENDOTA_*`, `SERVER_*`), `-D` flags, or an external `application.properties`.

| Property | Env | Default (http) | Purpose |
| --- | --- | --- | --- |
| `spring.profiles.active` | `SPRING_PROFILES_ACTIVE` | — | Set to `http` to enable this mode. |
| `server.address` | `SERVER_ADDRESS` | `127.0.0.1` | Bind interface. Loopback is the safe default. |
| `server.port` | `SERVER_PORT` | `8080` | Embedded Tomcat port (there is no MCP-specific port). |
| `spring.ai.mcp.server.streamable-http.mcp-endpoint` | — | `/mcp` | The single MCP endpoint path. |
| `opendota.http.auth-mode` | `OPENDOTA_HTTP_AUTH_MODE` | `bearer` | `bearer` or `none`. |
| `opendota.http.bearer-token` | `OPENDOTA_HTTP_BEARER_TOKEN` | — | Expected bearer secret (constant-time compared). Treat as a secret; never commit. |
| `opendota.sidecar-enabled` | `OPENDOTA_SIDECAR_ENABLED` | `true` | Forward through the shared sidecar. |

### Authentication (bearer) and the fail-closed guard

- **Bearer.** When `opendota.http.auth-mode=bearer` (the default), every request to
  `/mcp` must carry `Authorization: Bearer <token>` matching `opendota.http.bearer-token`.
  The comparison is constant-time (`MessageDigest.isEqual`). A missing/wrong token
  gets `401` with `WWW-Authenticate: Bearer`. Liveness paths (`/health`,
  `/actuator/health`) are left **un-gated** so a proxy/tunnel can probe.
- **Fail-closed bind guard.** On startup, if the server binds a **non-loopback**
  address (e.g. `server.address=0.0.0.0`, or unset → all interfaces) **without** a
  bearer token, it logs a SEVERE message and **refuses to start** (`System.exit(1)`)
  before the port goes live — mirroring the sidecar's guard. This prevents an open,
  unauthenticated `/mcp` surface. To bind a non-loopback address you **must** set
  `opendota.http.auth-mode=bearer` and `OPENDOTA_HTTP_BEARER_TOKEN`.

  The recommended shape is to **bind loopback** (`127.0.0.1`) and let the TLS proxy
  forward to it, rather than binding a public interface directly.

## 3. Put a TLS proxy / tunnel in front

Claude connects from **Anthropic's cloud**, not from your localhost, so the endpoint
must be a **public `https://` URL with a real CA-trusted certificate** — a bare local
HTTP port or a self-signed cert will not work. **TLS is terminated by an external
proxy/tunnel; it is never implemented in the JVM.**

### Cloudflare Tunnel (no inbound port, no public IP needed)

```sh
# After `cloudflared tunnel login` and creating a tunnel:
cloudflared tunnel --url http://127.0.0.1:8080
# or, with a named tunnel + DNS route, expose https://opendota.example.com -> 127.0.0.1:8080
```

Cloudflare presents the public `https://…` hostname and forwards to the loopback app.

### Caddy (automatic Let's Encrypt TLS)

```caddyfile
opendota.example.com {
    reverse_proxy 127.0.0.1:8080 {
        # Inject the app-level bearer so the JVM filter passes (defense in depth).
        header_up Authorization "Bearer <secret>"
    }
}
```

Caddy obtains and renews the certificate automatically and reverse-proxies `/mcp`
(and `/health`) to the loopback app.

> **A note on the in-app OAuth flow.** Claude's consumer "Add custom connector"
> dialog drives an **OAuth** authorize/redirect; it does **not** let the user type a
> static bearer token. So the bearer `Filter` here is the auth for the Messages-API /
> programmatic path and a **defense-in-depth** check behind the proxy. The clean,
> low-effort setup is to terminate TLS and do the OAuth at the fronting proxy/tunnel
> (Cloudflare Access / Caddy), and have the proxy **inject** the
> `Authorization: Bearer <token>` header upstream; the Java filter then enforces it.
> Full OAuth 2.1 / dynamic client registration in the JVM is out of scope.

## 4. Add it in Claude

In Claude, open **Settings → Connectors → Add custom connector**, choose
**Remote MCP server URL**, select **Streamable HTTP**, and paste your public
endpoint, e.g.:

```
https://opendota.example.com/mcp
```

Claude completes the connector handshake against the Streamable-HTTP endpoint and
the OpenDota tools become available.

## 5. (Optional) Docker

A documented, **off-by-default** compose service for the http instance is provided in
[`docker-compose.http.yml`](../docker-compose.http.yml). It is a separate compose
file so it never disturbs the existing sidecar service. See that file's header for
usage; it depends on the sidecar from the base `docker-compose.yml` and is intended
to sit behind your own TLS proxy/tunnel.

## Troubleshooting

- **Refuses to start, "bind … is not loopback but auth is not bearer-with-token":**
  you bound a non-loopback address without a token. Set
  `OPENDOTA_HTTP_BEARER_TOKEN` (and `opendota.http.auth-mode=bearer`), or bind
  `server.address=127.0.0.1`.
- **`401` on every call:** the `Authorization: Bearer <token>` header is missing or
  does not match `opendota.http.bearer-token`. If a proxy injects it, confirm the
  proxy and the app use the same secret.
- **Connector handshake fails / wrong endpoint:** ensure
  `spring.ai.mcp.server.protocol=STREAMABLE` (the library default is the deprecated
  SSE transport) and that you selected **Streamable HTTP** in the dialog.
- **Tools error with rate-limit or "sidecar" messages:** the http instance forwards
  to the sidecar by default — make sure the sidecar is running, or set
  `opendota.sidecar-enabled=false` and provide `OPENDOTA_API_KEY` to the http instance.
