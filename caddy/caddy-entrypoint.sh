#!/bin/sh
set -eu

# 输出错误并终止启动，避免 TLS 配置不完整时静默降级。
fail() {
	echo "caddy ingress configuration error: $*" >&2
	exit 1
}

# 判断环境变量是否包含非空值。
is_set() {
	[ -n "$1" ]
}

# 校验单个 IPv4 地址，拒绝前导零、越界值和配置注入字符。
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

# 校验单个 DNS 域名，仅接受不含协议、端口和通配符的主机名。
is_domain() {
	domain=$1
	[ "${#domain}" -le 253 ] || return 1
	case "$domain" in
		''|.*|*.|*..*|*[!A-Za-z0-9.-]*) return 1 ;;
	esac
	old_ifs=$IFS
	IFS=.
	set -- $domain
	IFS=$old_ifs
	for label in "$@"; do
		[ "${#label}" -le 63 ] || return 1
		case "$label" in
			''|-*|*-) return 1 ;;
		esac
	done
}

# 校验宿主机发布端口并生成 HTTPS 跳转端口后缀。
configure_ports() {
	http_port=${CADDY_EXTERNAL_HTTP_PORT:-81}
	https_port=${CADDY_EXTERNAL_HTTPS_PORT:-444}
	for port in "$http_port" "$https_port"; do
		case "$port" in
			''|*[!0-9]*) fail "HTTP_PORT and HTTPS_PORT must be integers" ;;
		esac
		[ "$port" -ge 1 ] && [ "$port" -le 65535 ] \
			|| fail "HTTP_PORT and HTTPS_PORT must be between 1 and 65535"
	done
	if [ "$https_port" -eq 443 ]; then
		CADDY_HTTPS_PORT_SUFFIX=''
	else
		CADDY_HTTPS_PORT_SUFFIX=:$https_port
	fi
	if [ "$http_port" -eq 80 ] && [ "$https_port" -eq 443 ]; then
		CADDY_HSTS_HEADER='Strict-Transport-Security "max-age=31536000"'
	else
		CADDY_HSTS_HEADER=''
	fi
	export CADDY_HTTPS_PORT_SUFFIX CADDY_HSTS_HEADER
}

# 向地址列表追加一个去重主机，并同步生成 HTTP、HTTPS 和 Host 匹配值。
append_host() {
	host=$1
	case " ${CADDY_ALLOWED_HOSTS:-} " in
		*" $host "*) return 0 ;;
	esac
	if [ -n "${CADDY_ALLOWED_HOSTS:-}" ]; then
		CADDY_ALLOWED_HOSTS="$CADDY_ALLOWED_HOSTS $host"
		CADDY_HTTPS_SITE_ADDRESSES="$CADDY_HTTPS_SITE_ADDRESSES, https://$host"
	else
		CADDY_ALLOWED_HOSTS=$host
		CADDY_HTTPS_SITE_ADDRESSES=https://$host
	fi
}

# 读取容器数据卷中请求驱动学习的 IPv4，拒绝损坏、超限或注入内容。
append_learned_hosts() {
	hosts_file=${CADDY_LEARNED_HOSTS_FILE:-/data/base-ai-tls/learned-hosts}
	[ -e "$hosts_file" ] || return 0
	[ -f "$hosts_file" ] && [ -r "$hosts_file" ] \
		|| fail "learned host address file must be a readable regular file"
	count=0
	while IFS= read -r learned_host || [ -n "$learned_host" ]; do
		[ -n "$learned_host" ] || continue
		is_ipv4 "$learned_host" \
			|| fail "learned host address must be a valid IPv4 address"
		count=$((count + 1))
		[ "$count" -le "$CADDY_IP_MAX_LEARNED_HOSTS" ] \
			|| fail "learned host address limit exceeded"
		append_host "$learned_host"
	done < "$hosts_file"
}

