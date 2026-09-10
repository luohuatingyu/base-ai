ARG GO_IMAGE=golang:1.26.6-alpine@sha256:3889b425f035be855a72fb4755265311293b6d414521f0a519d819df32222d83
ARG CADDY_IMAGE=caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648

FROM ${GO_IMAGE} AS build
ARG CADDY_VERSION=v2.11.4
ARG GOPROXY=https://goproxy.cn,direct
ENV GOPROXY=${GOPROXY}
WORKDIR /workspace
COPY ip-cert-helper.go ./
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go mod init base-ai/caddy-build \
    && go get github.com/caddyserver/caddy/v2/cmd/caddy@${CADDY_VERSION} \
    && go get gopkg.in/yaml.v3@v3.0.1 \
    && go get golang.org/x/text@v0.39.0 google.golang.org/grpc@v1.82.1 \
    && CGO_ENABLED=0 go build -trimpath \
        -ldflags="-s -w -X github.com/caddyserver/caddy/v2.CustomVersion=${CADDY_VERSION}" \
        -o /out/caddy github.com/caddyserver/caddy/v2/cmd/caddy \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" \
        -o /out/base-ai-ip-cert ip-cert-helper.go

# 构建时下载并校验 macOS 双架构 Python 3.12 自包含运行时。
FROM ${CADDY_IMAGE} AS agent-runtime
ARG ALPINE_MIRROR=https://mirrors.tuna.tsinghua.edu.cn/alpine
ARG AGENT_RUNTIME_BUNDLE=true
ARG AGENT_PYTHON_VERSION=3.12.8
ARG AGENT_PYTHON_TAG=20250106
ARG AGENT_PYTHON_BASE_URL=https://github.com/astral-sh/python-build-standalone/releases/download
RUN sed -i "s#https://dl-cdn.alpinelinux.org/alpine#${ALPINE_MIRROR}#g" /etc/apk/repositories \
    && apk add --no-cache curl
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
      [ "$(sha256sum "$cached" | cut -d' ' -f1)" = "$checksum" ] \
        || { echo "Checksum mismatch for $file" >&2; exit 1; }; \
      cp "$cached" "/runtime/$file"; printf '%s' "$checksum"; \
    }; \
    arm_sha="$(fetch "$arm")"; x86_sha="$(fetch "$x86")"; \
    { echo "AGENT_RUNTIME_BUNDLED=true"; echo "PYTHON_VERSION=${AGENT_PYTHON_VERSION}"; \
      echo "PYTHON_ARM64_FILE=$arm"; echo "PYTHON_ARM64_SHA256=$arm_sha"; \
      echo "PYTHON_X86_64_FILE=$x86"; echo "PYTHON_X86_64_SHA256=$x86_sha"; } \
      > /runtime/manifest.env

# 仅打包通用设备 Agent 的运行文件，不包含测试、缓存或设备自动化组件。
FROM ${CADDY_IMAGE} AS agent-dist
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
      echo "AGENT_PACKAGE_SHA256=$(sha256sum /dist/device-agent.tar.gz | cut -d' ' -f1)"; } \
      >> /dist/runtime/manifest.env; \
    cp /dist/runtime/manifest.env /dist/manifest.env

FROM ${CADDY_IMAGE}
ARG APP_IMAGE_REVISION
LABEL org.opencontainers.image.revision=${APP_IMAGE_REVISION}
ARG ALPINE_MIRROR=https://mirrors.tuna.tsinghua.edu.cn/alpine

COPY --from=build /out/caddy /usr/bin/caddy
COPY --from=agent-dist /dist /agent-dist
COPY --from=build /out/base-ai-ip-cert /usr/local/bin/base-ai-ip-cert
COPY --chmod=0555 caddy-entrypoint.sh /usr/local/bin/base-ai-caddy-entrypoint
COPY --chmod=0444 Caddyfile.bootstrap /etc/caddy/Caddyfile.bootstrap
RUN sed -i "s#https://dl-cdn.alpinelinux.org/alpine#${ALPINE_MIRROR}#g" /etc/apk/repositories \
    && apk upgrade --no-cache \
    && setcap cap_net_bind_service=+ep /usr/bin/caddy \
    && addgroup -S -g 10001 appuser \
    && adduser -S -D -H -u 10001 -G appuser appuser \
    && mkdir -p /public-ca \
    && chown -R appuser:appuser /data /config /public-ca

USER appuser
ENTRYPOINT ["/usr/local/bin/base-ai-caddy-entrypoint"]
CMD ["run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
