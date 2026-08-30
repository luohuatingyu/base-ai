ARG NODE_IMAGE=node:24-alpine@sha256:e67514e5d0f6c46656005e1b693b2ec9d52e80b641307de684d4a015ba7a4eaf
FROM ${NODE_IMAGE}
WORKDIR /app
RUN apk upgrade --no-cache \
    && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /opt/yarn-v* \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
        /usr/local/bin/yarn /usr/local/bin/yarnpkg \
    && addgroup -g 10001 -S appuser \
    && adduser -u 10001 -S -G appuser appuser \
    && mkdir -p /data/packages /data/tmp \
    && chown -R appuser:appuser /data
COPY --chown=appuser:appuser package.json ./
COPY --chown=appuser:appuser app ./app
USER appuser
EXPOSE 8102
CMD ["node", "app/server.mjs"]