# 校验证书检查、签发冷却和地址数量限制，避免错误配置绕过资源保护。
configure_ip_bootstrap_limits() {
	CADDY_CERT_CHECK_INTERVAL_SECONDS=${CADDY_CERT_CHECK_INTERVAL_SECONDS:-3600}
	CADDY_IP_MIN_ISSUE_INTERVAL_SECONDS=${CADDY_IP_MIN_ISSUE_INTERVAL_SECONDS:-5}
	CADDY_IP_MAX_LEARNED_HOSTS=${CADDY_IP_MAX_LEARNED_HOSTS:-32}
	for value in "$CADDY_CERT_CHECK_INTERVAL_SECONDS" "$CADDY_IP_MIN_ISSUE_INTERVAL_SECONDS" "$CADDY_IP_MAX_LEARNED_HOSTS"; do
		case "$value" in
			''|*[!0-9]*) fail "IP bootstrap limits must be integers" ;;
		esac
	done
	[ "$CADDY_CERT_CHECK_INTERVAL_SECONDS" -ge 60 ] && [ "$CADDY_CERT_CHECK_INTERVAL_SECONDS" -le 86400 ] \
		|| fail "TLS_CERT_CHECK_INTERVAL_SECONDS must be between 60 and 86400"
	[ "$CADDY_IP_MIN_ISSUE_INTERVAL_SECONDS" -le 3600 ] \
		|| fail "IP_CERT_MIN_ISSUE_INTERVAL_SECONDS must be between 0 and 3600"
	[ "$CADDY_IP_MAX_LEARNED_HOSTS" -ge 1 ] && [ "$CADDY_IP_MAX_LEARNED_HOSTS" -le 256 ] \
		|| fail "IP_CERT_MAX_LEARNED_HOSTS must be between 1 and 256"
	export CADDY_CERT_CHECK_INTERVAL_SECONDS CADDY_IP_MIN_ISSUE_INTERVAL_SECONDS CADDY_IP_MAX_LEARNED_HOSTS
}

# 根据完整的域名证书配置或 IP 配置选择唯一入口模式。
configure_ingress() {
	domain=${APP_DOMAIN:-}
	cert_file=${TLS_CERT_FILE:-}
	key_file=${TLS_KEY_FILE:-}
	domain_count=0
	is_set "$domain" && domain_count=$((domain_count + 1))
	is_set "$cert_file" && domain_count=$((domain_count + 1))
	is_set "$key_file" && domain_count=$((domain_count + 1))

	if [ "$domain_count" -eq 3 ]; then
		is_domain "$domain" || fail "APP_DOMAIN must be a single valid DNS name without scheme or port"
		CADDY_INGRESS_MODE=domain
		CADDY_ALLOWED_HOSTS=$domain
		CADDY_HTTPS_SITE_ADDRESSES=https://$domain
		CADDY_TLS_DIRECTIVE='tls /etc/caddy/tls/fullchain.pem /etc/caddy/tls/privkey.pem'
		CADDY_ADMIN_OPTION='admin off'
		CADDY_DEFAULT_SNI_OPTION=''
		CADDY_HTTP_FALLBACK='respond "Not Found" 404'
	elif [ "$domain_count" -ne 0 ]; then
		fail "APP_DOMAIN, TLS_CERT_FILE, and TLS_KEY_FILE must be configured together"
	else
		CADDY_INGRESS_MODE=ip
		CADDY_ALLOWED_HOSTS=''
		CADDY_HTTPS_SITE_ADDRESSES=''
		append_host localhost
		append_host 127.0.0.1
		append_learned_hosts
		CADDY_TLS_DIRECTIVE='tls /data/base-ai-tls/ip-fullchain.pem /data/base-ai-tls/ip-privkey.pem'
		CADDY_ADMIN_OPTION='admin 127.0.0.1:2019'
		CADDY_DEFAULT_SNI_OPTION="default_sni ${CADDY_ALLOWED_HOSTS%% *}"
		CADDY_HTTP_FALLBACK='reverse_proxy 127.0.0.1:2020'
	fi
	export CADDY_INGRESS_MODE CADDY_ALLOWED_HOSTS CADDY_HTTPS_SITE_ADDRESSES CADDY_TLS_DIRECTIVE
	export CADDY_ADMIN_OPTION CADDY_DEFAULT_SNI_OPTION CADDY_HTTP_FALLBACK
}

