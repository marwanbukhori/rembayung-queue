#!/usr/bin/env python3
"""Capture real CI and CD runs as data for the console's CI/CD page.

The page is an example of how the pipeline works, not a status page, so it
reads a copy of real runs rather than calling GitHub: nothing to rate-limit
and no credential in the console. Refreshing the example is running this again.

    deploy/scripts/capture-cicd-runs.py CI_RUN CD_RUN ROLLBACK_RUN:ATTEMPT
    deploy/scripts/capture-cicd-runs.py --self-test

Needs `gh` logged in to the repository. Refuses to write the file if any kept
log line looks like a credential; set CONSOLE_KEY to also refuse the console key.
"""
import json
import os
import re
import subprocess
import sys
from datetime import datetime

OUT = os.path.join(os.path.dirname(__file__), "../../console/ui/src/app/cicd-runs.ts")
HEAD, TAIL, WIDTH = 14, 8, 160
STAMP = re.compile(r"^\ufeff?\d{4}-\d\d-\d\dT[\d:.]+Z ?")
ANSI = re.compile(r"\x1b\[[0-9;]*m")
SECRET = re.compile(r"sha256~|ghp_|ghs_|token=[^*\s]")
DEPLOY_KEEP = re.compile(r'^(TASK \[|PLAY |ok: |changed: |fatal: |failed: |skipping: |localhost +:|\s*"msg"|Run ansible-playbook)')

# What each step does and why, from notes 06 and 07.
EXPLAIN = {
    "Set up job": "GitHub provisions a fresh ubuntu-latest runner for this one job. Nothing carries over from the previous run, so every build starts from a clean machine.",
    "Run actions/checkout@v4": "Checks out the exact commit that was pushed. Every image tag later in the pipeline is this commit's SHA.",
    "Set up JDK 25": "Installs Temurin 25 and restores the Maven cache keyed on the pom files, so dependencies download once rather than on every run.",
    "Test booking-service": "The slowest step, on purpose: Testcontainers starts a real Oracle and a real Redis and the tests run against them. A mock would accept SQL that Oracle rejects, and the seat-claiming query is exactly the SQL that matters.",
    "Test queue-gate": "queue-gate's tests, against a real Redis started by Testcontainers. The queue lives in Redis, so that is where it is tested.",
    "Test console": "The console's tests: the key filter, the demo-key endpoint, the Prometheus and pod readers.",
    "Package jars": "Builds the three Spring Boot jars. From here on the steps run only on main or a manual dispatch; a branch push stops after the tests and never touches the registry.",
    "Log in to ghcr.io": "Logs in to GitHub's container registry with the job's own short-lived GITHUB_TOKEN. There is no stored registry password to leak or rotate.",
    "Set up Buildx": "Prepares Docker's builder, which builds for linux/amd64, the architecture the OpenShift nodes run.",
    "Build and push booking-service": "Builds booking-service's image and pushes it tagged with the full commit SHA, never latest. An immutable tag is what lets CD say exactly what is running, and roll back to it.",
    "Build and push queue-gate": "The same for queue-gate: one image per service, one tag per commit.",
    "Build and push console": "The same for the console, which also carries the Angular UI you are reading.",
    "Verify images are linux/amd64": "Inspects each pushed manifest and fails if any image is not linux/amd64. An image built for the wrong architecture would pass every test here and then fail to start on the cluster.",
    "Summary": "Writes what happened to the run's summary page.",
    "Complete job": "The runner is discarded.",
    "Install Ansible and the Kubernetes client library": "CD is a separate workflow that starts when ci succeeds on main: CI publishes, and only CD touches the cluster. This installs Ansible and kubernetes.core, which talks to the API directly rather than shelling out to oc, so every task reports ok or changed truthfully.",
    "Install kustomize": "Installs kustomize, to render the same overlay a person would apply by hand.",
    "Authenticate to OpenShift": "Exports the cluster URL and a ServiceAccount token from the repository's secrets, never echoed. That ServiceAccount cannot read Secrets and cannot change RBAC, so a leaked token cannot widen its own access.",
    "Resolve the tag to deploy": "Picks the commit SHA that CI just published. A manual dispatch can name any earlier tag, which is how a rollback by hand works.",
    "Deploy": "The playbook, in order: read what is running now and refuse to start mid-rollout; render the overlay with every image pinned to this tag and apply only the kinds CD may write; wait for both rollouts; smoke-test the public queue path. Any failure jumps to the rollback.",
}
ROLLBACK_DEPLOY = ("This time booking-service did not become ready inside the wait. The playbook described what failed, "
                   "restored each Deployment to the tag it had been running, waited for that rollout, re-checked the "
                   "public queue path, and then failed loudly naming both tags. The site stayed up throughout; the re-run passed.")


