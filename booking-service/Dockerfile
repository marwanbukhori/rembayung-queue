# The JAR is built on the host, not in this image.
#
# Java bytecode is architecture-independent, so only the JRE base image needs
# to be linux/amd64. Building the JAR inside an emulated amd64 container would
# mean running an x86 JVM under QEMU on an arm64 Mac, where the Maven wrapper's
# tar extraction fails with ENOSYS. Copying a pre-built JAR needs no emulation
# at all, and is also considerably faster.
#
# Run `mvn -DskipTests package` before building this image; deploy/scripts/build-push.sh does both.
FROM eclipse-temurin:25-jre

# Associates the published package with this repository, which is how GHCR
# links a container package to its source and grants the repository's Actions
# workflows write access to it.
LABEL org.opencontainers.image.source=https://github.com/marwanbukhori/rembayung-queue
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
