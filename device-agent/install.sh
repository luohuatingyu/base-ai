#!/bin/sh
set -eu

if [ "$(uname -s)" != "Darwin" ]; then
  echo "This Agent can only be installed on macOS." >&2
  exit 1
fi

PYTHON_BIN="${BASE_AI_AGENT_PYTHON:-python3.12}"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  echo "Python 3.12 is required. Use the bundled bootstrap installer." >&2
  exit 1
fi
"$PYTHON_BIN" -c 'import sys; raise SystemExit(0 if sys.version_info[:2] == (3, 12) else 1)' || {
  echo "Python 3.12 is required." >&2
  exit 1
}

BACKEND_URL=""
PAIRING_CODE=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --backend-url) BACKEND_URL="$2"; shift 2 ;;
    --pairing-code) PAIRING_CODE="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$BACKEND_URL" ] && [ -n "$PAIRING_CODE" ] || {
  echo "--backend-url and --pairing-code are required." >&2
  exit 2
}

SUPPORT_DIR="$HOME/Library/Application Support/BaseAI/DeviceAgent"
VENV_DIR="$SUPPORT_DIR/venv"
CURRENT_DIR="$SUPPORT_DIR/current"
LOG_DIR="$SUPPORT_DIR/logs"
mkdir -p "$SUPPORT_DIR" "$LOG_DIR"
chmod 700 "$SUPPORT_DIR" "$LOG_DIR"
"$PYTHON_BIN" -m venv "$VENV_DIR"
"$VENV_DIR/bin/python" -m pip install --disable-pip-version-check --require-virtualenv "$CURRENT_DIR"
"$VENV_DIR/bin/python" -m device_agent.main pair --backend-url "$BACKEND_URL" --pairing-code "$PAIRING_CODE"

PLIST="$HOME/Library/LaunchAgents/com.baseai.device-agent.plist"
sed -e "s|__AGENT_PYTHON__|$VENV_DIR/bin/python|g" \
    -e "s|__AGENT_CURRENT__|$CURRENT_DIR|g" \
    -e "s|__AGENT_LOG__|$LOG_DIR|g" \
    "$CURRENT_DIR/launchd/com.baseai.device-agent.plist" > "$PLIST"
chmod 600 "$PLIST"
launchctl bootout "gui/$(id -u)/com.baseai.device-agent" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "Base AI device Agent installed."
