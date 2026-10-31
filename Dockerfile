# syntax=docker/dockerfile:1.4
# The JAR includes both RocksDB JNI architectures; compile Java on the builder host.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-26 AS builder
WORKDIR /build
COPY pom.xml .
# Keep dependencies in an image layer so the GHA cache restores them on new runners.
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:17-jre-jammy
ENV TZ=Asia/Shanghai
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r bootfs && useradd -r -g bootfs bootfs \
    && mkdir -p /app /data/cache && chown -R bootfs:bootfs /app /data
WORKDIR /app
COPY --from=builder /build/target/bootfs-*.jar /app.jar
USER bootfs
EXPOSE 8181
ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "/app.jar"]
