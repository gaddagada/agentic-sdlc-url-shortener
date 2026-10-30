# syntax=docker/dockerfile:1
# Build context: repository root (docker build -f url-shortener/Dockerfile .)

FROM eclipse-temurin:25-jdk AS build
# The Maven wrapper needs a downloader (curl or wget) to fetch the pinned, checksum-verified distribution.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && MVNW_VERBOSE=true ./mvnw -B -ntp -pl url-shortener -am -DskipTests package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --create-home app \
    && mkdir -p /app/data \
    && chown -R app:app /app
WORKDIR /app
COPY --from=build /workspace/url-shortener/target/url-shortener-*.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
ENTRYPOINT ["java", "-jar", "app.jar"]
