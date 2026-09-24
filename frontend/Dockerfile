ARG NODE_IMAGE=node:24-alpine@sha256:e67514e5d0f6c46656005e1b693b2ec9d52e80b641307de684d4a015ba7a4eaf
FROM ${NODE_IMAGE} AS build
WORKDIR /workspace
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# 构建设备 Agent 的自包含运行时与安装包，避免依赖独立的入口代理镜像。
FROM ${NODE_IMAGE} AS agent-runtime
ARG ALPINE_MIRROR=https://mirrors.tuna.tsinghua.edu.cn/alpine
ARG AGENT_RUNTIME_BUNDLE=true
ARG AGENT_PYTHON_VERSION=3.12.8
ARG AGENT_PYTHON_TAG=20250106
ARG AGENT_PYTHON_BASE_URL=https://github.com/astral-sh/python-build-standalone/releases/download
ARG AGENT_NODE_VERSION=22.22.0
ARG AGENT_NODE_BASE_URL=https://nodejs.org/dist
ARG AGENT_APPIUM_SPEC=appium@3.7.0
ARG AGENT_XCUITEST_SPEC=xcuitest@12.11.1
# 镜像源不可用时回退官方源，保证不同网络和 CPU 架构都能完成构建。
RUN original_repositories="$(cat /etc/apk/repositories)"; \
    sed -i "s#https://dl-cdn.alpinelinux.org/alpine#${ALPINE_MIRROR}#g" /etc/apk/repositories; \
    if ! apk add --no-cache curl; then \
      printf '%s\n' "$original_repositories" > /etc/apk/repositories; \
      apk add --no-cache curl; \
    fi
RUN --mount=type=cache,target=/agent-runtime-cache,sharing=locked set -eu; \
    mkdir -p /runtime; \
    if [ "${AGENT_RUNTIME_BUNDLE}" != "true" ]; then \
      echo "AGENT_RUNTIME_BUNDLED=false" > /runtime/manifest.env; exit 0; \
    fi; \
    arm="cpython-${AGENT_PYTHON_VERSION}+${AGENT_PYTHON_TAG}-aarch64-apple-darwin-install_only.tar.gz"; \
    x86="cpython-${AGENT_PYTHON_VERSION}+${AGENT_PYTHON_TAG}-x86_64-apple-darwin-install_only.tar.gz"; \
    fetch() { \
      file="$1"; escaped="$(printf '%s' "$file" | sed 's/+/%2B/g')"; \
      checksum="$(curl -fsSL --connect-timeout 15 --retry 3 \
        "${AGENT_PYTHON_BASE_URL}/${AGENT_PYTHON_TAG}/${escaped}.sha256" | tr -d '[:space:]')"; \
      [ "${#checksum}" -eq 64 ] || { echo "Invalid checksum for $file" >&2; exit 1; }; \
      cached="/agent-runtime-cache/$file"; \
      if [ ! -f "$cached" ] || [ "$(sha256sum "$cached" | cut -d' ' -f1)" != "$checksum" ]; then \
        curl -fsSL --connect-timeout 15 --retry 3 -o "$cached" \
          "${AGENT_PYTHON_BASE_URL}/${AGENT_PYTHON_TAG}/${escaped}"; \
      fi; \
      [ "$(sha256sum "$cached" | cut -d' ' -f1)" = "$checksum" ] || { echo "Checksum mismatch for $file" >&2; exit 1; }; \
      cp "$cached" "/runtime/$file"; printf '%s' "$checksum"; \
    }; \
    arm_sha="$(fetch "$arm")"; x86_sha="$(fetch "$x86")"; \
    node_checksums="$(curl -fsSL --connect-timeout 15 --retry 3 \
      "${AGENT_NODE_BASE_URL}/v${AGENT_NODE_VERSION}/SHASUMS256.txt")"; \
    fetch_node() { \
      file="$1"; checksum="$(printf '%s\n' "$node_checksums" | awk -v file="$file" '$2 == file {print $1}')"; \
      [ "${#checksum}" -eq 64 ] || { echo "Invalid Node checksum for $file" >&2; exit 1; }; \
      cached="/agent-runtime-cache/$file"; \
      if [ ! -f "$cached" ] || [ "$(sha256sum "$cached" | cut -d' ' -f1)" != "$checksum" ]; then \
        curl -fsSL --connect-timeout 15 --retry 3 -o "$cached" \
          "${AGENT_NODE_BASE_URL}/v${AGENT_NODE_VERSION}/$file"; \
      fi; \
      [ "$(sha256sum "$cached" | cut -d' ' -f1)" = "$checksum" ] || { echo "Checksum mismatch for $file" >&2; exit 1; }; \
      cp "$cached" "/runtime/$file"; printf '%s' "$checksum"; \
    }; \
    node_arm="node-v${AGENT_NODE_VERSION}-darwin-arm64.tar.gz"; \
    node_x86="node-v${AGENT_NODE_VERSION}-darwin-x64.tar.gz"; \
    node_arm_sha="$(fetch_node "$node_arm")"; node_x86_sha="$(fetch_node "$node_x86")"; \
    { echo "AGENT_RUNTIME_BUNDLED=true"; echo "PYTHON_VERSION=${AGENT_PYTHON_VERSION}"; \
      echo "PYTHON_ARM64_FILE=$arm"; echo "PYTHON_ARM64_SHA256=$arm_sha"; \
      echo "PYTHON_X86_64_FILE=$x86"; echo "PYTHON_X86_64_SHA256=$x86_sha"; \
      echo "NODE_VERSION=${AGENT_NODE_VERSION}"; echo "NODE_ARM64_FILE=$node_arm"; \
      echo "NODE_ARM64_SHA256=$node_arm_sha"; echo "NODE_X86_64_FILE=$node_x86"; \
      echo "NODE_X86_64_SHA256=$node_x86_sha"; echo "APPIUM_SPEC=${AGENT_APPIUM_SPEC}"; \
      echo "XCUITEST_SPEC=${AGENT_XCUITEST_SPEC}"; } > /runtime/manifest.env

