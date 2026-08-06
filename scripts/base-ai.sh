#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
RUNTIME_DIR=${BASE_AI_RUNTIME_DIR:-$PROJECT_DIR/.runtime}
HOSTS_FILE=$RUNTIME_DIR/host-ips
TRACKER_PID_FILE=$RUNTIME_DIR/host-ip-tracker.pid
TRACKER_LOG_FILE=$RUNTIME_DIR/host-ip-tracker.log

# 输出错误并终止命令，避免以不可用地址启动跟踪器。
fail() {
	echo "base-ai host IP tracker error: $*" >&2
	exit 1
}

# 校验规范 IPv4，拒绝前导零、越界值和配置注入。
is_ipv4() {
	value=$1
	old_ifs=$IFS
	IFS=.
	set -- $value
	IFS=$old_ifs
	[ "$#" -eq 4 ] || return 1
	for octet in "$@"; do
		case "$octet" in
			0|[1-9]|[1-9][0-9]|[1-9][0-9][0-9]) ;;
			*) return 1 ;;
		esac
		[ "$octet" -le 255 ] || return 1
	done
}

# 过滤回环、链路本地、基准测试和组播地址，仅保留宿主机可访问地址。
is_usable_host_ipv4() {
	is_ipv4 "$1" || return 1
	first=${1%%.*}
	rest=${1#*.}
	second=${rest%%.*}
	[ "$first" -ne 0 ] || return 1
	[ "$first" -ne 127 ] || return 1
	if [ "$first" -eq 169 ] && [ "$second" -eq 254 ]; then
		return 1
	fi
	if [ "$first" -eq 198 ] && { [ "$second" -eq 18 ] || [ "$second" -eq 19 ]; }; then
		return 1
	fi
	[ "$first" -lt 224 ] || return 1
}

# 识别 macOS 默认路由网卡上的可用 IPv4。
detect_macos_ipv4() {
	interface=$(route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}')
	[ -n "$interface" ] || return 1
	ifconfig "$interface" 2>/dev/null | awk '$1 == "inet" {print $2}'
}

# 识别 Linux 默认路由网卡上的全局 IPv4。
detect_linux_ipv4() {
	interface=$(ip -4 route show default 2>/dev/null | awk '{for (index = 1; index <= NF; index++) if ($index == "dev") {print $(index + 1); exit}}')
	[ -n "$interface" ] || return 1
	ip -o -4 addr show dev "$interface" scope global 2>/dev/null \
		| awk '{split($4, address, "/"); print address[1]}'
}

# 检测当前默认网卡地址，并以稳定顺序去重输出。
detect_host_ips() {
	case "$(uname -s)" in
		Darwin) candidates=$(detect_macos_ipv4 || true) ;;
		Linux) candidates=$(detect_linux_ipv4 || true) ;;
		*) fail "only macOS and Linux host discovery are supported" ;;
	esac
	result=''
	for candidate in $candidates; do
		is_usable_host_ipv4 "$candidate" || continue
		case " $result " in
			*" $candidate "*) ;;
			*) result="${result:+$result }$candidate" ;;
		esac
	done
	[ -n "$result" ] || return 1
	for address in $result; do
		printf '%s\n' "$address"
	done
}

# 创建可被非 root Caddy 容器读取的运行时目录。
prepare_runtime_directory() {
	mkdir -p "$RUNTIME_DIR"
	chmod 0755 "$RUNTIME_DIR"
}

# 从本地环境文件读取跟踪间隔，不 source 或输出其中的敏感值。
tracking_interval() {
	value=${HOST_IP_CHECK_INTERVAL_SECONDS:-}
	if [ -z "$value" ] && [ -f "$PROJECT_DIR/.env" ]; then
		value=$(awk -F= '$1 == "HOST_IP_CHECK_INTERVAL_SECONDS" {value=substr($0, index($0, "=") + 1)} END {print value}' "$PROJECT_DIR/.env")
	fi
	value=${value:-60}
	case "$value" in
		''|*[!0-9]*) fail "HOST_IP_CHECK_INTERVAL_SECONDS must be an integer" ;;
	esac
	[ "$value" -ge 5 ] && [ "$value" -le 3600 ] \
		|| fail "HOST_IP_CHECK_INTERVAL_SECONDS must be between 5 and 3600"
	printf '%s\n' "$value"
}