# 首次 IP 模式启动时初始化 Caddy CA 并签发包含全部 IP SAN 的证书。
ensure_ip_certificate() {
	[ "$CADDY_INGRESS_MODE" = ip ] || return 0
	/usr/bin/caddy validate --config /etc/caddy/Caddyfile.bootstrap --adapter caddyfile >/dev/null \
		|| fail "Caddy internal CA bootstrap failed"
	result=$(/usr/local/bin/base-ai-ip-cert --hosts "$CADDY_ALLOWED_HOSTS") \
		|| fail "initial multi-SAN IP certificate issuance failed"
	echo "event=ip_certificate status=$result hosts=$CADDY_ALLOWED_HOSTS"
}

# 启动容器内请求驱动签发服务；异常退出后重启，不读取任何宿主机网络信息。
serve_ip_bootstrap_forever() {
	while :; do
		if /usr/local/bin/base-ai-ip-cert \
			--serve \
			--mode "$CADDY_INGRESS_MODE" \
			--state-file "${CADDY_LEARNED_HOSTS_FILE:-/data/base-ai-tls/learned-hosts}" \
			--https-port-suffix "$CADDY_HTTPS_PORT_SUFFIX" \
			--check-interval "${CADDY_CERT_CHECK_INTERVAL_SECONDS}s" \
			--min-issue-interval "${CADDY_IP_MIN_ISSUE_INTERVAL_SECONDS}s" \
			--max-learned-hosts "$CADDY_IP_MAX_LEARNED_HOSTS"; then
			echo "event=ip_bootstrap status=stopped" >&2
		else
			echo "event=ip_bootstrap status=crashed retrying=true" >&2
		fi
		sleep 1
	done
}

# 启动前确认非 root Caddy 进程能够读取当前模式所需的有效证书文件。
validate_certificate_files() {
	if [ "$CADDY_INGRESS_MODE" = domain ]; then
		certificate_files='/etc/caddy/tls/fullchain.pem /etc/caddy/tls/privkey.pem'
	else
		certificate_files='/data/base-ai-tls/ip-fullchain.pem /data/base-ai-tls/ip-privkey.pem'
	fi
	for file in $certificate_files; do
		[ -f "$file" ] && [ -s "$file" ] && [ -r "$file" ] \
			|| fail "TLS certificate files must be non-empty and readable by container UID 10001"
	done
}

# 诊断模式只输出非敏感的解析结果，供部署契约测试和运维排查使用。
print_resolved_config() {
	echo "mode=$CADDY_INGRESS_MODE"
	echo "hosts=$CADDY_ALLOWED_HOSTS"
	echo "https_sites=$CADDY_HTTPS_SITE_ADDRESSES"
	echo "https_port_suffix=$CADDY_HTTPS_PORT_SUFFIX"
	echo "default_sni=${CADDY_DEFAULT_SNI_OPTION#default_sni }"
	if [ -n "$CADDY_HSTS_HEADER" ]; then
		echo "hsts=enabled"
	else
		echo "hsts=disabled"
	fi
}

configure_ports
configure_ip_bootstrap_limits
configure_ingress

if [ "${1:-}" = "--resolve-ingress" ]; then
	print_resolved_config
	exit 0
fi

ensure_ip_certificate
validate_certificate_files

if [ "${1:-}" = "--reload-ingress" ]; then
	exec /usr/bin/caddy reload --force --config /etc/caddy/Caddyfile --adapter caddyfile --address 127.0.0.1:2019
fi

if [ "${1:-}" = run ]; then
	serve_ip_bootstrap_forever &
fi

exec /usr/bin/caddy "$@"
