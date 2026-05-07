# Stream Selection Default Enforcement Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Force uniform stream formatting on permanently, and enable the requested stream-selection and parallel-connection defaults once on upgrade while keeping later manual toggle changes respected.

**Architecture:** Keep the behavior centered in `PlayerSettingsDataStore` by extending the existing one-time migration pattern and by making uniform stream formatting read back as always enabled. Update the settings UI to remove only the uniform-formatting toggle while preserving the other user-facing toggles.

**Tech Stack:** Kotlin, AndroidX DataStore Preferences, Jetpack Compose TV, JUnit4

---

### Task 1: Add regression coverage for migration behavior

**Files:**
- Create: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreMigrationTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`

- [ ] **Step 1: Write failing tests for the requested defaults and one-time upgrade behavior**
- [ ] **Step 2: Run the focused test target and verify it fails for the expected missing behavior**
- [ ] **Step 3: Extract minimal migration helpers in `PlayerSettingsDataStore.kt` so the tests can execute without Android context wiring**
- [ ] **Step 4: Run the focused test target and verify it passes**

### Task 2: Enforce production defaults and UI behavior

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PlaybackAutoPlaySettings.kt`

- [ ] **Step 1: Update stream-selection migration keys and parallel-connection defaults**
- [ ] **Step 2: Force uniform stream formatting on in persisted reads and writes**
- [ ] **Step 3: Remove the uniform stream formatting settings row from the autoplay screen**
- [ ] **Step 4: Keep the remaining toggles writable after the one-time migration**

### Task 3: Verify the change end to end

**Files:**
- Test: `app/src/test/java/com/nexio/tv/data/local/PlayerSettingsDataStoreMigrationTest.kt`

- [ ] **Step 1: Run the focused migration test target**
- [ ] **Step 2: Run one existing nearby regression target to catch collateral damage**
- [ ] **Step 3: Review the diff for only the intended files and behavior**
