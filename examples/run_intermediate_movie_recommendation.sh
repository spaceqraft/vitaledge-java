#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PROTO_ROOT="${VITALEDGE_PROTO_ROOT:-${HOME}/go/src/vitaledge/api/proto}"
MAIN_CLASS="examples.IntermediateMovieRecommendation"

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

has_flag() {
  local flag="$1"
  shift
  for arg in "$@"; do
    if [[ "$arg" == "$flag" ]]; then
      return 0
    fi
  done
  return 1
}

ARGS=("$@")

if ! has_flag "--movies" "${ARGS[@]}"; then
  if [[ -n "${MOVIES_CSV:-}" ]]; then
    ARGS=("--movies" "${MOVIES_CSV}" "${ARGS[@]}")
  fi
fi

if ! has_flag "--ratings" "${ARGS[@]}"; then
  if [[ -n "${RATINGS_CSV:-}" ]]; then
    ARGS=("--ratings" "${RATINGS_CSV}" "${ARGS[@]}")
  fi
fi

if ! has_flag "--movies" "${ARGS[@]}" || ! has_flag "--ratings" "${ARGS[@]}"; then
  cat >&2 <<'USAGE'
Usage:
  ./examples/run_intermediate_movie_recommendation.sh --movies /path/to/movies.csv --ratings /path/to/ratings.csv [additional args]

Alternately set MOVIES_CSV and RATINGS_CSV environment variables.
USAGE
  exit 1
fi

cd "${REPO_ROOT}"

"${MAVEN_BIN}" \
  -Dvitaledge.proto.root="${PROTO_ROOT}" \
  -Dexec.mainClass="${MAIN_CLASS}" \
  -Dexec.args="${ARGS[*]}" \
  -Dexec.cleanupDaemonThreads=false \
  exec:java