def clean(line):
    return ANSI.sub("", STAMP.sub("", line.rstrip("\n")))


def cut(text):
    return text if len(text) <= WIDTH else text[:WIDTH] + "…"


def trim(lines, deploy=False):
    """Keep the useful part of one step's log, with GitHub's line numbers."""
    if deploy:
        return [(n, cut(s)) for n, s in lines if DEPLOY_KEEP.match(s)]
    if len(lines) <= HEAD + TAIL + 1:
        return [(n, cut(s)) for n, s in lines]
    gap = len(lines) - HEAD - TAIL
    return ([(n, cut(s)) for n, s in lines[:HEAD]] + [(None, f"… {gap} lines …")]
            + [(n, cut(s)) for n, s in lines[-TAIL:]])


def scan_for_secrets(lines, extra=()):
    """The first kept line that looks like a credential, or None."""
    for n, s in lines:
        if SECRET.search(s) or any(x and x in s for x in extra):
            return (n, s)
    return None


def numbered(raw):
    out = []
    for s in raw:
        s = clean(s)
        if s.startswith("##[endgroup]"):
            continue
        out.append(s.replace("##[group]", "").replace("##[error]", "Error: "))
    return [(i + 1, s) for i, s in enumerate(out)]


def seconds(a, b):
    f = lambda x: datetime.fromisoformat(x.replace("Z", "+00:00"))
    return int((f(b) - f(a)).total_seconds())


def gh(*args):
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def shown(step):
    return not step["name"].startswith("Post ")


def run_from_view(run_id, workflow):
    meta = json.loads(gh("run", "view", run_id, "--json", "jobs,url,headSha,attempt"))
    by_step = {}
    for line in gh("run", "view", run_id, "--log").splitlines():
        parts = line.split("\t", 2)
        if len(parts) == 3:
            by_step.setdefault(parts[1], []).append(parts[2])
    job = meta["jobs"][0]
    steps = [dict(name=s["name"], result=s["conclusion"], started=s["startedAt"], completed=s["completedAt"],
                  raw=by_step.get(s["name"], [])) for s in job["steps"] if shown(s)]
    return build(workflow, job["name"], run_id, meta["attempt"], meta["url"], meta["headSha"],
                 job["startedAt"], job["completedAt"], job["conclusion"], steps)


def run_from_attempt(run_id, attempt, workflow):
    jobs = json.loads(gh("api", f"repos/{{owner}}/{{repo}}/actions/runs/{run_id}/attempts/{attempt}/jobs"))["jobs"]
    job = jobs[0]
    raw = gh("api", f"repos/{{owner}}/{{repo}}/actions/jobs/{job['id']}/logs").splitlines()
    # One raw log: each step after "Set up job" opens with a "##[group]Run" line.
    groups, current = [[]], None
    for line in raw:
        body = clean(line)
        if body.startswith("Post job cleanup"):
            break
        if body.startswith("##[group]Run "):
            groups.append([])
        groups[-1].append(line)
    steps = []
    listed = [s for s in job["steps"] if shown(s) and s["name"] != "Complete job"]
    for i, s in enumerate(listed):
        steps.append(dict(name=s["name"], result=s["conclusion"], started=s["started_at"],
                          completed=s["completed_at"], raw=groups[i] if i < len(groups) else []))
    run = build(workflow, job["name"], run_id, attempt, job["html_url"], job["head_sha"],
                job["started_at"], job["completed_at"], job["conclusion"], steps)
    for step in run["steps"]:
        if step["name"] == "Deploy":
            step["explain"] = ROLLBACK_DEPLOY
    return run


