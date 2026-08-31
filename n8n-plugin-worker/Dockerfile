ARG NODE_IMAGE=node:24-alpine@sha256:e67514e5d0f6c46656005e1b693b2ec9d52e80b641307de684d4a015ba7a4eaf
FROM ${NODE_IMAGE}
WORKDIR /app
RUN apk upgrade --no-cache \
    && npm install --global --ignore-scripts npm@12.0.2 \
    && cp /usr/local/lib/node_modules/npm/package.json /tmp/npm-package.json \
    && node -e 'const fs = require("node:fs"); const path = "/usr/local/lib/node_modules/npm/package.json"; const config = require(path); delete config.devDependencies; delete config.workspaces; config.dependencies.tar = "7.5.21"; config.overrides = {"brace-expansion": "5.0.9", "ip-address": "10.3.1"}; fs.writeFileSync(path, JSON.stringify(config))' \
    && npm install --prefix /usr/local/lib/node_modules/npm --ignore-scripts --omit=dev --package-lock=false --no-audit --no-fund \
    && mv /tmp/npm-package.json /usr/local/lib/node_modules/npm/package.json \
    && npm cache clean --force \
    && rm -rf /usr/local/lib/node_modules/corepack /opt/yarn-v* \
    && rm -f /usr/local/bin/corepack /usr/local/bin/yarn /usr/local/bin/yarnpkg \
    && addgroup -g 10001 -S appuser \
    && adduser -u 10001 -S -G appuser appuser \
    && mkdir -p /data/packages /data/tmp \
    && chown -R appuser:appuser /data
COPY --chown=appuser:appuser package.json ./
COPY --chown=appuser:appuser app ./app
USER appuser
EXPOSE 8102
CMD ["node", "app/server.mjs"]
