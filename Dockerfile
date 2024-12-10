FROM openjdk:21-jdk-slim

# Create minecraft user and group
RUN groupadd -r minecraft && useradd -r -g minecraft minecraft

WORKDIR /app
COPY target/*.jar app.jar

# Set proper permissions
RUN chown -R minecraft:minecraft /app

EXPOSE 8080

# Switch to minecraft user
USER minecraft
ENTRYPOINT ["java","-jar","app.jar"]