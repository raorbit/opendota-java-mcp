# Remote MCP connector (HTTP transport)

This guide covers the **opt-in HTTP transport** that lets the OpenDota MCP server
be added through Claude's **"Add custom connector → Remote MCP server URL"** dialog,
instead of being launched locally over stdio.

> **The default build is unaffected.** Everything here is opt-in: it requires the
> `-Phttp` Maven build and the `http` Spring profile at runtime. Plain `mvn package`
> still produces the pure-stdio `target/opendota-mcp-1.3.0.jar` with no web server,
> and existing Claude Desktop / Claude Code stdio users are unchanged. See
> [`mcp-registration.md`](mcp-registration.md) for the stdio setup.

## How it fits together

```
Claude (Anthropic cloud)
        │  https://opendota.example.com/mcp   (public, CA-trusted TLS)
        ▼
  TLS proxy / tunnel  (Cloudflare Tunnel + Access / Caddy)   ← terminates TLS,
        │  http://127.0.0.1:8080/mcp                          authenticates the caller
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
# -> target/opendota-mcp-1.3.0-http.jar   (the runnable http jar)
# The unclassified opendota-mcp-1.3.0.jar this build leaves behind is a thin,
# non-executable jar (no launcher) — for a runnable stdio jar, run a plain `mvn package`.
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
  java -jar target/opendota-mcp-1.3.0-http.jar
```

```powershell
# PowerShell
$env:SPRING_PROFILES_ACTIVE = 'http'
$env:OPENDOTA_HTTP_BEARER_TOKEN = '<secret>'
java -jar target\opendota-mcp-1.3.0-http.jar
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
| `opendota.http.allowed-hosts` | `OPENDOTA_HTTP_ALLOWED_HOSTS` | — | Extra `Host` values the `auth-mode=none` anti-rebinding guard accepts (the public hostname your proxy/tunnel forwards). Literals only; ignored in bearer mode. |
| `opendota.sidecar-enabled` | `OPENDOTA_SIDECAR_ENABLED` | `true` | Forward through the shared sidecar. |

### Authentication (bearer) and the fail-closed guard

- **Bearer.** When `opendota.http.auth-mode=bearer` (the default), every request to
  `/mcp` must carry `Authorization: Bearer <token>` matching `opendota.http.bearer-token`.
  The comparison is constant-time (`MessageDigest.isEqual`). A missing/wrong token
  gets `401` with `WWW-Authenticate: Bearer`. The actuator liveness endpoint
  (`/actuator/health`, served by `spring-boot-starter-actuator` under `-Phttp`) is
  left **un-gated** so a proxy/tunnel can probe; nothing else is exempt.
- **Fail-closed bind guard.** On startup, if the server binds a **non-loopback**
  address (e.g. `server.address=0.0.0.0`, or unset → all interfaces) **without** a
  bearer token, it logs a SEVERE message and **refuses to start** (`System.exit(1)`)
  before the port goes live — mirroring the sidecar's guard. This prevents an open,
  unauthenticated `/mcp` surface. To bind a non-loopback address you **must** set
  `opendota.http.auth-mode=bearer` and `OPENDOTA_HTTP_BEARER_TOKEN`.

  The recommended shape is to **bind loopback** (`127.0.0.1`) and let the TLS proxy
  forward to it, rather than binding a public interface directly.

## 3. Put a TLS proxy / tunnel in front — and authenticate the public hop

Claude connects from **Anthropic's cloud**, not from your localhost, so the endpoint
must be a **public `https://` URL with a real CA-trusted certificate** — a bare local
HTTP port or a self-signed cert will not work. **TLS is terminated by an external
proxy/tunnel; it is never implemented in the JVM.**

> **Read this first — how the public endpoint actually gets authenticated.**
> Claude's consumer **"Add custom connector"** dialog authenticates with **OAuth**
> (the Client ID / Secret fields); it **cannot send a static bearer token**. So the
> in-JVM `OPENDOTA_HTTP_BEARER_TOKEN` does **not** authenticate the dialog connector —
> it authenticates **programmatic** MCP clients (Messages API / code that sets the
> header) and serves as a defense-in-depth check on the proxy→app hop.
>
> Therefore **the public hop must be authenticated by an OAuth/identity layer at the
> proxy** (Cloudflare Access is the easy path), *not* by the bearer. The two failure
> modes to avoid:
> - **Injecting a fixed bearer for every public request** (e.g. Caddy `header_up
>   Authorization`) authenticates *nobody* on the public side — it makes `/mcp`
>   usable by anyone who knows the URL. Only do this *behind* a real access layer.
> - **Running bearer mode with nothing supplying the header** makes the dialog
>   connector get a `401` (it has no bearer field). Pair bearer mode with a header
>   injector, or use `auth-mode=none` behind an access-controlled proxy.

