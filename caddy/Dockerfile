ARG GO_IMAGE=golang:1.26.5-alpine@sha256:0178a641fbb4858c5f1b48e34bdaabe0350a330a1b1149aabd498d0699ff5fb2
ARG CADDY_IMAGE=caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648

FROM ${GO_IMAGE} AS build
ARG CADDY_VERSION=v2.11.4
WORKDIR /workspace
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go mod init base-ai/caddy-build \
    && go get github.com/caddyserver/caddy/v2/cmd/caddy@${CADDY_VERSION} \
    && go get golang.org/x/text@v0.39.0 google.golang.org/grpc@v1.82.1 \
    && CGO_ENABLED=0 go build -trimpath \
        -ldflags="-s -w -X github.com/caddyserver/caddy/v2.CustomVersion=${CADDY_VERSION}" \
        -o /out/caddy github.com/caddyserver/caddy/v2/cmd/caddy

FROM ${CADDY_IMAGE}

COPY --from=build /out/caddy /usr/bin/caddy
RUN apk upgrade --no-cache \
    && setcap cap_net_bind_service=+ep /usr/bin/caddy \
    && addgroup -S -g 10001 appuser \
    && adduser -S -D -H -u 10001 -G appuser appuser \
    && chown -R appuser:appuser /data /config

USER appuser
