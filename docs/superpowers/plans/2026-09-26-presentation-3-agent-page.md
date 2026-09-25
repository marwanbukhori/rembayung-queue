# AI Agent Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** A page that describes the run agent, marked "In progress", with an example report that shows how every claim rests on a fact.

**Architecture:** One static Angular component, `AgentPage`, with the `agent` surface, a nav link and an Overview tile. It has no backend. The example report is data inside the component, labelled as written by hand.

**Tech Stack:** Angular 20 standalone components and signals.

**Spec:** `docs/superpowers/specs/2026-09-25-site-presentation-design.md` §6. The agent design it describes is in `docs/superpowers/specs/2026-09-25-cluster-inspector-and-run-agent-design.md` §8.

## Global Constraints

- The "In progress" label sits at the top of the page and on the tile (§6).
- The example report is labelled as written by hand from the real 2026-09-25 rush, never presented as the agent's output (§6.4).
- The claims match the agent design (§8): at most 5 read-only tools, numbers checked against cited facts, one retry, then a deterministic fallback.
- Nav order: Overview · Run a simulation · Cluster · CI/CD · AI Agent · Build notes.

## Review Focus

1. **Clicking a fact chip on a phone.** Expected: the fact's label and value show inline, and nothing overflows at 390px.
2. **A claim citing two facts.** Expected: both chips show, and each opens its own fact.
3. **Keyboard use.** Expected: fact chips are buttons.
4. **Tiles at 900–1280px.** Expected: the five tiles fill the grid with no hole.
5. **The label.** Expected: "In progress" and "example, written by hand" are visible without scrolling into the report.

---

### Task 1: The page, nav and tile

**Files:** create `console/ui/src/app/agent-page.ts`; modify `app.ts` and `public-home.ts`.

- [ ] `AgentPage`, in this order:
  - crumbs, then an h1 with an "In progress" badge;
  - "What it does";
  - the loop as five boxes: facts → investigate (≤5 tools) → report → validate → store, with the fallback noted under validate;
  - the tools table;
  - the model;
  - the example report: three lists (went well, caught, look at), each claim followed by fact chips. A chip toggles a line showing that fact's label and value. Below the lists, the trail.
- [ ] Add the `agent` surface to `app.ts`, with the nav link between CI/CD and Build notes.
- [ ] Add an AI Agent tile to the Overview, tagged "In progress".
- [ ] Build, then check in the browser at 1680, 1100 and 390.
- [ ] Commit: "Add the AI Agent page: the bounded agent's design, marked in progress, with an example report".