# 原子刷新宿主机地址文件，未变化时保留原文件时间戳。
refresh_host_ips() {
	prepare_runtime_directory
	temporary_file=$RUNTIME_DIR/host-ips.tmp.$$
	if ! detect_host_ips > "$temporary_file"; then
		rm -f "$temporary_file"
		return 2
	fi
	chmod 0644 "$temporary_file"
	if [ -f "$HOSTS_FILE" ] && cmp -s "$temporary_file" "$HOSTS_FILE"; then
		rm -f "$temporary_file"
		return 1
	fi
	mv -f "$temporary_file" "$HOSTS_FILE"
	return 0
}

# 判断 PID 文件对应的跟踪器是否仍在运行。
tracker_running() {
	[ -f "$TRACKER_PID_FILE" ] || return 1
	pid=$(sed -n '1p' "$TRACKER_PID_FILE")
	case "$pid" in
		''|*[!0-9]*) return 1 ;;
	esac
	kill -0 "$pid" 2>/dev/null
}

# 前台轮询默认网卡，地址变化由 Caddy 容器自行检测并热加载。
watch_host_ips() {
	interval=$(tracking_interval)
	trap 'rm -f "$TRACKER_PID_FILE"; exit 0' INT TERM EXIT
	while :; do
		if refresh_host_ips; then
			echo "event=host_ip_change status=updated"
		else
			status=$?
			if [ "$status" -eq 2 ]; then
				echo "event=host_ip_detection status=failed retaining_previous=true" >&2
			fi
		fi
		sleep "$interval"
	done
}

# 在宿主机后台启动唯一跟踪器，使容器重建后仍能复用。
start_tracker() {
	prepare_runtime_directory
	if tracker_running; then
		return 0
	fi
	rm -f "$TRACKER_PID_FILE"
	if refresh_host_ips; then
		:
	else
		status=$?
		if [ "$status" -eq 2 ]; then
			echo "warning: no usable default-interface IPv4 was detected; loopback HTTPS remains available" >&2
		fi
	fi
	nohup /bin/sh "$SCRIPT_DIR/base-ai.sh" __watch >> "$TRACKER_LOG_FILE" 2>&1 &
	pid=$!
	printf '%s\n' "$pid" > "$TRACKER_PID_FILE"
	chmod 0644 "$TRACKER_PID_FILE" "$TRACKER_LOG_FILE" 2>/dev/null || true
}

# 停止当前项目的宿主机跟踪器并清理过期 PID。
stop_tracker() {
	if tracker_running; then
		pid=$(sed -n '1p' "$TRACKER_PID_FILE")
		kill "$pid" 2>/dev/null || true
	fi
	rm -f "$TRACKER_PID_FILE"
}

# 代理 Docker Compose 命令，up/start/restart 自动跟踪，down/stop 后自动停止。
run_compose() {
	command_name=${1:-}
	case "$command_name" in
		up|start|restart) start_tracker ;;
	esac
	set +e
	(cd "$PROJECT_DIR" && docker compose "$@")
	status=$?
	set -e
	case "$command_name" in
		down|stop) stop_tracker ;;
	esac
	return "$status"
}

case "${1:-}" in
	detect)
		detect_host_ips
		;;
	refresh)
		refresh_host_ips || [ "$?" -eq 1 ]
		;;
	tracker-start)
		start_tracker
		;;
	tracker-stop)
		stop_tracker
		;;
	tracker-status)
		tracker_running
		;;
	__watch)
		watch_host_ips
		;;
	'')
		fail "pass Docker Compose arguments, for example: up --build -d"
		;;
	*)
		run_compose "$@"
		;;
esac
