#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PROTO_ROOT="${VITALEDGE_PROTO_ROOT:-${HOME}/go/src/vitaledge/api/proto}"
MAIN_CLASS="examples.BasicUsage"

MAVEN_BIN=""
if command -v mvn >/dev/null 2>&1; then
  MAVEN_BIN="mvn"
elif [[ -x "${HOME}/.sdkman/candidates/maven/current/bin/mvn" ]]; then
  MAVEN_BIN="${HOME}/.sdkman/candidates/maven/current/bin/mvn"
else
  echo "Error: Maven not found on PATH or at ~/.sdkman/candidates/maven/current/bin/mvn" >&2
  exit 1
fi

if [[ -z "${JAVA_HOME:-}" && -x "${HOME}/.sdkman/candidates/java/current/bin/java" ]]; then
  export JAVA_HOME="${HOME}/.sdkman/candidates/java/current"
fi

cd "${REPO_ROOT}"

"${MAVEN_BIN}" \
  -Dvitaledge.proto.root="${PROTO_ROOT}" \
  -Dexec.mainClass="${MAIN_CLASS}" \
  -Dexec.cleanupDaemonThreads=false \
  exec:java