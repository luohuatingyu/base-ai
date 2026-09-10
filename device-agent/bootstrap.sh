#!/bin/sh
set -eu

BACKEND_URL=""
PAIRING_CODE=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --backend-url) BACKEND_URL="$2"; shift 2 ;;
    --pairing-code) PAIRING_CODE="$2"; shift 2 ;;
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

curl -fsSL --proto '=https' --tlsv1.2 "$DIST_URL/manifest.env" -o "$WORK_DIR/manifest.env"
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
    ;;
  x86_64)
    PYTHON_FILE="$(manifest_value PYTHON_X86_64_FILE)"
    PYTHON_SHA="$(manifest_value PYTHON_X86_64_SHA256)"
    ;;
  *) echo "Unsupported Mac architecture." >&2; exit 1 ;;
esac
AGENT_VERSION="$(manifest_value AGENT_CODE_VERSION)"
AGENT_SHA="$(manifest_value AGENT_PACKAGE_SHA256)"
for value in "$PYTHON_FILE" "$PYTHON_SHA" "$AGENT_VERSION" "$AGENT_SHA"; do
  [ -n "$value" ] || { echo "Incomplete Agent manifest." >&2; exit 1; }
done

curl -fsSL --proto '=https' --tlsv1.2 "$DIST_URL/runtime/$PYTHON_FILE" -o "$WORK_DIR/python.tar.gz"
curl -fsSL --proto '=https' --tlsv1.2 "$DIST_URL/device-agent.tar.gz" -o "$WORK_DIR/agent.tar.gz"
verify_file "$WORK_DIR/python.tar.gz" "$PYTHON_SHA"
verify_file "$WORK_DIR/agent.tar.gz" "$AGENT_SHA"

rm -rf "$RUNTIME_DIR/python"
mkdir -p "$RUNTIME_DIR/python"
tar -xzf "$WORK_DIR/python.tar.gz" -C "$RUNTIME_DIR/python" --strip-components=1

TARGET_DIR="$VERSIONS_DIR/$AGENT_VERSION"
rm -rf "$TARGET_DIR"
mkdir -p "$TARGET_DIR"
tar -xzf "$WORK_DIR/agent.tar.gz" -C "$TARGET_DIR"
ln -sfn "$TARGET_DIR" "$SUPPORT_DIR/current.next"
mv -h "$SUPPORT_DIR/current.next" "$SUPPORT_DIR/current"
export BASE_AI_AGENT_PYTHON="$RUNTIME_DIR/python/bin/python3.12"
exec "$TARGET_DIR/install.sh" --backend-url "$BACKEND_URL" --pairing-code "$PAIRING_CODE"
