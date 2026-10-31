# syntax=docker/dockerfile:1
# Build context: repository root (docker build -f url-shortener/Dockerfile .)

# Official Maven image matching the wrapper's pinned version (3.9.16) on Temurin 25: no in-container download.
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -pl url-shortener -am -DskipTests package

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
