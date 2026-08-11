ARG NODE_IMAGE=node:24-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43
FROM ${NODE_IMAGE}
WORKDIR /app
RUN addgroup -g 10001 -S appuser \
    && adduser -u 10001 -S -G appuser appuser \
    && mkdir -p /data/packages /data/tmp \
    && chown -R appuser:appuser /data
COPY --chown=appuser:appuser package.json ./
COPY --chown=appuser:appuser app ./app
USER appuser
EXPOSE 8102
CMD ["node", "app/server.mjs"]
