ARG GO_IMAGE=golang:1.26.6-alpine@sha256:3889b425f035be855a72fb4755265311293b6d414521f0a519d819df32222d83
ARG DOCKER_CLI_VERSION=29.7.2
ARG DOCKER_CLI_SOURCE_SHA256=225b7ab2a15f5230b482df8461069cd4bce38891266fb9898d4188d0a3cbf54a

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

FROM ${GO_IMAGE} AS build
WORKDIR /workspace
COPY go.mod main.go main_test.go ./
RUN go test ./... \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/adapter-manager .

FROM scratch AS manager
COPY --from=build /out/adapter-manager /usr/local/bin/adapter-manager
USER 10001:0
EXPOSE 8090
ENTRYPOINT ["/usr/local/bin/adapter-manager"]

FROM scratch AS supervisor
COPY --from=build /out/adapter-manager /usr/local/bin/adapter-manager
ENTRYPOINT ["/usr/local/bin/adapter-manager"]

FROM scratch AS broker
COPY --from=docker-cli-build /out/docker /usr/local/bin/docker
COPY --from=build /out/adapter-manager /usr/local/bin/adapter-manager
ENTRYPOINT ["/usr/local/bin/adapter-manager"]
