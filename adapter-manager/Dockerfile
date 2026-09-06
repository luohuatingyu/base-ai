ARG GO_IMAGE=golang:1.26.6-alpine@sha256:3889b425f035be855a72fb4755265311293b6d414521f0a519d819df32222d83
ARG DOCKER_CLI_VERSION=29.7.2
ARG DOCKER_CLI_SOURCE_SHA256=225b7ab2a15f5230b482df8461069cd4bce38891266fb9898d4188d0a3cbf54a
ARG DOCKER_COMPOSE_VERSION=5.4.0
ARG DOCKER_COMPOSE_LINUX_AMD64_SHA256=837fd1d35bf6a494f41b5b5988269a7be79de337cf1a1a6ff0e45ab51bb4e9be
ARG DOCKER_COMPOSE_LINUX_ARM64_SHA256=fc5d1371f1ec7987e703da94ede49af3fbfb240b83f22991a98511de7bc4b93b

FROM ${GO_IMAGE} AS docker-cli-build
ARG DOCKER_CLI_VERSION
ARG DOCKER_CLI_SOURCE_SHA256
WORKDIR /go/src/github.com/docker/cli
RUN wget -qO /tmp/docker-cli.tar.gz \
        "https://github.com/docker/cli/archive/refs/tags/v${DOCKER_CLI_VERSION}.tar.gz" \
    && echo "${DOCKER_CLI_SOURCE_SHA256}  /tmp/docker-cli.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/docker-cli.tar.gz --strip-components=1 \
    && rm /tmp/docker-cli.tar.gz \
    && GO111MODULE=auto CGO_ENABLED=0 go build -trimpath \
        -tags "osusergo netgo grpcnotrace" \
        -ldflags="-s -w -X github.com/docker/cli/cli/version.Version=${DOCKER_CLI_VERSION}" \
        -o /out/docker ./cmd/docker

FROM ${GO_IMAGE} AS docker-compose-download
ARG DOCKER_COMPOSE_VERSION
ARG DOCKER_COMPOSE_LINUX_AMD64_SHA256
ARG DOCKER_COMPOSE_LINUX_ARM64_SHA256
ARG TARGETARCH
RUN set -eu; \
    case "${TARGETARCH}" in \
        amd64) compose_arch=x86_64; compose_sha256="${DOCKER_COMPOSE_LINUX_AMD64_SHA256}" ;; \
        arm64) compose_arch=aarch64; compose_sha256="${DOCKER_COMPOSE_LINUX_ARM64_SHA256}" ;; \
        *) echo "Unsupported Docker Compose architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    mkdir -p /out; \
    wget -qO /out/docker-compose \
        "https://github.com/docker/compose/releases/download/v${DOCKER_COMPOSE_VERSION}/docker-compose-linux-${compose_arch}"; \
    echo "${compose_sha256}  /out/docker-compose" | sha256sum -c -; \
    chmod 0755 /out/docker-compose

FROM ${GO_IMAGE} AS build
WORKDIR /workspace
COPY go.mod main.go main_test.go ./
RUN go test ./... \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/adapter-manager .

FROM scratch AS manager
ARG APP_IMAGE_REVISION
LABEL org.opencontainers.image.revision=${APP_IMAGE_REVISION}
COPY --from=build /out/adapter-manager /usr/local/bin/adapter-manager
USER 10001:0
EXPOSE 8090
ENTRYPOINT ["/usr/local/bin/adapter-manager"]

FROM scratch AS supervisor
ARG APP_IMAGE_REVISION
LABEL org.opencontainers.image.revision=${APP_IMAGE_REVISION}
COPY --from=build /out/adapter-manager /usr/local/bin/adapter-manager
ENTRYPOINT ["/usr/local/bin/adapter-manager"]

FROM scratch AS broker
ARG APP_IMAGE_REVISION
LABEL org.opencontainers.image.revision=${APP_IMAGE_REVISION}
COPY --from=docker-cli-build /out/docker /usr/local/bin/docker
COPY --from=docker-compose-download /out/docker-compose /usr/local/lib/docker/cli-plugins/docker-compose
COPY --from=build /out/adapter-manager /usr/local/bin/adapter-manager
ENTRYPOINT ["/usr/local/bin/adapter-manager"]
