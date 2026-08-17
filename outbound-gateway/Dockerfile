ARG GO_IMAGE=golang:1.26.5-alpine@sha256:0178a641fbb4858c5f1b48e34bdaabe0350a330a1b1149aabd498d0699ff5fb2
FROM ${GO_IMAGE} AS build
WORKDIR /workspace
COPY go.mod main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/outbound-gateway .

FROM ${GO_IMAGE}
RUN addgroup -S -g 10001 appuser && adduser -S -D -H -u 10001 -G appuser appuser
COPY --from=build /out/outbound-gateway /usr/local/bin/outbound-gateway
USER appuser
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/outbound-gateway"]
