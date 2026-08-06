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

# 读取宿主机跟踪器原子写入的 IPv4 列表，拒绝损坏或注入内容。
append_discovered_hosts() {
	hosts_file=${CADDY_DISCOVERED_HOSTS_FILE:-/run/base-ai/host-ips}
	[ -e "$hosts_file" ] || return 0
	[ -f "$hosts_file" ] && [ -r "$hosts_file" ] \
		|| fail "discovered host address file must be a readable regular file"
	while IFS= read -r discovered_host || [ -n "$discovered_host" ]; do
		[ -n "$discovered_host" ] || continue
		is_ipv4 "$discovered_host" \
			|| fail "discovered host address must be a valid IPv4 address"
		append_host "$discovered_host"
	done < "$hosts_file"
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
	elif [ "$domain_count" -ne 0 ]; then
		fail "APP_DOMAIN, TLS_CERT_FILE, and TLS_KEY_FILE must be configured together"
	else
		CADDY_INGRESS_MODE=ip
		CADDY_ALLOWED_HOSTS=''
		CADDY_HTTPS_SITE_ADDRESSES=''
		append_host localhost
		append_host 127.0.0.1
		append_discovered_hosts
		CADDY_TLS_DIRECTIVE='tls /data/base-ai-tls/ip-fullchain.pem /data/base-ai-tls/ip-privkey.pem'
		CADDY_ADMIN_OPTION='admin 127.0.0.1:2019'
		CADDY_DEFAULT_SNI_OPTION="default_sni ${CADDY_ALLOWED_HOSTS%% *}"
	fi
	export CADDY_INGRESS_MODE CADDY_ALLOWED_HOSTS CADDY_HTTPS_SITE_ADDRESSES CADDY_TLS_DIRECTIVE
	export CADDY_ADMIN_OPTION CADDY_DEFAULT_SNI_OPTION
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

# 按间隔重读宿主机 IP 文件，地址、签发者或有效期变化后热加载。
renew_ip_certificate_forever() {
	check_interval=${CADDY_IP_CHECK_INTERVAL_SECONDS:-60}
	case "$check_interval" in
		''|*[!0-9]*) fail "HOST_IP_CHECK_INTERVAL_SECONDS must be an integer" ;;
	esac
	[ "$check_interval" -ge 5 ] && [ "$check_interval" -le 3600 ] \
		|| fail "HOST_IP_CHECK_INTERVAL_SECONDS must be between 5 and 3600"
	while sleep "$check_interval"; do
		previous_hosts=$CADDY_ALLOWED_HOSTS
		configure_ingress
		if result=$(/usr/local/bin/base-ai-ip-cert --hosts "$CADDY_ALLOWED_HOSTS"); then
			if [ "$result" = renewed ] || [ "$CADDY_ALLOWED_HOSTS" != "$previous_hosts" ]; then
				if /usr/bin/caddy reload --force --config /etc/caddy/Caddyfile --adapter caddyfile --address 127.0.0.1:2019; then
					echo "event=ip_certificate_reload status=success hosts=$CADDY_ALLOWED_HOSTS"
				else
					echo "event=ip_certificate_reload status=failed hosts=$CADDY_ALLOWED_HOSTS" >&2
				fi
			fi
		else
			echo "event=ip_certificate_renewal status=failed hosts=$CADDY_ALLOWED_HOSTS" >&2
		fi
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

if [ "$CADDY_INGRESS_MODE" = ip ] && [ "${1:-}" = run ]; then
	renew_ip_certificate_forever &
fi

exec /usr/bin/caddy "$@"
