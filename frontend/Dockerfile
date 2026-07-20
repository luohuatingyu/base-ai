ARG NODE_IMAGE=node:24-alpine
FROM ${NODE_IMAGE} AS build
WORKDIR /workspace
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

FROM ${NODE_IMAGE}
WORKDIR /app
ENV PORT=80
ENV BACKEND_URL=http://backend:8080
COPY server.mjs ./
COPY --from=build /workspace/dist ./dist
EXPOSE 80
CMD ["node", "server.mjs"]
