# TrueHD Secondary Suspects Triage Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture and triage the remaining non-primary Kodi/Media3 parity differences that could still explain late-stream TrueHD stutter after the zero-write cadence fix.

**Architecture:** This plan is intentionally read-heavy and proof-oriented. It does not bundle production changes into the same pass as the current cadence fix. Instead, it writes down the remaining suspect differences, adds lightweight proof points where needed, and creates a safe order for future follow-up plans if audio is still not normalized.

**Tech Stack:** Android, Media3, JNI/C++, Kodi AE references, Kotlin/JUnit, Gradle, Markdown docs

---

## File Map

### Documentation files

- Create: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-secondary-parity-suspects.md`
  - inventory of remaining parity suspects and why each is or is not the next fix boundary
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-full-parity-audit.md`
  - add a pointer to the suspect inventory

### Optional proof files

- Modify only if needed: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`
  - only for non-invasive source-structure tests that document existing divergence
- Modify only if needed: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt`
  - only for non-invasive source-structure tests that document existing divergence

## Guardrails

- Do not change production runtime behavior in this plan.
- Do not touch transport, MAT packing, route logic, or Java contract behavior.
- If a suspect needs runtime proof, capture it as documentation or a source-structure test first.
- Do not mix triage with implementation of the current zero-write cadence fix.

---

### Task 1: Freeze The Remaining Suspect List

**Files:**
- Create: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-secondary-parity-suspects.md`
- Reference: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-full-parity-audit.md`

- [ ] **Step 1: Write the suspect inventory**

Include these sections:
- why Kodi is the immediate behavioral reference for native cadence
- why Media3 remains the outer contract guardrail
- remaining suspects:
  - Java startup reservoir / handoff controller
  - layered native ownership model
  - startup retry heuristics
  - generic `AudioTrack` buffer sizing
  - native `HasPendingData()` truth
  - larger retry bookkeeping complexity

- [ ] **Step 2: For each suspect, record**

For each suspect add:
- exact file paths
- reference file paths
- why it is a real suspect
- why it is not the first next patch

- [ ] **Step 3: Add the follow-up priority order**

Write the post-cadence-fix order:
1. native `HasPendingData()` truth
2. `AudioTrack` buffer sizing
3. startup reservoir / handoff complexity
4. larger ownership-model simplification

- [ ] **Step 4: Commit the suspect inventory**

```bash
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality add \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-secondary-parity-suspects.md
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality commit -m "docs: capture truehd secondary parity suspects"
```

---

### Task 2: Link The Suspect Inventory From The Main Audit

**Files:**
- Modify: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-full-parity-audit.md`
- Reference: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-secondary-parity-suspects.md`

- [ ] **Step 1: Add a short section**

Add:
- a pointer to the suspect inventory
- a statement that these differences remain documented but intentionally deferred behind the
  current zero-write cadence fix

- [ ] **Step 2: Verify the main audit still has one primary gap**

Read the audit and confirm:
- the primary active gap remains steady-state zero-write cadence
- the secondary suspects are clearly separated from the primary target

- [ ] **Step 3: Commit the audit link**

```bash
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality add \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-full-parity-audit.md
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality commit -m "docs: link truehd secondary suspects from full audit"
```

---

### Task 3: Add Optional Source-Structure Proof For The Highest-Value Secondary Suspects

**Files:**
- Modify if needed: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt`
- Modify if needed: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt`

- [ ] **Step 1: Add a source-structure test for layered native ownership**

Only if useful, add a non-failing documentation-style test that asserts the active engine still
contains:
- `pendingPassthroughInput_`
- `startupPendingPackedOutput_`
- `steadyStatePendingPackedOutput_`

- [ ] **Step 2: Add a source-structure test for Java startup reservoir presence**

Only if useful, add a non-failing documentation-style test that asserts the active Java sink still
contains:
- `handleTrueHdStartupBuffer(...)`
- `hasPendingPassthroughStartupWindow()`

- [ ] **Step 3: Run the focused tests**

Run:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests "*KodiTrueHdAEEngineSourceStructureTest*" --tests "*KodiTrueHdNativeAudioSinkSourceStructureTest*"
```

Expected:
- PASS

- [ ] **Step 4: Commit only if tests add real value**

If these tests are kept:

```bash
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality add \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdAEEngineSourceStructureTest.kt \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/app/src/test/java/com/nexio/tv/debug/passthrough/KodiTrueHdNativeAudioSinkSourceStructureTest.kt
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality commit -m "test: document truehd secondary parity suspects"
```

If not:
- skip this task without forcing extra test noise into the branch

---

### Task 4: Define The Follow-Up Decision Gate

**Files:**
- Reference: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-zero-write-cadence-parity.md`
- Reference: `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-secondary-parity-suspects.md`

- [ ] **Step 1: Record the handoff rule**

Document the rule in the suspect inventory:
- if the zero-write cadence fix materially improves audible quality, stop and do not reopen
  secondary suspects
- if transport stays clean but audible quality still fails, the next follow-up plan should target
  exactly one of the secondary suspects in priority order

- [ ] **Step 2: Commit the decision gate**

```bash
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality add \
  /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-secondary-parity-suspects.md
git -C /Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality commit -m "docs: add truehd secondary suspect decision gate"
```
