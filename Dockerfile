# syntax=docker/dockerfile:1.4
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -B

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
