# See booking-service/Dockerfile for why the JAR is built on the host.
FROM eclipse-temurin:25-jre

# Links the published package back to this repository on GHCR, so a package
# first created by a workflow push is repository-linked and writable by
# GITHUB_TOKEN thereafter.
#
# It cannot rescue this project's two existing packages, and the reason is
# circular rather than a limitation of the label: GHCR reads the label from a
# manifest that has already been pushed, and the push is precisely what is being
# denied. Those packages were created by hand with a PAT (measured: owner is a
# User, linked repository is null), so they need a one-time Actions-access grant
# in the package settings. The label is here for every package created after it.
LABEL org.opencontainers.image.source=https://github.com/marwanbukhori/rembayung-queue
WORKDIR /app
COPY target/*.jar app.jar
COPY docker-entrypoint.sh .
RUN chmod +x docker-entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
