# Dependency policy

This project runs a **deliberately locked** dependency stack. The pins are not
inertia — they are load-bearing. This doc explains why the stack is locked, how
security patches get in without breaking it, and which transitive dependencies
must always move together.

It is policy/process, not a changelog. It does not propose version bumps.

## Why the stack is locked

The MCP server is a tightly coupled assembly: Spring Boot's dependency
management, the Spring AI BOM, and the MCP Java SDK each pin large transitive
trees, and those trees overlap. The pins that matter (see the root `pom.xml`
and `CLAUDE.md`):

- Maven parent: `spring-boot-starter-parent` **3.5.16**
- Spring AI BOM: **1.1.8**
- MCP server starter: `org.springframework.ai:spring-ai-starter-mcp-server`
  (the **core stdio** starter — the only MCP dependency in the **default** build).
  The `-webmvc` starter is permitted **only** inside the opt-in `http` Maven profile
  (`mvn -Phttp`); it must never be added to the top-level `<dependencies>`. The
  `-webflux` variant is not used at all. See "The opt-in `http` profile" below.
- MCP Java SDK: `io.modelcontextprotocol.sdk:mcp` **0.18.3** (transitive via the
  starter — never declared directly)
- Java: **21**
- Packaging: `spring-boot-maven-plugin` only (no shade / fat-jar)

Bumping any one of these (parent, BOM, or SDK) silently reshuffles the whole
transitive set, which is exactly what we want to avoid. So we do **not** bump
the parent/BOM/SDK to chase a CVE. Instead we forward-pin the one offending
transitive (see the backport template below).

### Canonical case: the json-schema-validator 2.0.0 override

The root `pom.xml` carries a single-line `dependencyManagement` entry pinning
`com.networknt:json-schema-validator` to **2.0.0**. This is **required** and
must not be removed:

- MCP SDK 0.18.3 (`mcp-json-jackson2`) needs json-schema-validator **2.0.0** —
  it references class `com.networknt.schema.dialect.Dialects` — and declares
  2.0.0 in its own pom.
- Verified against the resolved BOMs (2026-07-14): neither Spring Boot
  3.5.15/3.5.16 nor spring-ai-bom 1.1.8 carries a managed entry for this
  artifact, so resolution yields 2.0.0 from the SDK even without the override.
- The override stays as a **downgrade guard**: if a future parent/BOM starts
  managing this artifact below 2.0.0, the server fails at startup with
  `NoClassDefFoundError` for `com.networknt.schema.dialect.Dialects`.

This is a version-only override: no new or banned dependency is introduced, and
the SDK already pulls json-schema-validator in transitively. We simply hold it
at the version the SDK actually needs.

This is the **canonical backport template** for this repo:

> **Forward-pin a single transitive via `dependencyManagement`.** When a
> transitive needs to be a different version than the parent/BOM pins (for a CVE
> fix or, as here, an API the SDK requires), add or adjust a one-line
> `dependencyManagement` entry for that single `groupId:artifactId`. Do **not**
> bump the parent, the Spring AI BOM, or the MCP SDK to drag it along.

## The opt-in `http` profile (webmvc is profile-confined)

The default build is **pure core stdio** — no web server on the classpath. The
opt-in remote-MCP / HTTP transport is added **only** under the `http` Maven
profile, which is the clean seam that keeps the default build byte-behavior-identical:

- `mvn -Phttp package` adds `org.springframework.ai:spring-ai-starter-mcp-server-webmvc`
  (version **managed by the spring-ai-bom 1.1.8** — do **not** declare a version),
  which transitively pulls `spring-boot-starter-web` (embedded Tomcat), the SDK's
  `io.modelcontextprotocol.sdk:mcp-spring-webmvc` transport (sibling of the core
  `mcp` 0.18.3 — also never declared directly), and `spring-webmvc`.
- The profile attaches a distinct **`http`-classified** artifact
  (`target/opendota-mcp-1.2.0-http.jar`) via the spring-boot-maven-plugin
  `<classifier>http</classifier>`, so the plain unclassified jar stays web-free.
- The http-only sources live in `src/http/java` and are compiled **only** under the
  profile (added via `build-helper-maven-plugin` 3.6.1).
