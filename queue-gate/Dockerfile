# See booking-service/Dockerfile for why the JAR is built on the host.
FROM eclipse-temurin:25-jre

# Associates the published package with this repository, which is how GHCR
# links a container package to its source and grants the repository's Actions
# workflows write access to it.
LABEL org.opencontainers.image.source=https://github.com/marwanbukhori/rembayung-queue
WORKDIR /app
COPY target/*.jar app.jar
COPY docker-entrypoint.sh .
RUN chmod +x docker-entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
