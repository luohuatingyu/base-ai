ARG GO_IMAGE=golang:1.26.6-alpine@sha256:3889b425f035be855a72fb4755265311293b6d414521f0a519d819df32222d83
ARG DOCKER_CLI_IMAGE=docker:29.6.1-cli@sha256:862099ada15c669000bef53aa4cb9d821262829f45b0dda2159ccb276443043b

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

FROM ${DOCKER_CLI_IMAGE} AS broker
COPY --from=build /out/adapter-manager /usr/local/bin/adapter-manager
ENTRYPOINT ["/usr/local/bin/adapter-manager"]
