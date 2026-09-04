# 06 — Continuous integration

**Covers:** why CI runs real Oracle instead of H2, why it builds images at all when the dev loop does not need to, why images are tagged by commit SHA and never republished, and what it actually took to make publishing work
**Branch state:** `phase-1-booking-domain-core`

---

## What CI does

One workflow, one job, on `ubuntu-latest`, `timeout-minutes: 30`.

Triggers:
- Every push to any branch
- Every pull request
- Manual dispatch via the GitHub Actions UI

Tests always run. Build and publish (packaging and image push) are guarded by:

```
if: github.ref == 'refs/heads/main' || github.event_name == 'workflow_dispatch'
```

So a branch push runs tests, fails safely if they fail, but never touches the registry. Only `main` pushes and manual dispatch can publish.

**The concurrency block** cancels superseded runs on branches but **never** on `main`:

```yaml
cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}
```

A publish cannot be aborted midway. Push a commit to `main` and then immediately push another, and the second run does not cancel the first — it **queues** behind it in the same concurrency group and starts only once the first has finished entirely. Both commits get published, in order.

That holds for two. It does not generalise: GitHub keeps at most **one** pending
run per concurrency group, so a third push cancels the still-waiting second, and
that middle commit is never published. Rapid-fire pushes to `main` publish the
first and the last, not everything in between. Not worth engineering around —
worth knowing before you conclude an image went missing.

On a branch the opposite is what you want: the older run is cancelled the moment a newer one arrives, because nobody cares whether a superseded commit passed. The distinction is the whole point of the expression — a cancelled test run costs nothing, a cancelled publish can leave a half-pushed image.

---

## Why CI runs real Oracle and real Redis, not mocks

`ConcurrencyInvariantTest` runs 400 threads against a single database row and is the central claim this whole project makes. The test passes only if `SELECT ... FOR UPDATE` locking actually works, Oracle's CHECK constraint actually prevents `seats_taken > capacity`, and Testcontainers actually starts a real container that behaves like production.

A pipeline that skipped this — substituting H2 or Mockito — would run fast and report green on a project that does not work. That is the wrong trade here. The cost is:

- **Slower:** Oracle pulls take 37s, start 17.8s. Tests follow. Whole suite is 1m41s on `booking-service`, 18.1s on `queue-gate`. Total job time is **2m23s** wall clock.

  **The spec predicted 8–12 minutes. That was wrong — pessimistic by about 4×.**
  It reasoned from a cold laptop run and did not account for `actions/setup-java`
  caching the Maven repository, or for GitHub's runners pulling from a registry
  mirror on the same network. The estimate is recorded here as wrong rather than
  quietly replaced, because the reasoning that produced it is the kind that will
  produce another one.
- **More fragile:** a network hiccup, a registry rate limit, or an Oracle license check can fail a build that has nothing to do with the code.

But they make the build meaningful. This is worth the cost.

**Note the log confirms zero skipped tests** — `mvn verify` on both services reports all 36 tests for `booking-service` and all 38 for `queue-gate`. If either showed skipped tests, the build would be green but would prove nothing. Watch for that number on every run.

---

## Why the architecture problem disappears here

This is one of the few places where CI **is** faster than local development.

The dev machine is arm64 (Apple Silicon) and the cluster is x86_64. Building an amd64 image locally meant spinning up an x86 JVM inside QEMU — thousands of CPU cycles slower than native. In Phase 3, we hit a real failure: the Maven wrapper's tar extraction inside the emulated container failed with `ENOSYS` (operation not supported). No amount of retrying helps; QEMU simply does not emulate enough of the syscall surface for that specific Java path.

Runners are x86_64 natively. No emulation. The build works. The parent spec predicted this would be the benefit of CI; Phase 3 confirmed the problem and Phase 4 confirms the fix.

**This is why the Dockerfiles are single-stage and copy `target/*.jar`** — they do not build from source. On the dev machine, building from source fails; in CI, we build the JARs natively on x86 first and copy them into amd64 layers. Same shapes work both places. The trade is that a Dockerfile alone no longer builds the app — you must run Maven first. That is fine; deployments do not rebuild from scratch anyway.

---

## Why `GITHUB_TOKEN` and no registry secret

The workflow uses the built-in `secrets.GITHUB_TOKEN` to log in to ghcr.io:

```yaml
password: ${{ secrets.GITHUB_TOKEN }}
```

There is nothing to store, rotate or leak. This decided the choice of ghcr.io over Quay.io — Quay would require managing a separate registry token, tracking its expiry, and distributing it to all runners.

`GITHUB_TOKEN` is ephemeral; GitHub issues and revokes it for each workflow run.

---

## Why tags are commit SHAs and never `latest`

Every image is tagged with the full commit SHA:

```
ghcr.io/marwanbukhori/booking-service:${{ github.sha }}
```

This is the full 40-character SHA (e.g. `a1b2c3d4e5f6...`), not a shortened form. Compare that to local builds via `deploy/scripts/build-push.sh`, which use the 7-character short form (e.g. `a1b2c3d`). Both are valid; both are immutable; SHAs never change.

**A rollback must be able to name a previous image.** If the tag were `latest`, every push rewrites it, and you cannot ask "give me the image from last Tuesday." CI prints the exact deploy command in the run summary, so nobody retypes and guesses.

The `latest` tag is a footgun that feels convenient until it is not.

---

## Why CI does not deploy

The workflow builds and publishes images. It does not apply the Kustomize overlays or update OpenShift.

