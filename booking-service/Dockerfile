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
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
