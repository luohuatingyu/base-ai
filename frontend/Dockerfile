ARG NODE_IMAGE=node:24-alpine@sha256:e67514e5d0f6c46656005e1b693b2ec9d52e80b641307de684d4a015ba7a4eaf
FROM ${NODE_IMAGE} AS build
WORKDIR /workspace
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM ${NODE_IMAGE}
WORKDIR /app
ENV PORT=8080
ENV BACKEND_URL=http://backend:8080
RUN apk upgrade --no-cache \
    && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /opt/yarn-v* \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
        /usr/local/bin/yarn /usr/local/bin/yarnpkg
COPY --chown=node:node server.mjs ./
COPY --chown=node:node --from=build /workspace/dist ./dist
USER node
EXPOSE 8080
CMD ["node", "server.mjs"]
