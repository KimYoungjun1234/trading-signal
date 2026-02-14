# Stage 1: Build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
COPY src ./src
RUN ./gradlew bootJar --no-daemon

# Stage 2: Run
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

ENV DB_USERNAME=root
ENV DB_PASSWORD=""
ENV SLACK_WEBHOOK_URL=""
ENV SPRING_DATASOURCE_URL=""

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
