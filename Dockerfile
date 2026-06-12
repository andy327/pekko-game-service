# Stage 1: build the fat JAR
FROM sbtscala/scala-sbt:eclipse-temurin-17.0.15_6_1.11.0_2.13.16 AS builder

WORKDIR /build
COPY . .
RUN sbt server/assembly

# Match the builder's JDK major version (Temurin 17)
FROM eclipse-temurin:17-jre

# Set working directory
WORKDIR /app

# COPY the fat jar from the correct subproject directory (produced by sbt server/assembly)
COPY --from=builder /build/game-service-server/target/scala-2.13/game-service-server-assembly-*.jar app.jar

# Run the app
ENTRYPOINT ["java", "-jar", "app.jar"]
