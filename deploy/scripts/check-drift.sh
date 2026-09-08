#!/usr/bin/env bash
# Fails when the cluster does not match what git says it should be.
#
# This exists because that gap was invisible for 81 commits. The deploy path
# wrote exactly one field of one kind of object — the container image — and
# every other field in deploy/base reached the cluster only when a human
# remembered to run `oc apply -k`. A manifest-only change could therefore be
# committed, pass CI, deploy green, and never take effect: the console's Splunk
# configuration did precisely that, and the symptom was an empty Splunk search
# that looked like a broken integration.
#
# The images are deliberately excluded from the comparison. Which tag is
# deployed is a run-time decision that git cannot know, so this pins the
# rendered manifests to whatever each Deployment is running before diffing.
# What it checks is everything else: env, probes, resources, RBAC, routes,
# quotas, autoscalers.
#
# Usage: deploy/scripts/check-drift.sh [namespace]
# Exit:  0 clean · 1 drift found · 2 could not run the check
set -euo pipefail

NS="${1:-marwanbukhori-dev}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OVERLAY="deploy/overlays/sandbox"
SERVICES=(queue-gate booking-service console)
REGISTRY="ghcr.io/marwanbukhori"

command -v oc >/dev/null || { echo "check-drift: oc is not on PATH" >&2; exit 2; }
oc whoami >/dev/null 2>&1 || { echo "check-drift: not logged in to a cluster" >&2; exit 2; }

scratch="$(mktemp -d)"
trap 'rm -rf "${scratch}"' EXIT

cp -R "${REPO_ROOT}/deploy/base" "${scratch}/base"
mkdir -p "${scratch}/overlays"
cp -R "${REPO_ROOT}/${OVERLAY}" "${scratch}/overlays/sandbox"

# Pin each image to what that Deployment is ACTUALLY running, so the diff is
# about configuration rather than about which build happens to be deployed.
for svc in "${SERVICES[@]}"; do
  running="$(oc get "deploy/${svc}" -n "${NS}" \
    -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
  [ -n "${running}" ] || { echo "check-drift: ${svc} not found in ${NS}" >&2; exit 2; }
  tag="${running##*:}"
  # Rewrite only this service's pin, matched by image name.
  python3 - "${scratch}/overlays/sandbox/kustomization.yaml" "${REGISTRY}/${svc}" "${tag}" <<'PY'
import io, re, sys
path, name, tag = sys.argv[1], sys.argv[2], sys.argv[3]
text = io.open(path, encoding="utf-8").read()
pattern = re.compile(r"(- name:\s*" + re.escape(name) + r"\s*\n\s*newTag:\s*)\S+")
updated, count = pattern.subn(lambda m: m.group(1) + tag, text)
if count != 1:
    sys.exit("check-drift: expected exactly one pin for %s, found %d" % (name, count))
io.open(path, "w", encoding="utf-8").write(updated)
PY
done

oc kustomize "${scratch}/overlays/sandbox" > "${scratch}/rendered.yaml"

# `oc diff` exits 1 when there are differences and >1 on real errors, so the
# two are separated rather than both read as "drift".
set +e
diff_out="$(oc diff -n "${NS}" -f "${scratch}/rendered.yaml" 2>&1)"
rc=$?
set -e
[ "${rc}" -gt 1 ] && { echo "check-drift: oc diff failed" >&2; echo "${diff_out}" >&2; exit 2; }

# generation and resourceVersion churn on every write and are not configuration.
meaningful="$(printf '%s\n' "${diff_out}" \
  | grep -E '^[+-][^+-]' \
  | grep -vE '^[+-]\s*(generation|resourceVersion):' || true)"

# Matching git is not the same as serving traffic.
#
# The console sat at replicas 0 for forty minutes while this script would have
# called the namespace clean: the platform had scaled it down, the Route stayed
# up with nothing behind it, and the public URL answered "Application is not
# available". Every other page looked perfect, because every other workload was
# fine. A drift check that only compares manifests cannot see that, so it is
# asked separately and plainly: is anything declared, but not actually running?
dead="$(oc get deploy -n "${NS}" \
  -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.availableReplicas}{"\n"}{end}' \
  2>/dev/null | awk '$2 == "" || $2 == 0 { print "  " $1 " has no available replica" }')"

if [ -n "${dead}" ]; then
  echo "check-drift: NOT SERVING — declared but with nothing running:"
  printf '%s\n' "${dead}"
  echo
  echo "  Bring one back with: oc scale deploy/<name> --replicas=1"
  exit 1
fi

if [ -z "${meaningful}" ]; then
  echo "check-drift: clean — the cluster matches deploy/base in ${NS}, and every deployment has a pod"
  exit 0
fi

echo "check-drift: DRIFT — these differ from what git declares:"
printf '%s\n' "${diff_out}" | grep -E '^diff -u -N' \
  | sed -E 's#.*/(LIVE|MERGED)-[0-9]+/#  #' | sort -u
echo
printf '%s\n' "${meaningful}" | sed 's/^/  /'
exit 1
