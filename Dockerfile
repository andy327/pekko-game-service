FROM eclipse-temurin:11-jre

# Set working directory
WORKDIR /app

# COPY the fat jar from the correct subproject directory (produced by sbt server/assembly)
COPY game-service-server/target/scala-2.13/game-service-server-assembly-*.jar app.jar

# Run the app
ENTRYPOINT ["java", "-jar", "app.jar"]
