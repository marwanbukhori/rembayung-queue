#!/usr/bin/env bash
# Build both service images for linux/amd64 and push them to ghcr.io.
#
# The development machine is arm64 and the cluster is x86_64. A plain
# `docker build` produces arm64 layers that fail on the cluster with
# `exec format error` and a CrashLoopBackOff that names no cause, so the
# platform flag is not optional and the result is verified before use.
#
# Java bytecode is architecture-independent. We build the JARs natively on
# arm64, then copy them into amd64 images. This avoids the QEMU tar
# extraction failures that occur when trying to run Maven in an emulated
# amd64 container.
set -euo pipefail

REGISTRY="${REGISTRY:-ghcr.io/marwanbukhori}"
TAG="${TAG:-$(git rev-parse --short HEAD)}"

# Build JARs natively on the host (arm64), with the required JAVA_HOME for openjdk@25
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25}"
export PATH="${JAVA_HOME}/bin:${PATH}"

for svc in booking-service queue-gate; do
  echo "building ${svc} jar natively"
  (cd "${svc}" && ./mvnw -q -DskipTests package)
done

# Now build and push the amd64 images with the pre-built JARs
for svc in booking-service queue-gate; do
  image="${REGISTRY}/${svc}:${TAG}"
  echo "building ${image}"
  docker buildx build \
    --platform linux/amd64 \
    -t "${image}" \
    --push \
    "./${svc}"

  echo "verifying architecture of ${image}"
  arch="$(docker buildx imagetools inspect "${image}" \
            --format '{{range .Manifest.Manifests}}{{.Platform.OS}}/{{.Platform.Architecture}} {{end}}' \
          2>/dev/null || docker buildx imagetools inspect "${image}" | grep -i 'Platform' | head -1)"
  case "${arch}" in
    *linux/amd64*) echo "  ok: ${arch}" ;;
    *) echo "  FAIL: expected linux/amd64, got '${arch}'" >&2; exit 1 ;;
  esac
done

echo
echo "pushed at tag ${TAG}"
echo "set it in the overlay with:"
echo "  cd deploy/overlays/sandbox && kustomize edit set image \\"
echo "    ${REGISTRY}/booking-service=${REGISTRY}/booking-service:${TAG} \\"
echo "    ${REGISTRY}/queue-gate=${REGISTRY}/queue-gate:${TAG}"
