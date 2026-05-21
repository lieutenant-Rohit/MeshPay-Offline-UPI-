# ==========================================
# STAGE 1: Build the Application
# ==========================================
# Use an official Maven image with Java 21 to build the app
FROM maven:3.9.6-eclipse-temurin-21 AS builder

# Set the working directory inside the container
WORKDIR /app

# Copy only the pom.xml first to cache the dependencies
# This makes future builds much faster if you only change source code
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy the actual source code
COPY src ./src

# Build the application, skipping tests since we already ran them locally
RUN mvn clean package -DskipTests

# ==========================================
# STAGE 2: Run the Application
# ==========================================
# Use a highly stripped-down Java 21 Runtime Environment (JRE) for production
FROM eclipse-temurin:21-jre-alpine

# Create a non-root user for security (Prevents container breakout attacks)
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Set the working directory
WORKDIR /app

# Extract the compiled JAR file from the 'builder' stage
# Your pom.xml creates an artifact named payment-0.0.1-SNAPSHOT.jar
COPY --from=builder /app/target/payment-0.0.1-SNAPSHOT.jar app.jar

# Expose the standard Spring Boot port
EXPOSE 8080

# Command to execute the application
ENTRYPOINT ["java", "-jar", "app.jar"]