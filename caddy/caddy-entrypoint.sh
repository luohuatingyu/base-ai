#!/bin/sh
set -eu

# 仅保留 Caddy 原生启动行为。
exec /usr/bin/caddy "$@"
