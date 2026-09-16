FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app

COPY --chown=app:app target/reservation-service-0.0.1-SNAPSHOT.jar app.jar

USER app

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]