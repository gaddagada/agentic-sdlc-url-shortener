# syntax=docker/dockerfile:1
# Build context: repository root (docker build -f orchestrator-app/Dockerfile .)

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw -B -ntp -pl orchestrator-app -am -DskipTests package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --create-home app \
    && mkdir -p /app/data /workspace-src \
    && chown -R app:app /app /workspace-src
WORKDIR /app
COPY --from=build /workspace/orchestrator-app/target/orchestrator-app-*.jar app.jar
# Source tree the orchestrator reasons over (brownfield codebase analysis)
COPY --from=build /workspace/pom.xml /workspace-src/pom.xml
COPY --from=build /workspace/url-shortener/pom.xml /workspace-src/url-shortener/pom.xml
COPY --from=build /workspace/url-shortener/src /workspace-src/url-shortener/src
USER app
EXPOSE 8090
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC" \
    SDLC_WORKSPACE_ROOT=/workspace-src
ENTRYPOINT ["java", "-jar", "app.jar"]