def build(workflow, job, run_id, attempt, url, sha, started, completed, result, steps):
    out = []
    for s in steps:
        log = trim(numbered(s["raw"]), deploy=s["name"] == "Deploy")
        out.append(dict(name=s["name"], result=s["result"], seconds=seconds(s["started"], s["completed"]),
                        log=log, explain=EXPLAIN.get(s["name"], EXPLAIN.get(s["name"].split(" ")[0], ""))))
    return dict(workflow=workflow, job=job, runId=int(run_id), attempt=int(attempt), url=url, commit=sha[:7],
                startedAt=started, result=result, seconds=seconds(started, completed), steps=out)


def write(runs):
    extra = tuple(filter(None, [os.environ.get("CONSOLE_KEY")]))
    for run in runs:
        for step in run["steps"]:
            hit = scan_for_secrets(step["log"], extra)
            if hit:
                sys.exit(f"refusing to write: {run['workflow']} / {step['name']} line {hit[0]} looks like a credential")
    body = json.dumps(runs, indent=1, ensure_ascii=False)
    with open(OUT, "w") as f:
        f.write("// Generated by deploy/scripts/capture-cicd-runs.py from real runs. Do not edit by hand;\n"
                "// re-run the script to refresh the example.\n\n"
                "export interface CapturedStep {\n  name: string;\n  result: string;\n  seconds: number;\n"
                "  /** [GitHub's line number, or null for an elision marker; the line] */\n"
                "  log: [number | null, string][];\n  explain: string;\n}\n\n"
                "export interface CapturedRun {\n  workflow: string;\n  job: string;\n  runId: number;\n"
                "  attempt: number;\n  url: string;\n  commit: string;\n  startedAt: string;\n  result: string;\n"
                "  seconds: number;\n  steps: CapturedStep[];\n}\n\n"
                f"export const CAPTURED_RUNS: CapturedRun[] = {body};\n")
    print("wrote", os.path.normpath(OUT))


def self_test():
    ok = True
    def check(name, cond):
        nonlocal ok
        print(("PASS " if cond else "FAIL ") + name)
        ok = ok and cond
    lines = [(i + 1, f"line {i}") for i in range(40)]
    kept = trim(lines)
    check("keeps first 14 and last 8 with a gap marker", len(kept) == 23 and kept[14][1].startswith("… 18 lines"))
    check("keeps GitHub line numbers", kept[0][0] == 1 and kept[-1][0] == 40)
    check("short step untouched", trim(lines[:10]) == lines[:10])
    longl = trim([(1, "x" * 400)])
    check("long line cut to 160 with ellipsis", len(longl[0][1]) == 161 and longl[0][1].endswith("…"))
    dep = trim([(1, "TASK [rembayung : Roll back] ****"), (2, "noise"), (3, 'ok: [localhost]'),
                (4, "fatal: [localhost]: FAILED!"), (5, "PLAY RECAP ***"), (6, "localhost : ok=24")], deploy=True)
    check("deploy keeps tasks, results and recap", [n for n, _ in dep] == [1, 3, 4, 5, 6])
    check("secret: sha256~ token refused", scan_for_secrets([(1, "oc login --token=sha256~abc")]) is not None)
    check("secret: raw token= refused", scan_for_secrets([(1, "token=abcdef")]) is not None)
    check("masked token allowed", scan_for_secrets([(1, "token=***"), (2, "password: ***")]) is None)
    check("console key refused", scan_for_secrets([(1, "?key=hunter2")], extra=("hunter2",)) is not None)
    return ok

if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(0 if self_test() else 1)
    if len(sys.argv) != 4 or ":" not in sys.argv[3]:
        sys.exit(__doc__)
    rb_run, rb_attempt = sys.argv[3].split(":")
    write([run_from_view(sys.argv[1], "ci"), run_from_view(sys.argv[2], "CD"),
           run_from_attempt(rb_run, rb_attempt, "CD")])
