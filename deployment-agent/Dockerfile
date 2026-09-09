ARG GO_IMAGE=golang:1.26.6-alpine@sha256:3889b425f035be855a72fb4755265311293b6d414521f0a519d819df32222d83

FROM ${GO_IMAGE} AS build
WORKDIR /workspace
COPY go.mod main.go main_test.go ./
RUN go test ./... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/deployment-agent .

FROM docker:29.7.2-cli@sha256:3f4743208d2338c934d7b8bcfbe1bb54c0b2355c510ad5e0f31c0c4a54bd704e
ARG APP_IMAGE_REVISION
LABEL org.opencontainers.image.revision=${APP_IMAGE_REVISION}
RUN apk add --no-cache openssh-client sshpass
COPY --from=build /out/deployment-agent /usr/local/bin/deployment-agent
USER 10001
EXPOSE 8091
ENTRYPOINT ["/usr/local/bin/deployment-agent"]
