# syntax=docker/dockerfile:1

# ---------- build ----------
# Tests are skipped on purpose: the suite drives Testcontainers, which needs a
# Docker daemon *inside* the build. `./mvnw verify` stays a local / CI gate.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build

# Dependency layer — re-resolved only when pom.xml changes, not on every src edit.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

# ---------- runtime ----------
# jammy (glibc), not alpine: flying-saucer/OpenPDF pulls in java.awt for the
# agreement PDFs, and musl + headless AWT is a known source of surprises.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Runs as root deliberately. Railway mounts persistent volumes owned by root, so
# a non-root UID cannot write to UPLOAD_DIR without a privilege-dropping
# entrypoint. Revisit if uploads move to object storage.
COPY --from=build /build/target/*.jar app.jar

# Informational only — Railway routes to the port the app reports via $PORT.
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.awt.headless=true"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
