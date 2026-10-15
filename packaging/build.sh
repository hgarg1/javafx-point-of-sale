#!/usr/bin/env bash
set -euo pipefail
repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
    echo 'Set JAVA_HOME to Eclipse Temurin JDK 26.0.2.1.' >&2
    exit 1
fi
cd "$repo"
exec "$JAVA_HOME/bin/java" packaging/Package.java "$@"
