# Continuous Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A push runs both test suites against real Oracle and real Redis, builds both images, and publishes them to `ghcr.io` tagged with the commit that produced them — so no image is ever built by hand again.

**Architecture:** One GitHub Actions workflow on `ubuntu-latest`. Tests run first and gate everything after them; images publish only from `main` or a manual dispatch. Authentication to `ghcr.io` uses the workflow's built-in `GITHUB_TOKEN`, so there is no registry secret to store or rotate. Deployment stays out of CI entirely — that is Phase 5, and separating them is what makes a rollback possible without a rebuild.

**Tech Stack:** GitHub Actions, `ubuntu-latest`, Temurin JDK 25, Maven wrapper, Docker Buildx, Testcontainers (Oracle 23ai Free, Redis 7), ghcr.io

**Spec:** `docs/superpowers/specs/2026-09-03-continuous-integration-design.md`
**Parent spec:** `docs/superpowers/specs/2026-09-02-rembayung-booking-queue-design.md`

## Global Constraints

- **Java 25**, Spring Boot 4.1.1. Both modules build with their committed Maven wrapper (`./mvnw`), never a system `mvn`.
- **Real Oracle and real Redis in CI.** Never substitute H2, an in-memory Redis, or `-DskipTests` on any path that gates a merge. `ConcurrencyInvariantTest` runs 400 threads against one slot row and is the project's central claim; a pipeline that skips it is decorative.
- **Registry:** `ghcr.io/marwanbukhori/booking-service` and `ghcr.io/marwanbukhori/queue-gate`, both already public.
- **Image tags are the git short SHA. Never `latest`.** Phase 5's rollback depends on immutable tags.
- **Images must be `linux/amd64`.** GitHub runners are x86_64 so this is native — but it is still asserted, because the cluster fails with a bare `exec format error` if it is ever wrong.
- **Authentication is `GITHUB_TOKEN` only.** No registry credentials in secrets. This was the deciding reason for choosing ghcr.io over Quay.io.
- **CI does not deploy.** No `oc`, no kubeconfig, no cluster credentials anywhere in this workflow.
- **Commit messages must contain no AI attribution.** No `Co-Authored-By`, no `Claude-Session:`, no `claude.ai` URL, no "Generated with", no assistant mention. Hard standing rule of the repository owner; violated once already, requiring a history rewrite. After each commit run `git log -1 --format=%B` and confirm the body holds only your subject line and any prose you wrote.
- Local Maven commands must first `export JAVA_HOME=/opt/homebrew/opt/openjdk@25` and `export PATH="$JAVA_HOME/bin:$PATH"`. CI sets its own JDK.

### Two things that behave differently in CI

