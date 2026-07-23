# Stage 1: Build the Spring Boot application using Maven
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B || true

COPY src ./src
RUN ./mvnw clean package -DskipTests

# Stage 2: Runtime container
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /app/target/neo-copier-backend-java-1.0.0.jar app.jar

ENV PORT=3000
EXPOSE 3000

ENTRYPOINT ["java", "-jar", "app.jar"]
