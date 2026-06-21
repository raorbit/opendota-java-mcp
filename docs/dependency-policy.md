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

- Maven parent: `spring-boot-starter-parent` **3.5.15**
- Spring AI BOM: **1.1.8**
- MCP server starter: `org.springframework.ai:spring-ai-starter-mcp-server`
  (the **core stdio** starter — not `-webmvc` / `-webflux`)
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
  it references class `com.networknt.schema.dialect.Dialects`.
- Spring Boot 3.5.15's dependency management otherwise pins json-schema-validator
  to **1.5.9**, which **lacks** that class.
- Without the override, the server throws `NoClassDefFoundError` for
  `com.networknt.schema.dialect.Dialects` **at startup**.

This is a version-only override: no new or banned dependency is introduced, and
the SDK already pulls json-schema-validator in transitively. We simply move it
forward to the version the SDK actually needs.

This is the **canonical backport template** for this repo:

> **Forward-pin a single transitive via `dependencyManagement`.** When a
> transitive needs to be a different version than the parent/BOM pins (for a CVE
> fix or, as here, an API the SDK requires), add or adjust a one-line
> `dependencyManagement` entry for that single `groupId:artifactId`. Do **not**
> bump the parent, the Spring AI BOM, or the MCP SDK to drag it along.

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
- Never declare the MCP SDK directly, and never bump it to 2.0.0.
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
