# See booking-service/Dockerfile for why the JAR is built on the host.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/*.jar app.jar
COPY docker-entrypoint.sh .
RUN chmod +x docker-entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
