FROM eclipse-temurin:21.0.12_8-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app
COPY --chown=app:app target/auth-service.jar app.jar
USER app
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
