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

# 判断 IPv4 是否可用于服务证书，拒绝未指定、链路本地、组播和受限广播地址。
is_usable_ipv4() {
	is_ipv4 "$1" || return 1
	case "$1" in
		0.0.0.0|169.254.*|22[4-9].*|23[0-9].*|255.255.255.255) return 1 ;;
	esac
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

# 追加一个内部 CA 覆盖的去重 IPv4，并限制证书 SAN 总量。
append_ip_host() {
	host=$1
	source=$2
	is_usable_ipv4 "$host" || fail "$source must contain canonical usable IPv4 addresses"
	case " ${CADDY_IP_HOSTS:-} " in
		*" $host "*) return 0 ;;
	esac
	if [ "$host" != 127.0.0.1 ]; then
		CADDY_IP_HOST_COUNT=$((CADDY_IP_HOST_COUNT + 1))
		[ "$CADDY_IP_HOST_COUNT" -le 256 ] \
			|| fail "configured and learned HTTPS IP address limit exceeded"
	fi
	if [ -n "${CADDY_IP_HOSTS:-}" ]; then
		CADDY_IP_HOSTS="$CADDY_IP_HOSTS $host"
	else
		CADDY_IP_HOSTS=$host
	fi
	append_host "$host"
}

# 解析逗号或空白分隔的预配置 IPv4，生成供续期服务复用的规范列表。
append_configured_ips() {
	configured_value=$(printf '%s' "${APP_HTTPS_IPS:-}" | tr ',\t\r\n' '    ')
	for configured_ip in $configured_value; do
		append_ip_host "$configured_ip" APP_HTTPS_IPS
		if [ "$configured_ip" != 127.0.0.1 ]; then
			case " ${CADDY_CONFIGURED_IPS:-} " in
				*" $configured_ip "*) ;;
				*) CADDY_CONFIGURED_IPS="${CADDY_CONFIGURED_IPS:+$CADDY_CONFIGURED_IPS }$configured_ip" ;;
			esac
		fi
	done
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
		is_usable_ipv4 "$learned_host" \
			|| fail "learned host address must be a canonical usable IPv4 address"
		count=$((count + 1))
		[ "$count" -le "$CADDY_IP_MAX_LEARNED_HOSTS" ] \
			|| fail "learned host address limit exceeded"
		append_ip_host "$learned_host" "learned host address"
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

# 根据完整的 YAML HTTPS 站点配置或 IP 配置选择唯一入口模式。
configure_ingress() {
	https_sites_file=${APP_HTTPS_SITES_FILE:-}
	tls_certs_dir=${TLS_CERTS_DIR:-}
	domain_config_count=0
	is_set "$https_sites_file" && domain_config_count=$((domain_config_count + 1))
	is_set "$tls_certs_dir" && domain_config_count=$((domain_config_count + 1))

	CADDY_ALLOWED_HOSTS=''
	CADDY_HTTPS_SITE_ADDRESSES=''
	CADDY_DOMAIN_HOSTS=''
	CADDY_IP_HOSTS=''
	CADDY_CONFIGURED_IPS=''
	CADDY_IP_HOST_COUNT=0
	CADDY_INGRESS_MODE=ip
	if [ "$domain_config_count" -eq 2 ]; then
		mounted_sites_file=${CADDY_HTTPS_SITES_FILE:-/etc/caddy/https-sites.yml}
		mounted_tls_root=${CADDY_TLS_ROOT:-/etc/caddy/tls}
		ingress_helper=${CADDY_INGRESS_HELPER:-/usr/local/bin/base-ai-ip-cert}
		[ -f "$mounted_sites_file" ] && [ -r "$mounted_sites_file" ] \
			|| fail "APP_HTTPS_SITES_FILE must reference a readable regular YAML file"
		[ -d "$mounted_tls_root" ] && [ -r "$mounted_tls_root" ] \
			|| fail "TLS_CERTS_DIR must reference a readable directory"
		resolved_domains=$("$ingress_helper" \
			--prepare-https-sites "$mounted_sites_file" \
			--tls-root "$mounted_tls_root") \
			|| fail "APP_HTTPS_SITES_FILE contains an invalid HTTPS sites configuration"
		[ -n "$resolved_domains" ] || fail "APP_HTTPS_SITES_FILE must contain at least one domain"
		CADDY_INGRESS_MODE=mixed
		for domain in $resolved_domains; do
			append_host "$domain"
			CADDY_DOMAIN_HOSTS="${CADDY_DOMAIN_HOSTS:+$CADDY_DOMAIN_HOSTS }$domain"
		done
	elif [ "$domain_config_count" -ne 0 ]; then
		fail "APP_HTTPS_SITES_FILE and TLS_CERTS_DIR must be configured together"
	fi
	append_host localhost
	append_ip_host 127.0.0.1 "fixed loopback address"
	append_configured_ips
	append_learned_hosts
	CADDY_TLS_DIRECTIVE='tls {
		load /tmp/base-ai-https-tls
	}'
	CADDY_ADMIN_OPTION='admin 127.0.0.1:2019'
	CADDY_DEFAULT_SNI_OPTION='default_sni localhost'
	CADDY_HTTP_FALLBACK='reverse_proxy 127.0.0.1:2020'
	export CADDY_INGRESS_MODE CADDY_ALLOWED_HOSTS CADDY_HTTPS_SITE_ADDRESSES CADDY_TLS_DIRECTIVE
	export CADDY_ADMIN_OPTION CADDY_DEFAULT_SNI_OPTION CADDY_HTTP_FALLBACK
	export CADDY_DOMAIN_HOSTS CADDY_IP_HOSTS CADDY_CONFIGURED_IPS
}

# 每次启动时初始化 Caddy CA，并签发包含预配置和已学习地址的全部 IP SAN。
ensure_ip_certificate() {
	/usr/bin/caddy validate --config /etc/caddy/Caddyfile.bootstrap --adapter caddyfile >/dev/null \
		|| fail "Caddy internal CA bootstrap failed"
	if [ "$CADDY_INGRESS_MODE" = ip ]; then
		rm -rf /tmp/base-ai-https-tls
	fi
	result=$(/usr/local/bin/base-ai-ip-cert --hosts "localhost $CADDY_IP_HOSTS") \
		|| fail "initial multi-SAN IP certificate issuance failed"
	echo "event=ip_certificate status=$result hosts=localhost $CADDY_IP_HOSTS"
}

# 启动容器内请求驱动签发服务；异常退出后重启，不读取任何宿主机网络信息。
serve_ip_bootstrap_forever() {
	while :; do
		if /usr/local/bin/base-ai-ip-cert \
			--serve \
			--mode "$CADDY_INGRESS_MODE" \
			--configured-ips "$CADDY_CONFIGURED_IPS" \
			--additional-hosts "$CADDY_DOMAIN_HOSTS" \
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
	certificate_files='/data/base-ai-tls/ip-fullchain.pem /data/base-ai-tls/ip-privkey.pem /tmp/base-ai-https-tls/*.pem'
	for file in $certificate_files; do
		[ -f "$file" ] && [ -s "$file" ] && [ -r "$file" ] \
			|| fail "TLS certificate files must be non-empty and readable by container UID 10001"
	done
}

# 诊断模式只输出非敏感的解析结果，供部署契约测试和运维排查使用。
print_resolved_config() {
	echo "mode=$CADDY_INGRESS_MODE"
	echo "domains=$CADDY_DOMAIN_HOSTS"
	echo "configured_ips=$CADDY_CONFIGURED_IPS"
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
