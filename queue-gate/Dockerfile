# See booking-service/Dockerfile for why the JAR is built on the host.
FROM eclipse-temurin:25-jre

# Links the published package back to this repository on GHCR. Note what this
# does NOT do: it does not grant anything. A package created by a workflow that
# carries this label is repository-owned and writable by GITHUB_TOKEN, but the
# label cannot retroactively link a package that already exists — which is why
# the two packages created by hand in Phase 3 still need a manual access grant.
LABEL org.opencontainers.image.source=https://github.com/marwanbukhori/rembayung-queue
WORKDIR /app
COPY target/*.jar app.jar
COPY docker-entrypoint.sh .
RUN chmod +x docker-entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
