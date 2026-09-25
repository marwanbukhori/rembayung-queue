#!/usr/bin/env bash
# One answer to "is everything up?", from the outside in.
#
# It starts with what a visitor experiences — the two public URLs — because
# those need no login and are the only thing that matters to someone opening
# the link. Every outage so far has looked fine from some other angle: the
# console at 0 replicas behind a healthy-looking namespace, the log pipeline
# 404ing behind a green HEC probe. So each layer is asked separately and the
# script never stops at the first failure; it reports all of them.
#
# Usage: deploy/scripts/status.sh [namespace]
# Exit:  0 all good · 1 something is wrong · 2 could only check part of it
set -uo pipefail

NS="${1:-marwanbukhori-dev}"
APPS="apps.rm3.7wse.p1.openshiftapps.com"
CONSOLE_URL="https://console-${NS}.${APPS}/"
GATE_URL="https://queue-gate-${NS}.${APPS}/actuator/health"
WORKLOADS=(console redis booking-service queue-gate)

if [ -t 1 ]; then G=$'\e[32m'; R=$'\e[31m'; Y=$'\e[33m'; B=$'\e[1m'; N=$'\e[0m'
else G=; R=; Y=; B=; N=; fi

failed=0; partial=0
ok()   { echo "  ${G}ok${N}    $*"; }
bad()  { echo "  ${R}FAIL${N}  $*"; failed=1; }
warn() { echo "  ${Y}warn${N}  $*"; }
skip() { echo "  ${Y}skip${N}  $*"; partial=1; }
section() { echo; echo "${B}$*${N}"; }

# ---------------------------------------------------------------- public
section "Public URLs (what a visitor sees)"

probe() {
  local name="$1" url="$2" body code
  body="$(curl -s -m 10 -w $'\n%{http_code}' "${url}" 2>/dev/null)"
  code="${body##*$'\n'}"
  body="${body%$'\n'*}"
  if [ "${code}" = "200" ]; then
    ok "${name} ${code}"
  elif printf '%s' "${body}" | grep -q "Application is not available"; then
    bad "${name} ${code} — Route has no pod behind it (${url})"
  else
    bad "${name} ${code:-no answer} (${url})"
  fi
}
probe console    "${CONSOLE_URL}"
probe queue-gate "${GATE_URL}"

# ---------------------------------------------------------------- cluster
section "Cluster (${NS})"

cluster=0
if ! command -v oc >/dev/null; then
  skip "oc is not on PATH"
elif ! oc whoami >/dev/null 2>&1; then
  skip "oc is not logged in — Console → your name → Copy login command → Display Token"
else
  cluster=1
fi

if [ "${cluster}" = 1 ]; then
  for d in "${WORKLOADS[@]}"; do
    line="$(oc get "deploy/${d}" -n "${NS}" \
      -o jsonpath='{.spec.replicas} {.status.availableReplicas} {.spec.template.spec.containers[0].image}' 2>/dev/null)"
    if [ -z "${line}" ]; then bad "${d}: not found"; continue; fi
    read -r want have image <<<"${line}"
    have="${have:-0}"
    tag="${image##*:}"
    if [ "${want}" = 0 ]; then
      bad "${d}: scaled to 0 — fix: oc patch deploy/${d} --type=merge -p '{\"spec\":{\"replicas\":1}}'"
    elif [ "${have}" = 0 ]; then
      bad "${d}: 0/${want} available (${tag:0:12})"
    elif [ "${have}" -lt "${want}" ]; then
      warn "${d}: ${have}/${want} available (${tag:0:12})"
    else
      ok "${d}: ${have}/${want} available (${tag:0:12})"
    fi
  done

  # An HPA does not scale up from zero: once its target sits at 0 replicas it
  # reports ScalingActive=False and does nothing, so "the HPA will restore it"
  # stops being true at exactly the moment it is needed.
  while read -r name active reason; do
    [ -z "${name}" ] && continue
    if [ "${active}" = "True" ]; then ok "hpa/${name}: active"
    else bad "hpa/${name}: not scaling (${reason:-unknown})"; fi
  done < <(oc get hpa -n "${NS}" -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.conditions[?(@.type=="ScalingActive")].status}{" "}{.status.conditions[?(@.type=="ScalingActive")].reason}{"\n"}{end}' 2>/dev/null)

  # Pods that exist but are not healthy: crash loops, pulls, pending on quota.
  unhealthy="$(oc get pods -n "${NS}" --no-headers 2>/dev/null \
    | awk '$3 != "Running" && $3 != "Completed" { print $1 " " $3 }')"
  if [ -n "${unhealthy}" ]; then
    while read -r p s; do bad "pod ${p}: ${s}"; done <<<"${unhealthy}"
  else
    ok "no pods in a bad state"
  fi

  last="$(oc get jobs -n "${NS}" -l job-name --sort-by=.metadata.creationTimestamp \
    -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.succeeded}{" "}{.metadata.creationTimestamp}{"\n"}{end}' 2>/dev/null \
    | grep '^keepalive' | tail -1)"
  if [ -z "${last}" ]; then
    warn "keepalive: no recent job found"
  else
    read -r jname jok jwhen <<<"${last}"
    if [ "${jok}" = 1 ]; then ok "keepalive: last run ${jwhen} succeeded"
    else bad "keepalive: last run ${jwhen} did not succeed (oc logs job/${jname})"; fi
  fi

  for s in splunk-hec dynatrace oracle-wallet oracle-credentials; do
    if oc get secret "${s}" -n "${NS}" >/dev/null 2>&1; then ok "secret ${s} present"
    else warn "secret ${s} missing"; fi
  done

  events="$(oc get events -n "${NS}" --field-selector type=Warning \
    --sort-by=.lastTimestamp --no-headers 2>/dev/null | tail -5)"
  if [ -n "${events}" ]; then
    echo "  recent warnings:"
    printf '%s\n' "${events}" | awk '{ $1=$1; print "    " substr($0, 1, 150) }'
  fi
fi

# ---------------------------------------------------------------- ci/cd
section "CI / CD"
if ! command -v gh >/dev/null; then
  skip "gh is not on PATH"
else
  runs="$(gh run list --limit 4 --json workflowName,status,conclusion,headSha \
    -q '.[] | "\(.workflowName) \(.status) \(.conclusion) \(.headSha[0:7])"' 2>/dev/null)"
  if [ -z "${runs}" ]; then
    skip "gh could not list runs (gh auth status?)"
  else
    while read -r wf st con sha; do
      case "${st}/${con}" in
        completed/success) ok "${wf} ${sha} success" ;;
        completed/*)       bad "${wf} ${sha} ${con}" ;;
        *)                 warn "${wf} ${sha} ${st}" ;;
      esac
    done <<<"${runs}"
  fi
fi
echo "  head  $(git rev-parse --short HEAD 2>/dev/null) on $(git branch --show-current 2>/dev/null)"

# ---------------------------------------------------------------- verdict
echo
if [ "${failed}" = 1 ]; then echo "${R}${B}something is wrong${N}"; exit 1
elif [ "${partial}" = 1 ]; then echo "${Y}${B}partial check — see skips above${N}"; exit 2
else echo "${G}${B}everything is up${N}"; exit 0; fi
