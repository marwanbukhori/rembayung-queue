# CI/CD Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** A CI/CD page that shows how the pipeline works using real, captured runs, the way GitHub's run page shows them.

**Architecture:** A script turns `gh run view` output into `cicd-runs.ts`, a static data file in the console UI. A `CicdPage` component renders the pipeline diagram (moved from the Overview), then one run per workflow as expandable steps with logs and explanations, then three guard cards. The page has no backend and makes no GitHub calls.

**Tech Stack:** Python 3 (capture script), Angular 20 standalone components with signals.

**Spec:** `docs/superpowers/specs/2026-09-25-site-presentation-design.md` §3.1, §4 (CI/CD tile), §5.

## Global Constraints

- No live GitHub calls; the data is a captured example (§2 "Run data").
- All times use `malaysiaTime` and are labelled GMT+8 once per run (§7.2).
- Nav order: Overview · Run a simulation · Cluster · CI/CD · AI Agent · Build notes (§3.1). This plan adds CI/CD only; AI Agent comes in plan 3.
- No secret may reach the data file: logs are scanned for tokens and the console key, and the capture fails if one is found.

## Review Focus

1. **A step with no log lines** (for example "Install kustomize" after trimming). Expected: the row still expands and shows its explanation, with no empty dark box.
2. **A very long log line** (JSON dumps from Ansible). Expected: trimmed with "…", and no sideways page scroll at 390px.
3. **The failed rollback run.** Expected: collapsed by default, a red "failed" result, and the rollback tasks readable in order.
4. **Keyboard use.** Expected: step rows are buttons, so Enter or Space expands them.
5. **The capture re-run with a secret in a log.** Expected: the script stops with a non-zero exit and writes nothing.

---

### Task 1: Capture script and data

**Files:** create `deploy/scripts/capture-cicd-runs.py` and `console/ui/src/app/cicd-runs.ts` (generated).

- [ ] The script takes a CI run id, a CD run id and a rollback run id with its attempt. It reads `gh run view --json jobs` and `--log` (or the jobs API for an attempt), splits the log into steps, and drops `##[group]`, `##[endgroup]` and timestamps.
- [ ] It trims each step to its first 14 and last 8 lines, with a "… N lines …" marker in between. For the Deploy step it keeps Ansible `TASK`, `ok:`, `changed:`, `fatal:`, `"msg"` and `PLAY RECAP` lines. Lines over 160 characters get "…".
- [ ] It keeps GitHub's line number for each kept line, and adds a hand-written explanation per step name (drawn from notes 06 and 07).
- [ ] It refuses to write if any line matches `sha256~`, `ghp_`, `ghs_`, `token=[^*\s]`, or `$CONSOLE_KEY` when that is set.
- [ ] Run it for runs 36157265585 (ci) and 36157668802 (CD) of 7983f03, and for 36142971533 attempt 1 (the 95ba6af rollback).
- [ ] Test: `python3 deploy/scripts/capture-cicd-runs.py --self-test` checks the secret refusal and the trimming on fixtures. Watch it fail before implementing, then pass.
- [ ] Commit: "Capture one real CI run, one CD run and the rollback as data for the CI/CD page".

### Task 2: The page, nav and tile

**Files:** create `console/ui/src/app/cicd-page.ts`; modify `app.ts` (the `cicd` surface, nav link, route), `public-home.ts` (CI/CD tile).

- [ ] `CicdPage`:
  - Starts with the intro and `<rb-pipeline-diagram />`.
  - Each run is a card:
    - The header shows the workflow, job, result, when it ran (GMT+8), total duration, and a link to GitHub.
    - Each step is a `<button>` row with a tick or cross, the name and the duration.
    - An open row shows a dark monospace log with line numbers, then the explanation.
  - The rollback card starts collapsed.
  - Three guard cards (automatic rollback, drift check, what CD may not do) open notes 07 and 06 through an `open` output.
- [ ] The Overview gets a CI/CD tile; the nav gets "CI/CD" after "Cluster".
- [ ] Build, then check in the browser at 1680 and 390: no sideways scroll, a step expands, the rollback run reads correctly.
- [ ] Commit: "Add the CI/CD page: the pipeline, one real run of each workflow with its logs, and what it guards against".