**Testcontainers reuse does nothing here, by design.** Both `OracleTestBase` and `RedisTestBase` call `.withReuse(true)`, which locally keeps containers alive between runs and makes the suite take seconds instead of minutes. Reuse is opt-in on *both* sides and requires `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, which a fresh runner does not have. **No code change is needed** — but expect CI to be much slower than your laptop, and do not "fix" it.

**The JAR is built on the host, then copied into the image.** Both Dockerfiles are single-stage and expect `target/*.jar` to exist. That was forced locally because building inside an emulated amd64 container fails on Apple Silicon, and it is kept in CI because it is faster and keeps the Dockerfiles identical in both places. **`./mvnw package` must run before `docker build`.**

---

## File Structure

```
rembayung-queue/
└── .github/
    └── workflows/
        └── ci.yml        the entire pipeline — one workflow, one job
```

One file. The spec's decision log records why one job rather than two: the build depends on the tests passing, so a split would only add a re-checkout and a second dependency resolution.

---

## Task 1: Test-only CI

Gets the suites running on a runner before any publishing exists. This task carries the phase's real risk — **Oracle Testcontainers on a hosted runner is the single biggest unknown** — so it is proven first and alone.

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: the committed Maven wrappers in both modules.
- Produces: a workflow named `ci` that runs both suites on every push and pull request. Task 2 extends this same file with build and publish steps.

- [ ] **Step 1: Create the workflow**

`.github/workflows/ci.yml`:

```yaml
name: ci

on:
  push:
  pull_request:
  workflow_dispatch:

# Two pushes in quick succession would otherwise race. Cancel superseded runs
# on branches, but never cancel a main build midway — it may be publishing.
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
          cache: maven

      # Real Oracle and real Redis, via Testcontainers. Not mocked, and not
      # H2: SELECT ... FOR UPDATE semantics are the thing under test, and
      # ConcurrencyInvariantTest — 400 threads against one slot row — is the
      # claim this whole project exists to make.
      #
      # Testcontainers reuse is a no-op here. It needs
      # testcontainers.reuse.enable=true in ~/.testcontainers.properties, which
      # a fresh runner does not have, so every run starts clean. That is why CI
      # is minutes where a laptop is seconds.
      - name: Test booking-service
        working-directory: booking-service
        run: ./mvnw --batch-mode verify

      - name: Test queue-gate
        working-directory: queue-gate
        run: ./mvnw --batch-mode verify
```

`--batch-mode` suppresses Maven's download progress spinner, which is unreadable in a CI log.

- [ ] **Step 2: Commit and push**

```bash
git add .github/workflows/ci.yml
git commit -m "Run both test suites on every push"
git push origin phase-1-booking-domain-core
```

- [ ] **Step 3: Watch the first run**

```bash
gh run watch
```

or open the Actions tab. **This is the step that matters.** Expect roughly 8–12 minutes: dependency resolution 1–2, Oracle startup 1–2, `booking-service` 2–3, `queue-gate` 1–2.

Expected: `booking-service` **36** tests, `queue-gate` **38**, both green.

- [ ] **Step 4: If Oracle fails to start**

Do not reach for a fallback first. The container is most likely slower on a hosted runner than on a laptop, not broken.

Check the log for what actually happened:

```bash
gh run view --log | grep -iE 'oracle|timed out|container|pull'
```

- **Timed out waiting for the container** → raise the startup timeout. `OracleTestBase` sets `.withStartupTimeout(Duration.ofMinutes(5))`; raise it to 10 and try once more.
- **Out of memory / killed** → the runner is short of memory. Report it; this is a real constraint decision, not a detail to work around silently.
- **Image pull failure** → transient; re-run.

**Never substitute H2 or a mocked datasource to make CI pass.** If Oracle genuinely cannot run on a hosted runner, that is a finding to report, not to route around.

- [ ] **Step 5: Confirm the test counts**

```bash
gh run view --log | grep -E 'Tests run:.*Failures'
```

Both totals must match the local ones exactly. A lower count means tests were skipped, which is a failure even when the build is green.

---

## Task 2: Build and publish images

Extends the same workflow. Runs only after the tests pass, and publishes only from `main` or a manual dispatch.

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: the passing suites from Task 1; both Dockerfiles; the committed Maven wrappers.
- Produces: `ghcr.io/marwanbukhori/booking-service:<short-sha>` and `ghcr.io/marwanbukhori/queue-gate:<short-sha>`, both `linux/amd64`. Phase 5's deploy consumes these tags.

- [ ] **Step 1: Add permissions and the publish steps**

Add `permissions` at the top level of `ci.yml`, directly after the `concurrency` block:

```yaml
# ghcr.io accepts the workflow's built-in GITHUB_TOKEN. There is no registry
# secret to store, rotate or leak — the reason ghcr.io was chosen over Quay.io.
permissions:
  contents: read
  packages: write
```

Then append these steps to the `build` job, after the two test steps:

```yaml
      # The Dockerfiles are single-stage and copy target/*.jar, so the jars must
      # exist before the image build. Locally this exists because building inside
      # an emulated amd64 container fails on Apple Silicon; here it is simply
      # faster, and it keeps the Dockerfiles identical in both places.
      - name: Package jars
        if: github.ref == 'refs/heads/main' || github.event_name == 'workflow_dispatch'
        run: |
          booking-service/mvnw --batch-mode -f booking-service -DskipTests package
          queue-gate/mvnw     --batch-mode -f queue-gate     -DskipTests package

      - name: Log in to ghcr.io
        if: github.ref == 'refs/heads/main' || github.event_name == 'workflow_dispatch'
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Set up Buildx
        if: github.ref == 'refs/heads/main' || github.event_name == 'workflow_dispatch'
        uses: docker/setup-buildx-action@v3

      - name: Build and push booking-service
        if: github.ref == 'refs/heads/main' || github.event_name == 'workflow_dispatch'
        uses: docker/build-push-action@v6
        with:
          context: ./booking-service
          platforms: linux/amd64
          push: true
          tags: ghcr.io/marwanbukhori/booking-service:${{ github.sha }}

      - name: Build and push queue-gate
        if: github.ref == 'refs/heads/main' || github.event_name == 'workflow_dispatch'
        uses: docker/build-push-action@v6
        with:
          context: ./queue-gate
          platforms: linux/amd64
          push: true
          tags: ghcr.io/marwanbukhori/queue-gate:${{ github.sha }}
```

**Note the tag is `github.sha`, the full 40-character SHA**, not the 7-character short form used locally. That is fine and preferable — it is unambiguous — but Phase 5 and the overlay must use the same form. Step 3 makes the value explicit in the log.

- [ ] **Step 2: Assert the architecture**

The runner is x86_64 so amd64 is native, but the cluster fails with a bare `exec format error` if this is ever wrong, and that error names no cause. Append:

```yaml
      # Native on an x86_64 runner, unlike the arm64 development machine — but
      # asserted anyway. A wrong architecture surfaces on the cluster as a
      # CrashLoopBackOff whose logs say only "exec format error".
      - name: Verify images are linux/amd64
        if: github.ref == 'refs/heads/main' || github.event_name == 'workflow_dispatch'
        run: |
          for svc in booking-service queue-gate; do
            image="ghcr.io/marwanbukhori/${svc}:${{ github.sha }}"
            echo "== ${image}"
            docker buildx imagetools inspect "${image}" | tee /tmp/inspect.txt
            grep -q 'linux/amd64' /tmp/inspect.txt \
              || { echo "::error::${image} is not linux/amd64"; exit 1; }
          done
```

- [ ] **Step 3: Print the deployable tag**

Phase 5 needs this value, and a human rehearsing a demo needs to find it without digging:

```yaml
      - name: Summary
        if: github.ref == 'refs/heads/main' || github.event_name == 'workflow_dispatch'
        run: |
          {
            echo "### Images published"
            echo
            echo '```'
            echo "ghcr.io/marwanbukhori/booking-service:${{ github.sha }}"
            echo "ghcr.io/marwanbukhori/queue-gate:${{ github.sha }}"
            echo '```'
            echo
            echo "Deploy with:"
            echo '```bash'
            echo "cd deploy/overlays/sandbox"
            echo "kustomize edit set image \\"
            echo "  ghcr.io/marwanbukhori/booking-service=ghcr.io/marwanbukhori/booking-service:${{ github.sha }} \\"
            echo "  ghcr.io/marwanbukhori/queue-gate=ghcr.io/marwanbukhori/queue-gate:${{ github.sha }}"
            echo "oc apply -k deploy/overlays/sandbox"
            echo '```'
          } >> "$GITHUB_STEP_SUMMARY"
```

- [ ] **Step 4: Commit and push**

```bash
git add .github/workflows/ci.yml
git commit -m "Build and publish images from main"
git push origin phase-1-booking-domain-core
```

- [ ] **Step 5: Verify the branch run does NOT publish**

The current branch is not `main`, so this push should run the tests and skip every publish step.

```bash
gh run watch
```

Expected: tests run; the packaging, login, build and verify steps all show as skipped. **If they run on a branch, the `if:` conditions are wrong — fix before merging**, or every branch push litters the registry.

- [ ] **Step 6: Prove publishing works, via manual dispatch**

`workflow_dispatch` publishes without needing a merge to `main`:

```bash
gh workflow run ci.yml --ref phase-1-booking-domain-core
gh run watch
```

Expected: images pushed, the architecture check passes, and the run summary shows both tags.

Confirm they are really in the registry:

```bash
docker buildx imagetools inspect ghcr.io/marwanbukhori/booking-service:$(git rev-parse HEAD) | grep -i platform
```

- [ ] **Step 7: Commit any fixes**

If Steps 5 or 6 required changes, commit them:

```bash
git add .github/workflows/ci.yml
git commit -m "Correct the publish conditions"
```

---

## Task 3: Document it

**Files:**
- Create: `docs/notes/06-continuous-integration.md`
- Modify: `docs/notes/README.md`
- Modify: `deploy/README.md`

**Interfaces:**
- Consumes: the working workflow.
- Produces: documentation explaining what CI does and why it is built this way.

- [ ] **Step 1: Write the note**

`docs/notes/06-continuous-integration.md`, matching the voice of notes 01–05 — they explain *why* a choice was made, especially where a default was rejected. Read `docs/notes/05-debugging-openshift.md` first for tone.

Cover:

- **Why CI runs real Oracle rather than H2.** `ConcurrencyInvariantTest` is the project's central claim; a pipeline that skips it proves nothing. This makes CI slower and more fragile than a mocked one, and that is the right trade *here* — but name it as a trade.
- **Why the architecture problem disappears in CI.** The dev machine is arm64 and the cluster is x86_64; building an amd64 image locally meant an x86 JVM under QEMU, where the Maven wrapper's tar extraction fails with `ENOSYS`. Runners are x86_64, so it is native. The parent spec predicted this benefit; Phase 3 confirmed the problem and Phase 4 confirms the fix.
- **Why `GITHUB_TOKEN` and no registry secret**, and that this decided ghcr.io over Quay.io.
- **Why tags are SHAs and never `latest`** — a rollback must be able to name a previous image.
- **Why CI does not deploy.** A job that deploys cannot roll back without rebuilding. Phase 5 is separate on purpose.
- **Why Testcontainers reuse does nothing here**, so nobody "fixes" the slowness.
- **Why one job, not two.**

- [ ] **Step 2: Add a row to the notes index**

In `docs/notes/README.md`:

```markdown
| [06 — Continuous integration](06-continuous-integration.md) | What CI runs and why it uses real Oracle, native amd64 builds, and no registry secret | — |
```

- [ ] **Step 3: Point the deploy runbook at CI**

In `deploy/README.md`, add a short section under the deployment steps noting that images are now built by CI on every push to `main`, that `deploy/scripts/build-push.sh` remains for local builds and emergencies, and that the tag to deploy appears in the Actions run summary.

- [ ] **Step 4: Commit**

```bash
git add docs/notes deploy/README.md
git commit -m "Document the continuous integration pipeline"
```

---

## Self-Review

**Spec coverage.** §3 (what CI must prove — real Oracle, real Redis, the concurrency test) → Task 1. §4 (architecture, one job, the arm64 problem disappearing) → Tasks 1 and 2, documented in Task 3. §5 (triggers, jobs, `GITHUB_TOKEN`, caching) → Tasks 1 and 2. §6 (risks: Oracle on a hosted runner, reuse being a no-op, wall clock, concurrency) → Task 1 Step 4, the `concurrency` block, and Task 3's note. §7 (what CI does not do) → enforced by omission and stated in Task 3. §8 (known weaknesses) → branch protection and digest pinning remain out of scope and are recorded in the spec, not built.

**Placeholders.** None. Every step contains the actual YAML or command.

**Type consistency.** The image names `ghcr.io/marwanbukhori/booking-service` and `ghcr.io/marwanbukhori/queue-gate` match Phase 3's overlay exactly. Working directories `booking-service` and `queue-gate` match the repository layout. The tag expression `${{ github.sha }}` is used identically in the build, verify and summary steps — note it is the full 40-character SHA, whereas local builds use the 7-character short form; both are valid tags and the summary prints the exact string to use.

**Known gaps, carried forward deliberately.** No branch protection — the workflow reports status but nothing enforces it before a merge; one repository setting, worth enabling before showing the repo. No test-result publishing, so failures must be read from the log. No image signing or scanning. All three are recorded in the spec's known weaknesses rather than built, because this is a demonstration pipeline and each would add surface without adding to the argument.
