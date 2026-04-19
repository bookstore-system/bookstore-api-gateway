# ===== BUILD STAGE =====
FROM maven:3.9.4-eclipse-temurin-17 AS build
WORKDIR /workspace

# cache dependencies
COPY mvnw pom.xml ./
COPY .mvn .mvn

# copy source
COPY src ./src

RUN chmod +x mvnw || true
RUN ./mvnw -B -DskipTests package

# ===== RUNTIME STAGE =====
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]