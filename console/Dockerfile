# The first multi-language build in this project.
#
# The page and the service ship as one image on purpose: no second container, no
# second Route, and no way for a browser to be served a page built from a
# different commit than the API it calls.
#
# The node stage exists only to produce dist/. Nothing from it reaches the
# runtime image except the built files, so node, npm and node_modules — several
# hundred megabytes and a large share of the image's CVE surface — are not
# shipped to production.
FROM node:24-alpine AS ui
WORKDIR /ui
# Manifests first: the layer that installs dependencies is then reused whenever
# only the application source has changed.
COPY ui/package.json ui/package-lock.json ./
RUN npm ci
COPY ui/ ./
RUN npm run build

# See booking-service/Dockerfile for why the JAR is built on the host.
FROM eclipse-temurin:25-jre

# Links the published package back to this repository on GHCR, so a package
# first created by a workflow push is repository-linked and writable by
# GITHUB_TOKEN thereafter.
LABEL org.opencontainers.image.source=https://github.com/marwanbukhori/rembayung-queue
WORKDIR /app
COPY target/*.jar app.jar
COPY --from=ui /ui/dist/ui/browser/ /app/static/
# Spring Boot serves this ahead of the classpath copy, so the built page wins
# over the "not built yet" note baked into the JAR.
ENV CONSOLE_STATIC_PATH=/app/static/
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
