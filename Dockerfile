# Digi Gharz: one container serving the site, the app and the API (Java).
#
# Build args:
#   SITE_URL      public address, baked into canonical links and the sitemap
#   NODE_IMAGE    image that builds the frontend (React + Vite)
#   MAVEN_IMAGE   image that builds the backend (Java 21 + Maven)
#   JRE_IMAGE     image the app runs on
#   NPM_REGISTRY  npm registry; point it at a mirror if npmjs is unreachable
#   MAVEN_MIRROR  Maven repository mirror, if Maven Central is unreachable
# Point the *_IMAGE args at a registry mirror if Docker Hub is unreachable
# from the build server.
ARG NODE_IMAGE=node:22-alpine
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre-alpine

FROM ${NODE_IMAGE} AS frontend
ARG NPM_REGISTRY=https://registry.npmjs.org/
ARG SITE_URL=https://example.com
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm config set registry "$NPM_REGISTRY" && npm ci
COPY . .
RUN SITE_URL="$SITE_URL" npm run build && date -u +%Y-%m-%dT%H:%M:%SZ > BUILT_AT

FROM ${MAVEN_IMAGE} AS backend
ARG MAVEN_MIRROR=
WORKDIR /build
RUN if [ -n "$MAVEN_MIRROR" ]; then mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR" > /root/.m2/settings.xml; fi
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B package -DskipTests

FROM ${JRE_IMAGE}
ENV APP_ENV=production \
    PORT=3000 \
    DB_PATH=/app/data/digi-gharz.db \
    DIST_DIR=/app/dist \
    BUILT_AT_FILE=/app/BUILT_AT \
    TZ=Asia/Tehran \
    JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError"
WORKDIR /app
COPY --from=frontend /app/dist ./dist
COPY --from=frontend /app/BUILT_AT ./
COPY --from=backend /build/target/digigharz.jar ./
# Runs as root: PaaS disks are usually mounted root-owned, and SQLite must
# be able to write to them.
RUN mkdir -p /app/data
EXPOSE 3000
# SQLite lives in /app/data: mount the platform's persistent disk there, or
# set DATABASE_URL to use PostgreSQL instead.
# (No VOLUME line: it would create an anonymous volume some platforms keep
# in place of, or alongside, the disk they mount.)
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD wget -qO- http://127.0.0.1:3000/api/health || exit 1
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar digigharz.jar"]
