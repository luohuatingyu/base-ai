#!/bin/sh
set -eu

BACKEND_URL=""
PAIRING_CODE=""
CA_FILE_URL=""
INSECURE=false
NPM_REGISTRY=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --backend-url) BACKEND_URL="$2"; shift 2 ;;
    --pairing-code) PAIRING_CODE="$2"; shift 2 ;;
    --ca-file) CA_FILE_URL="$2"; shift 2 ;;
    --insecure) INSECURE=true; shift ;;
    --npm-registry) NPM_REGISTRY="$2"; shift 2 ;;
    --force-pair) shift ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ "$(uname -s)" = "Darwin" ] || { echo "macOS is required." >&2; exit 1; }
[ -n "$BACKEND_URL" ] && [ -n "$PAIRING_CODE" ] || {
  echo "--backend-url and --pairing-code are required." >&2
  exit 2
}

DIST_URL="${BACKEND_URL%/}/agent-dist"
SUPPORT_DIR="$HOME/Library/Application Support/BaseAI/DeviceAgent"
RUNTIME_DIR="$SUPPORT_DIR/runtime"
VERSIONS_DIR="$SUPPORT_DIR/versions"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/base-ai-agent.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT HUP INT TERM
mkdir -p "$SUPPORT_DIR" "$RUNTIME_DIR" "$VERSIONS_DIR"
chmod 700 "$SUPPORT_DIR" "$RUNTIME_DIR" "$VERSIONS_DIR"

CURL_TLS=""
[ "$INSECURE" = false ] || CURL_TLS="-k"
# 只允许 HTTPS 下载：清单与代码包同信道传输，放行 HTTP 等于允许中间人替换代码执行
fetch() {
  curl $CURL_TLS -fsSL --proto '=https' --tlsv1.2 "$1" -o "$2"
}

CA_FILE=""
if [ -n "$CA_FILE_URL" ]; then
  CA_FILE="$SUPPORT_DIR/root-ca.crt"
  fetch "$CA_FILE_URL" "$CA_FILE"
  /usr/bin/openssl x509 -in "$CA_FILE" -noout >/dev/null
  chmod 600 "$CA_FILE"
fi

fetch "$DIST_URL/manifest.env" "$WORK_DIR/manifest.env"
manifest_value() {
  sed -n "s/^$1=//p" "$WORK_DIR/manifest.env" | tail -1
}
verify_file() {
  actual="$(shasum -a 256 "$1" | awk '{print $1}')"
  [ "$actual" = "$2" ] || { echo "Checksum mismatch: $1" >&2; exit 1; }
}

case "$(uname -m)" in
  arm64)
    PYTHON_FILE="$(manifest_value PYTHON_ARM64_FILE)"
    PYTHON_SHA="$(manifest_value PYTHON_ARM64_SHA256)"
    NODE_FILE="$(manifest_value NODE_ARM64_FILE)"
    NODE_SHA="$(manifest_value NODE_ARM64_SHA256)"
    ;;
  x86_64)
    PYTHON_FILE="$(manifest_value PYTHON_X86_64_FILE)"
    PYTHON_SHA="$(manifest_value PYTHON_X86_64_SHA256)"
    NODE_FILE="$(manifest_value NODE_X86_64_FILE)"
    NODE_SHA="$(manifest_value NODE_X86_64_SHA256)"
    ;;
  *) echo "Unsupported Mac architecture." >&2; exit 1 ;;
esac
AGENT_VERSION="$(manifest_value AGENT_CODE_VERSION)"
AGENT_SHA="$(manifest_value AGENT_PACKAGE_SHA256)"
for value in "$PYTHON_FILE" "$PYTHON_SHA" "$NODE_FILE" "$NODE_SHA" "$AGENT_VERSION" "$AGENT_SHA"; do
  [ -n "$value" ] || { echo "Incomplete Agent manifest." >&2; exit 1; }
done

fetch "$DIST_URL/runtime/$PYTHON_FILE" "$WORK_DIR/python.tar.gz"
fetch "$DIST_URL/runtime/$NODE_FILE" "$WORK_DIR/node.tar.gz"
fetch "$DIST_URL/device-agent.tar.gz" "$WORK_DIR/agent.tar.gz"
verify_file "$WORK_DIR/python.tar.gz" "$PYTHON_SHA"
verify_file "$WORK_DIR/node.tar.gz" "$NODE_SHA"
verify_file "$WORK_DIR/agent.tar.gz" "$AGENT_SHA"

rm -rf "$RUNTIME_DIR/python"
mkdir -p "$RUNTIME_DIR/python"
tar -xzf "$WORK_DIR/python.tar.gz" -C "$RUNTIME_DIR/python" --strip-components=1
rm -rf "$RUNTIME_DIR/node"
mkdir -p "$RUNTIME_DIR/node"
tar -xzf "$WORK_DIR/node.tar.gz" -C "$RUNTIME_DIR/node" --strip-components=1
cp "$WORK_DIR/manifest.env" "$RUNTIME_DIR/manifest.env"

TARGET_DIR="$VERSIONS_DIR/$AGENT_VERSION"
rm -rf "$TARGET_DIR"
mkdir -p "$TARGET_DIR"
tar -xzf "$WORK_DIR/agent.tar.gz" -C "$TARGET_DIR"
ln -sfn "$TARGET_DIR" "$SUPPORT_DIR/current.next"
mv -h "$SUPPORT_DIR/current.next" "$SUPPORT_DIR/current"
export BASE_AI_AGENT_PYTHON="$RUNTIME_DIR/python/bin/python3.12"
set -- --backend-url "$BACKEND_URL" --pairing-code "$PAIRING_CODE"
[ -z "$CA_FILE" ] || set -- "$@" --ca-file "$CA_FILE"
[ -z "$NPM_REGISTRY" ] || set -- "$@" --npm-registry "$NPM_REGISTRY"
exec "$TARGET_DIR/install.sh" "$@"
