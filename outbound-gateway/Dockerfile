ARG GO_IMAGE=golang:1.26.6-alpine@sha256:3889b425f035be855a72fb4755265311293b6d414521f0a519d819df32222d83
FROM ${GO_IMAGE} AS build
WORKDIR /workspace
COPY go.mod main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/outbound-gateway .

FROM ${GO_IMAGE}
RUN apk upgrade --no-cache \
    && addgroup -S -g 10001 appuser \
    && adduser -S -D -H -u 10001 -G appuser appuser
COPY --from=build /out/outbound-gateway /usr/local/bin/outbound-gateway
USER appuser
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/outbound-gateway"]
