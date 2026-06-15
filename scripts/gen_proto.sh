#!/usr/bin/env bash
# Regenerate Java + gRPC Java sources from the VitalEdge proto definitions.
# Run from anywhere; paths are resolved relative to this script.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

VITALEDGE_PROTO_ROOT="${VITALEDGE_PROTO_ROOT:-${HOME}/go/src/vitaledge/api/proto}"
DDL_PROTO_FILE_REL="${PROTO_FILE_REL:-vitaledge/v1/ddl.proto}"
DML_PROTO_FILE_REL="${PROTO_FILE_REL:-vitaledge/v1/dml.proto}"

DDL_SRC_PROTO_FILE="${VITALEDGE_PROTO_ROOT}/${DDL_PROTO_FILE_REL}"
DDL_VENDORED_PROTO_FILE="${REPO_ROOT}/api/proto/${DDL_PROTO_FILE_REL}"
DML_SRC_PROTO_FILE="${VITALEDGE_PROTO_ROOT}/${DML_PROTO_FILE_REL}"
DML_VENDORED_PROTO_FILE="${REPO_ROOT}/api/proto/${DML_PROTO_FILE_REL}"
PROTO_INCLUDE_DIR="${REPO_ROOT}/api/proto"

JAVA_OUT_DIR="${JAVA_OUT_DIR:-${REPO_ROOT}/target/generated-sources/protobuf/java}"
GRPC_JAVA_OUT_DIR="${GRPC_JAVA_OUT_DIR:-${REPO_ROOT}/target/generated-sources/protobuf/grpc-java}"

if [[ ! -f "${DDL_SRC_PROTO_FILE}" ]]; then
  echo "error: proto file not found: ${DDL_SRC_PROTO_FILE}" >&2
  echo "hint: set VITALEDGE_PROTO_ROOT and/or DDL_PROTO_FILE_REL" >&2
  exit 1
fi
if [[ ! -f "${DML_SRC_PROTO_FILE}" ]]; then
  echo "error: proto file not found: ${DML_SRC_PROTO_FILE}" >&2
  echo "hint: set VITALEDGE_PROTO_ROOT and/or DML_PROTO_FILE_REL" >&2
  exit 1
fi

HAS_PROTOC=false
if command -v protoc >/dev/null 2>&1 && command -v protoc-gen-grpc-java >/dev/null 2>&1; then
  HAS_PROTOC=true
fi

mkdir -p "$(dirname "${DDL_VENDORED_PROTO_FILE}")"
mkdir -p "${JAVA_OUT_DIR}" "${GRPC_JAVA_OUT_DIR}"

# Keep a vendored local copy of the proto so this client repo can build standalone.
cp "${DDL_SRC_PROTO_FILE}" "${DDL_VENDORED_PROTO_FILE}"
cp "${DML_SRC_PROTO_FILE}" "${DML_VENDORED_PROTO_FILE}"

if [[ "${HAS_PROTOC}" == "true" ]]; then
  protoc \
    -I "${PROTO_INCLUDE_DIR}" \
    --java_out="${JAVA_OUT_DIR}" \
    --grpc-java_out="${GRPC_JAVA_OUT_DIR}" \
    --plugin=protoc-gen-grpc-java="$(command -v protoc-gen-grpc-java)" \
    "${DDL_VENDORED_PROTO_FILE}"

  protoc \
    -I "${PROTO_INCLUDE_DIR}" \
    --java_out="${JAVA_OUT_DIR}" \
    --grpc-java_out="${GRPC_JAVA_OUT_DIR}" \
    --plugin=protoc-gen-grpc-java="$(command -v protoc-gen-grpc-java)" \
    "${DML_VENDORED_PROTO_FILE}"

  echo "Vendored DDL proto: ${DDL_VENDORED_PROTO_FILE}"
  echo "Vendored DML proto: ${DML_VENDORED_PROTO_FILE}"
  echo "Generated Java sources: ${JAVA_OUT_DIR}"
  echo "Generated gRPC Java sources: ${GRPC_JAVA_OUT_DIR}"
  exit 0
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "error: neither protoc+protoc-gen-grpc-java nor mvn was found" >&2
  echo "hint: install protoc + protoc-gen-grpc-java, or install Maven and rerun" >&2
  exit 1
fi

echo "warning: protoc/protoc-gen-grpc-java not found; using Maven protobuf plugin instead" >&2
mvn -Dvitaledge.proto.root="${PROTO_INCLUDE_DIR}" generate-sources

echo "Vendored DDL proto: ${DDL_VENDORED_PROTO_FILE}"
echo "Vendored DML proto: ${DML_VENDORED_PROTO_FILE}"
echo "Generated Java sources: ${REPO_ROOT}/target/generated-sources/protobuf/java"
echo "Generated gRPC Java sources: ${REPO_ROOT}/target/generated-sources/protobuf/grpc-java"
