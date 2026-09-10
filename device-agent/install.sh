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
CA_FILE=""
NPM_REGISTRY=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --backend-url) BACKEND_URL="$2"; shift 2 ;;
    --pairing-code) PAIRING_CODE="$2"; shift 2 ;;
    --ca-file) CA_FILE="$2"; shift 2 ;;
    --npm-registry) NPM_REGISTRY="$2"; shift 2 ;;
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
RUNTIME_DIR="$SUPPORT_DIR/runtime"
APPIUM_PREFIX="$RUNTIME_DIR/appium"
APPIUM_HOME="$SUPPORT_DIR/appium-home"
MANIFEST_FILE="$RUNTIME_DIR/manifest.env"
mkdir -p "$SUPPORT_DIR" "$LOG_DIR"
chmod 700 "$SUPPORT_DIR" "$LOG_DIR"
"$PYTHON_BIN" -m venv "$VENV_DIR"
"$VENV_DIR/bin/python" -m pip install --disable-pip-version-check --require-virtualenv "$CURRENT_DIR"
set -- --backend-url "$BACKEND_URL" --pairing-code "$PAIRING_CODE"
[ -z "$CA_FILE" ] || set -- "$@" --ca-file "$CA_FILE"
"$VENV_DIR/bin/python" -m device_agent.main pair "$@"

# 从已校验清单读取固定运行依赖，不执行清单之外的任意命令。
manifest_value() {
  sed -n "s/^$1=//p" "$MANIFEST_FILE" | tail -1
}

NODE_BIN="$RUNTIME_DIR/node/bin/node"
NPM_BIN="$RUNTIME_DIR/node/bin/npm"
APPIUM_SPEC="$(manifest_value APPIUM_SPEC)"
XCUITEST_SPEC="$(manifest_value XCUITEST_SPEC)"
[ -x "$NODE_BIN" ] && [ -x "$NPM_BIN" ] && [ -n "$APPIUM_SPEC" ] && [ -n "$XCUITEST_SPEC" ] || {
  echo "Bundled Node/Appium manifest is incomplete." >&2
  exit 1
}
mkdir -p "$APPIUM_PREFIX" "$APPIUM_HOME"
chmod 700 "$APPIUM_PREFIX" "$APPIUM_HOME"
export PATH="$RUNTIME_DIR/node/bin:$APPIUM_PREFIX/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export APPIUM_HOME
set -- install -g --engine-strict --prefix "$APPIUM_PREFIX"
[ -z "$NPM_REGISTRY" ] || set -- "$@" --registry "$NPM_REGISTRY"
"$NPM_BIN" "$@" "$APPIUM_SPEC"
# Appium driver install 内部独立调用 npm，不会继承命令行 registry 参数，
# 必须通过环境变量把私有镜像同步给驱动安装，否则内网机器装完 Appium 也装不上驱动
[ -z "$NPM_REGISTRY" ] || export npm_config_registry="$NPM_REGISTRY"
if ! "$APPIUM_PREFIX/bin/appium" driver list --installed 2>&1 | grep -Fq 'xcuitest'; then
  "$APPIUM_PREFIX/bin/appium" driver install "$XCUITEST_SPEC"
fi

PLIST="$HOME/Library/LaunchAgents/com.baseai.device-agent.plist"
sed -e "s|__AGENT_PYTHON__|$VENV_DIR/bin/python|g" \
    -e "s|__AGENT_CURRENT__|$CURRENT_DIR|g" \
    -e "s|__AGENT_PATH__|$RUNTIME_DIR/node/bin:$APPIUM_PREFIX/bin:/usr/bin:/bin:/usr/sbin:/sbin|g" \
    -e "s|__APPIUM_HOME__|$APPIUM_HOME|g" \
    -e "s|__AGENT_LOG__|$LOG_DIR|g" \
    "$CURRENT_DIR/launchd/com.baseai.device-agent.plist" > "$PLIST"
chmod 600 "$PLIST"

APPIUM_PLIST="$HOME/Library/LaunchAgents/com.baseai.device-agent-appium.plist"
sed -e "s|__NODE_BIN__|$NODE_BIN|g" \
    -e "s|__APPIUM_BIN__|$APPIUM_PREFIX/bin/appium|g" \
    -e "s|__NODE_PATH__|$RUNTIME_DIR/node/bin|g" \
    -e "s|__APPIUM_HOME__|$APPIUM_HOME|g" \
    -e "s|__USER_HOME__|$HOME|g" \
    -e "s|__AGENT_LOG__|$LOG_DIR|g" \
    "$CURRENT_DIR/launchd/com.baseai.device-agent-appium.plist" > "$APPIUM_PLIST"
chmod 600 "$APPIUM_PLIST"

# 安装 root-owned Registry 副本，阻止普通用户替换特权进程代码或运行时。
REGISTRY_ROOT="/Library/Application Support/BaseAI/DeviceAgentRegistry"
REGISTRY_STAGE="${REGISTRY_ROOT}.stage.$$"
REGISTRY_PLIST="/Library/LaunchDaemons/com.baseai.device-agent-registry.plist"
REGISTRY_PLIST_TEMP="$(mktemp "${TMPDIR:-/tmp}/base-ai-registry-plist.XXXXXX")"
trap 'rm -f "$REGISTRY_PLIST_TEMP"' EXIT HUP INT TERM
sudo rm -rf "$REGISTRY_STAGE"
sudo install -d -m 0700 "$REGISTRY_STAGE/runtime" "$REGISTRY_STAGE/app/device_agent" "$REGISTRY_STAGE/logs"
sudo ditto "$RUNTIME_DIR/python" "$REGISTRY_STAGE/runtime/python"
sudo ditto "$RUNTIME_DIR/node" "$REGISTRY_STAGE/runtime/node"
sudo ditto "$APPIUM_PREFIX" "$REGISTRY_STAGE/runtime/appium"
sudo ditto "$APPIUM_HOME" "$REGISTRY_STAGE/appium-home"
sudo ditto "$CURRENT_DIR/device_agent" "$REGISTRY_STAGE/app/device_agent"
sudo chown -R root:wheel "$REGISTRY_STAGE"
sudo chmod -R go-w "$REGISTRY_STAGE"
sed -e "s|__REGISTRY_PYTHON__|$REGISTRY_ROOT/runtime/python/bin/python3.12|g" \
    -e "s|__AUTHORIZED_UID__|$(id -u)|g" \
    -e "s|__REGISTRY_ROOT__|$REGISTRY_ROOT|g" \
    "$CURRENT_DIR/launchd/com.baseai.device-agent-registry.plist" > "$REGISTRY_PLIST_TEMP"
sudo launchctl bootout system/com.baseai.device-agent-registry >/dev/null 2>&1 || true
sudo rm -rf "$REGISTRY_ROOT"
sudo mv "$REGISTRY_STAGE" "$REGISTRY_ROOT"
sudo install -o root -g wheel -m 0644 "$REGISTRY_PLIST_TEMP" "$REGISTRY_PLIST"
sudo launchctl bootstrap system "$REGISTRY_PLIST"

launchctl bootout "gui/$(id -u)/com.baseai.device-agent-appium" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$APPIUM_PLIST"
launchctl bootout "gui/$(id -u)/com.baseai.device-agent" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "Base AI device Agent installed."