# 仅打包通用设备 Agent 文件，不把测试和缓存带入运行镜像。
FROM ${NODE_IMAGE} AS agent-dist
COPY --from=agent-src / /agent-src/
COPY --from=agent-runtime /runtime /dist/runtime
RUN set -eu; mkdir -p /stage /dist; \
    cp -R /agent-src/device_agent /agent-src/launchd /stage/; \
    cp /agent-src/pyproject.toml /agent-src/requirements.in /agent-src/install.sh /stage/; \
    chmod 0555 /stage/install.sh; \
    code_hash="$(cd /stage && find . -type f -exec sha256sum {} + | sort | sha256sum | cut -c1-12)"; \
    code_version="$(date -u +%Y%m%d.%H%M)+${code_hash}"; \
    tar -C /stage -czf /dist/device-agent.tar.gz .; \
    cp /agent-src/bootstrap.sh /dist/; \
    { echo "AGENT_CODE_VERSION=${code_version}"; \
      echo "AGENT_PACKAGE_SHA256=$(sha256sum /dist/device-agent.tar.gz | cut -d' ' -f1)"; } >> /dist/runtime/manifest.env; \
    cp /dist/runtime/manifest.env /dist/manifest.env

FROM ${NODE_IMAGE}
ARG APP_IMAGE_REVISION
LABEL org.opencontainers.image.revision=${APP_IMAGE_REVISION}
WORKDIR /app
ENV PORT=8080
ENV BACKEND_URL=http://backend:8080
RUN apk upgrade --no-cache \
    && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /opt/yarn-v* \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
        /usr/local/bin/yarn /usr/local/bin/yarnpkg
COPY --chown=node:node server.mjs ./
COPY --chown=node:node --from=build /workspace/dist ./dist
COPY --chown=node:node --from=agent-dist /dist ./agent-dist
USER node
EXPOSE 8080
CMD ["node", "server.mjs"]
