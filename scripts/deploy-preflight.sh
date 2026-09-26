#!/bin/sh
# 从仓库根目录执行；通过 COMPOSE_FILE、COMPOSE_ENV_FILES 和 DOCKER_HOST 选择验收环境。
set -eu

# 宿主检查无需镜像；完整检查复用已构建后端及其 Compose 网络配置。
case "${1:-}" in
  '') mode=full ;;
  --host-only) mode=host ;;
  *) echo 'preflight: usage: sh scripts/deploy-preflight.sh [--host-only]' >&2; exit 2 ;;
esac
[ "$#" -le 1 ] || { echo 'preflight: too many arguments' >&2; exit 2; }
command -v docker >/dev/null 2>&1 || { echo 'preflight: Docker is unavailable' >&2; exit 1; }
security=$(docker info --format '{{json .SecurityOptions}}' 2>/dev/null) || {
  echo 'preflight: Docker daemon is unavailable; start the selected Docker context' >&2; exit 1;
}
case "$security" in
  *'"name=rootless"'*) ;;
  *) echo 'preflight: select a rootless Docker daemon required by the adapter sandbox' >&2; exit 1 ;;
esac
docker compose config --quiet || { echo 'preflight: invalid Compose configuration' >&2; exit 1; }
echo 'preflight: Docker and Compose configuration are ready'
[ "$mode" = full ] || exit 0

# 检查独立进程不启动 Spring、Flyway 或依赖服务；检查结果直接作为脚本退出码。
exec docker compose run --rm --no-deps --entrypoint java backend \
  -Dloader.main=com.baseai.platform.deployment.DeploymentPreflight \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
