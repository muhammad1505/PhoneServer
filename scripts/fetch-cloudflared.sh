#!/usr/bin/env bash
# Kept as the stable entry point used by the release checklist. Pocket Share ships a patched
# cloudflared build because the upstream binary cannot use Android's DNS resolver; see
# scripts/build-cloudflared-patched.sh and docs/CLOUDFLARED.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${ROOT}/scripts/build-cloudflared-patched.sh" "$@"
