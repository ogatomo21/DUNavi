#!/usr/bin/env bash
set -euo pipefail

tag=${1-}
version=${2-}
if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo 'Invalid version tag' >&2
  exit 1
fi
if [[ "$tag" != "v$version" ]]; then
  echo 'Tag must match Gradle versionName' >&2
  exit 1
fi
