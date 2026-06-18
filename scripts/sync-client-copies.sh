#!/usr/bin/env bash
# Re-copy the canonical OpenDota client classes from the root module into the sidecar module.
#
# The sidecar is a deliberately standalone build that cannot depend on the root jar, so it keeps
# byte-identical COPIES of four client classes. The root copies under
# src/main/java/com/raorbit/opendota/client/ are the SINGLE SOURCE OF TRUTH — edit them there, then
# run this to mirror the change into the sidecar, and commit both. ClientCopyDriftTest fails the
# sidecar build (hard, under CI) if the copies ever diverge, so this is the sanctioned way to sync.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="src/main/java/com/raorbit/opendota/client"
FILES=(OpenDotaClient OpenDotaException RateLimiter TtlCache)

for f in "${FILES[@]}"; do
  cp "$ROOT_DIR/$PKG/$f.java" "$ROOT_DIR/sidecar/$PKG/$f.java"
  echo "synced $f.java -> sidecar/"
done
echo "done — review 'git diff sidecar/' and commit the synced copies."
