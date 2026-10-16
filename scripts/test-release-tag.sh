#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

bash scripts/check-release-tag.sh v1.0.0 1.0.0
bash scripts/check-release-tag.sh v1.1.0-beta.1 1.1.0-beta.1
for invalid in '' main 1.0.0 v2.0.0 'v1.0.0;echo injected' 'v1.0.0 extra'; do
  if bash scripts/check-release-tag.sh "$invalid" 1.0.0 >/dev/null 2>&1; then
    echo "Unexpectedly accepted: $invalid" >&2
    exit 1
  fi
done
echo 'Release tag checks passed (8 cases)'