- The json-schema-validator **2.0.0** forward-pin in root `dependencyManagement`
  applies to this profile too (profiles inherit the project's dependency management);
  the webmvc path still uses `mcp-json-jackson2`, so the pin must remain.

Verify both builds after any change here: `mvn clean verify` (default — must show
**no** webmvc/tomcat in `dependency:tree`) and `mvn -Phttp clean verify` (the http
artifact must build).

## Security-patch intake process

CVEs are **surfaced, not auto-bumped**. Nothing in CI rewrites the pins.

1. **Surface (non-blocking CI).** A CI job runs
   `mvn -B versions:display-dependency-updates` (root build) and, separately,
   against `sidecar/pom.xml`. It reports available newer versions but is
   **advisory only** — it does not fail the build and does not change any
   version. (An equivalent advisory dependency/CVE scan, e.g.
   `dependency-check` / OSV, may feed the same review queue.)
2. **Review manually.** A human reads the report, identifies the actually
   vulnerable transitive, and decides whether it is reachable / exploitable in
   this read-only stdio server before acting.
3. **Patch with a single-line pin.** Apply the fix by **adding or adjusting one
   `dependencyManagement` pin** for the affected `groupId:artifactId` (the
   backport template above) — keeping the parent, BOM, and SDK versions
   unchanged.
4. **Verify the whole assembly.** Run `mvn -B clean verify` from the repo root
   (and `mvn -B -f sidecar/pom.xml clean verify` for the sidecar). A pin is only
   accepted if the full build, tests, and server startup still pass — the
   startup path is what the json-schema-validator pin protects, so a green
   compile is not sufficient.

Only bump the parent/BOM/SDK themselves as a deliberate, separately reviewed
upgrade — never as a reflexive response to a scanner finding.

## SDK-coupled transitives (must move together)

Some transitives are version-coupled to the MCP SDK and cannot be pinned in
isolation. Treat these as a unit — if the SDK ever moves, re-derive the
matching versions; if one of these is flagged by a scanner, the fix is almost
always to align it to what the **current** SDK expects, not to bump it freely:

| Coupled to | Transitive | Current pin | Why coupled |
| --- | --- | --- | --- |
| MCP SDK `io.modelcontextprotocol.sdk:mcp` 0.18.3 | `com.networknt:json-schema-validator` | **2.0.0** (forward-pinned in root `dependencyManagement`) | SDK's `mcp-json-jackson2` requires `com.networknt.schema.dialect.Dialects`, present only in 2.0.x. Pinning below 2.0.0 breaks startup. |

If the MCP SDK version changes, the json-schema-validator pin must be
re-validated (and likely changed) **in the same change** — never independently.

## What this policy does not allow

These constraints are locked (see `CLAUDE.md`); a CVE response must respect them:

- Never bump the pinned parent / Spring AI BOM / MCP SDK to chase a patch — pin
  the single transitive instead.
- Never remove the json-schema-validator 2.0.0 override.
- `spring-boot-maven-plugin` only — no maven-shade or any other fat-jar
  mechanism.
- Never declare the MCP SDK directly, and never bump it to 2.0.0. This includes the
  webmvc-transport sibling `io.modelcontextprotocol.sdk:mcp-spring-webmvc`, which
  arrives transitively via the `-webmvc` starter under the `http` profile.
- Never add `spring-ai-starter-mcp-server-webmvc` (or any embedded web server) to the
  top-level `<dependencies>`. It is permitted **only** inside the `http` Maven profile;
  `-webflux` is not used at all. The default build must stay web-free.
- The sidecar is a standalone build with **no parent** and JDK-only runtime; do
  not introduce a reactor parent to "unify" dependency management. Each build
  applies this same single-transitive-pin policy independently.
- `sqlite-jdbc` (the durable L2 cache, now implemented in the sidecar) belongs in
  the **sidecar** pom only — never in the root pom.
- `jackson-databind` is now used **directly** by application code (`ExplorerTools`
  reshapes the `/explorer` response via `ObjectMapper`/`JsonNode`). It is left
  **undeclared** in the root pom and version-managed transitively by the Spring
  Boot parent. This is Jackson **tree-model** reshaping, **not** DTO/POJO binding,
  so the repo-wide "raw JSON strings, no DTO mapping" invariant still holds. A
  Jackson CVE is handled by the same single-line `dependencyManagement` backport as
  any other transitive — it is not forward-pinned today (unlike json-schema-validator),
  because the parent's managed version satisfies the code.
