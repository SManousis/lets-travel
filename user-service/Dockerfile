FROM eclipse-temurin:21.0.12_8-jre-alpine
WORKDIR /app
# Security fixes released after the base image was published (the CI image scan blocks fixable HIGH/CRITICAL).
RUN apk upgrade --no-cache && addgroup -S app && adduser -S -G app app
COPY --chown=app:app target/user-service.jar app.jar
USER app
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
