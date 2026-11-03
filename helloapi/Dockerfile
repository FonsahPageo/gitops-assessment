# Stage 1: Build the application 
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Resolve dependencies
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# Stage 2: runtime
FROM eclipse-temurin:21-jre-alpine

# non-root numeric user so k8s runAsNonRoot can verify it
RUN addgroup -g 10001 -S app && adduser -u 10001 -S -G app app
WORKDIR /app

COPY --from=build /workspace/target/app.jar app.jar

# injected at build time by CI: surfaced by GET /api/info
ARG APP_COMMIT=unknown
ENV APP_COMMIT=${APP_COMMIT} \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval= --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]