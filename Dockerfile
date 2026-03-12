# syntax=docker/dockerfile:1.4

# Stage 1: Build stage - compile the application
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy pom.xml first
COPY pom.xml .

# Download dependencies with cache mount
# The .m2 directory is cached separately from the image layers
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

# Copy source code and build with cache mount
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests -B

# Stage 2: Runtime stage - minimal JRE image
FROM eclipse-temurin:17-jre-jammy

ENV TZ=PRC
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# Create non-root user for security
RUN groupadd -r tfs && useradd -r -g tfs tfs

WORKDIR /app

# Copy the built jar from builder stage
COPY --from=builder /build/target/toyohime-file-service-*.jar /app/app.jar

# Create data directory and set permissions
RUN mkdir -p /data && chown -R tfs:tfs /app /data

USER tfs

EXPOSE 8181

ENTRYPOINT exec java $JAVA_OPTS -Dspring.profiles.active=prod -jar /app/app.jar \
    --server.tomcat.connection-timeout=1800000 \
    --tfs.copies=$TFS_COPIES \
    --tfs.web-entrypoint=$TFS_WEB_ENTRYPOINT \
    --tfs.data-dir=/data \
    --meili-search.master-key=$MEILISEARCH_MASTER_KEY \
    --meili-search.web-ui=$MEILISEARCH_WEB_UI \
    --sso.server-url=$SSO_SERVER_URL \
    --sso.info-url=$SSO_INFO_URL