### Recommended: Cloudflare Tunnel + Cloudflare Access

```sh
# After `cloudflared tunnel login` and creating a named tunnel with a DNS route:
#   https://opendota.example.com  ->  http://127.0.0.1:8080
cloudflared tunnel run <tunnel-name>
```

Then add a **Cloudflare Access** policy on `opendota.example.com` (Zero Trust →
Access → Applications) so only your identity/SSO can reach it. Access enforces auth at
the edge; only approved requests are forwarded to the loopback app. With the Access
policy in place you can run the app with `opendota.http.auth-mode=none` (the loopback
bind satisfies the fail-closed guard), or keep `auth-mode=bearer` and have
Access/cloudflared inject the matching header.

> **Loopback is not isolation.** A loopback bind does *not* mean the app is reachable
> only through the tunnel: any browser on the same machine can reach `127.0.0.1:8080`,
> and a DNS-rebinding page (an attacker hostname re-pointed at `127.0.0.1`) reaches it
> as what the browser considers a same-origin request. In `auth-mode=none` the app
> therefore runs an anti-rebinding **Host guard** (`HostGuardFilter`) that rejects any
> `Host` header that is not a loopback literal. Because cloudflared forwards the
> original public `Host` (`opendota.example.com`), you **must** allow-list it or the
> tunnel's requests are refused with `403`:
>
> ```sh
> OPENDOTA_HTTP_ALLOWED_HOSTS=opendota.example.com
> ```
>
> (`opendota.http.allowed-hosts`: comma-separated literals, any `:port` ignored, never
> DNS-resolved. `/actuator/health` stays un-gated for the tunnel's liveness probe.)

> A bare `cloudflared tunnel --url http://127.0.0.1:8080` with **no** Access policy is
> an **open** endpoint — anyone with the URL reaches `/mcp`. Always add the Access
> policy (or another auth layer) before exposing it.

### Caddy (automatic Let's Encrypt TLS) — add a real auth layer

Caddy gets the CA cert for you, but you **must** add public-side authentication; the
connector dialog can't send a bearer, so reverse-proxying with a bare injected bearer
would leave `/mcp` open. Put an auth layer in front — e.g. `forward_auth` to an
OAuth/OIDC provider, or `basic_auth` for a private/programmatic setup — and only then
optionally inject the upstream bearer for defense in depth:

```caddyfile
opendota.example.com {
    # Public auth (pick one): OIDC/OAuth via forward_auth, or basic_auth, etc.
    forward_auth auth-provider:9091 {
        uri /api/verify
        copy_headers Remote-User
    }
    reverse_proxy 127.0.0.1:8080 {
        # Optional defense-in-depth: satisfy the app's bearer filter on the internal hop.
        header_up Authorization "Bearer {env.OPENDOTA_HTTP_BEARER_TOKEN}"
    }
}
```

Caddy renews the certificate automatically and reverse-proxies `/mcp` (and
`/actuator/health`) to the loopback app. **Do not** ship the bare `header_up`-only
config from earlier drafts as your public auth — it authenticates no one.

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
- **Refuses to start, "auth-mode=bearer was requested but no bearer token is
  configured":** the default `auth-mode=bearer` needs a token even on a loopback
  bind. Set `OPENDOTA_HTTP_BEARER_TOKEN`, or set `opendota.http.auth-mode=none`
  for an explicitly unauthenticated loopback-only instance.
- **`401` on every call:** the `Authorization: Bearer <token>` header is missing or
  does not match `opendota.http.bearer-token`. If a proxy injects it, confirm the
  proxy and the app use the same secret.
- **Connector handshake fails / wrong endpoint:** ensure
  `spring.ai.mcp.server.protocol=STREAMABLE` (the library default is the deprecated
  SSE transport) and that you selected **Streamable HTTP** in the dialog.
- **Tools error with rate-limit or "sidecar" messages:** the http instance forwards
  to the sidecar by default — make sure the sidecar is running, or set
  `opendota.sidecar-enabled=false` and provide `OPENDOTA_API_KEY` to the http instance.