A deployment job that deploys **cannot roll back without rebuilding.** If a job pushes an image and then patches the cluster, and the patch goes wrong, you cannot simply point back to the previous manifest — the old image tag is gone and rebuilt into something else.

Phase 5 adds **Ansible** for CD — a playbook run from a workflow, not Jenkins. It runs after CI and does the deployment, so rollback is separable from build. (The parent spec originally said Jenkins; it was revised, because an in-cluster Jenkins would consume CPU quota the autoscaling demonstration needs, and its rationale — a host with network reach into the cluster — is already satisfied by a runner.)

Phase 4 (this phase) is CI only: prove the code is good, and publish evidence of it. Phase 5 uses that evidence.

---

## Why Testcontainers reuse does nothing here

Both `OracleTestBase` and `RedisTestBase` call `.withReuse(true)`:

```java
// OracleTestBase.java:31
            .withReuse(true);

    static {
        ORACLE.start();
    }
```

`RedisTestBase.java:28` does the same. Note the shape: `.withReuse(true)` is set
on the *builder*, and the container is started once in a `static` block — the
singleton pattern that makes the suite share one container across every
`@SpringBootTest` context instead of starting a fresh one per class.

Reuse is opt-in on both sides. The container framework will reuse a container only if:

1. The application explicitly asks (which we do)
2. **AND** the runner has `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`

A fresh GitHub Actions runner has neither a `~/.testcontainers.properties` file nor any containers to reuse. So every CI run starts Oracle and Redis cold, paying the full 55s startup cost every time.

This is why CI is **2m23s and the laptop is seconds.** The reuse setting is local; turning it off to "fix" the slowness in CI would cost the fast local loop for nothing. Do not do that.

---

## Why one job rather than two

The workflow could split into a "test" job and a "build" job, or even a separate "deploy" job. One test, build and publish together.

The build depends on the tests passing. Splitting would require:
- A second checkout of the repository (the runner's workspace is ephemeral between jobs)
- A second Maven dependency resolution, because Maven's local cache also does not cross job boundaries
- Job orchestration to run them in sequence

For no gain — the workflow already runs sequentially. A single job is simpler, faster, and has one failure mode to reason about.

---

## Publishing: how it was broken, and what it actually took

Images publish correctly now. Getting there is worth recording, because the
error named the symptom and not the cause, and because the fix had a second half
that is easy to miss.

The push failed with:

```
denied: permission_denied: write_package
```

The misleading part: **the token was fine.** The job log prints its grants, and
they read `Contents: read` / `Packages: write` in every failing run. Login
succeeded and GHCR issued a `pull,push` token. So authentication worked and the
permission was present — and the push was still refused.

The cause was on the other side. Queried directly:

```console
$ gh api /user/packages/container/booking-service
  owner      : marwanbukhori (User)
  repository : null
```

Both packages were created by hand in Phase 3 with a personal access token, so
they are **user-owned with no repository linked**. `GITHUB_TOKEN`'s
`packages: write` is scoped to *this repository*; a package that has no
relationship to the repository is not covered by it. A workflow can freely
**create** a new package — that one is linked at birth and writable ever after —
but it cannot write to a pre-existing unlinked one.

### The half that is easy to miss

The fix is in each package's settings → **Manage Actions access** → **Add
Repository** → `rembayung-queue`. That is the step everyone writes down.

**Adding the repository is not enough. The new row defaults to the `Read` role,**
and Read does not permit push. Changing it to `Write` is a separate dropdown, and
skipping it produces the identical `write_package` denial — so it looks like the
fix did not work at all, rather than like it was half applied.

That misdiagnosis is worth the paragraph it costs. The evidence that settled it
was accidental and better than anything designed: one repository was switched to
`Write` and the other left at `Read`, and the next run pushed `booking-service`
successfully and failed `queue-gate` — same job, same token, same second. One
variable, two outcomes.

### What is not the cause

- **Not the branch.** The `if` guard evaluated true and the step ran; the ref was
  never involved. The permission is scoped to the package.
- **Not the repository's default workflow permissions.** That setting *is*
  `read` on this repo, which looks like a smoking gun. It is not: the workflow's
  explicit `permissions:` block overrides it, which the job log confirms by
  printing `Packages: write`. Changing that setting would have altered something
  that was never broken.
- **Not the `org.opencontainers.image.source` label.** It links a package created
  by a workflow push, and is why every *future* package will not need any of
  this. It cannot rescue these two, for a circular reason: GHCR reads that label
  from a manifest that has already been pushed, and the push is what is denied.

### Verified working

Run `33831363387`, all steps green:

```
Test booking-service   Tests run: 36, Failures: 0, Errors: 0, Skipped: 0
Test queue-gate        Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
booking-service        linux/amd64   HTTP 200 in the registry
queue-gate             linux/amd64   HTTP 200 in the registry
wall clock             147s
```

---

## Running a test locally

The same CI commands work on the laptop:

```bash
cd booking-service
./mvnw --batch-mode verify

cd ../queue-gate
./mvnw --batch-mode verify
```

Tests start immediately, but Oracle takes ~55s to pull and start. Testcontainers reuse makes subsequent runs much faster:

```bash
# First run: 2+ minutes (cold Oracle)
mvn test

# Second run: ~20s (reused Oracle)
mvn test
```

Enable reuse in `~/.testcontainers.properties`:

```
testcontainers.reuse.enable=true
```

Then stop the reuse on purpose when you are done:

```bash
docker stop $(docker ps --filter ancestor=gvenzl/oracle-free:23-slim-faststart -q)
```

Or leave it running; it will not affect anything else.
