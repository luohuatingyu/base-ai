ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc
ARG JRE_IMAGE=eclipse-temurin:17-jre@sha256:13cc28a6cc72a38ce1f00c906be3580c1a3e604b8984d694f369a96742abc93b

FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace
ARG MAVEN_MIRROR_URL
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2 && \
      printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; \
    fi
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package

FROM ${JRE_IMAGE} AS runtime-base
WORKDIR /app
RUN useradd -r -u 10001 appuser \
    && rm -f /usr/bin/pebble \
    && mkdir -p /app/caddy-public-ca /app/bootstrap /run/document-parser \
    && chown appuser:appuser /app/caddy-public-ca /app/bootstrap /run/document-parser
COPY --from=build /workspace/target/base-ai-backend-0.0.1-SNAPSHOT.jar /app/app.jar

FROM runtime-base AS document-parser
USER appuser
ENTRYPOINT ["java", "-Xms32m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-Djava.awt.headless=true", "-Dloader.main=com.baseai.platform.document.DocumentParserWorker", "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]

FROM runtime-base AS backend
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
USER appuser
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
