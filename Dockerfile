# syntax=docker/dockerfile:1.7

# Stage 1 — build the Spring Boot jar with Maven.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies before copying sources so dependency-only rebuilds
# don't re-download from Maven Central.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# Stage 2 — minimal runtime.
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the fat jar (Spring Boot Maven plugin produces a single executable jar).
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENV JAVA_OPTS=""

# `exec` so signals (SIGTERM from docker stop) reach the JVM and Spring
# shuts down gracefully instead of being killed.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
