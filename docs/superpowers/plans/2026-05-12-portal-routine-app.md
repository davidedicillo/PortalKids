# Portal Routine App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify a sideloadable Android Home app for the Meta Portal routine board described in the product spec.

**Architecture:** Create a native Kotlin Android app using Android Views for the child-facing Home surface, Room for local structured data, a routine engine shared by UI/admin behavior, and NanoHTTPD for home-network parent administration. Keep logic testable outside Android where possible, then verify the APK on the connected Portal through ADB.

**Tech Stack:** Kotlin, Android Gradle Plugin, Android Views, Room, NanoHTTPD, JUnit.

---

### Task 1: Project And Toolchain

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Modify: `.gitignore`

- [ ] Write the Android project scaffold for package `com.davidedicillo.portalroutine`.
- [ ] Configure min SDK for Android 9 compatibility and compile SDK from the local SDK.
- [ ] Add Kotlin, Room, NanoHTTPD, and JUnit dependencies.
- [ ] Build once to verify Gradle and SDK wiring.

### Task 2: Routine Engine Tests First

**Files:**
- Create: `app/src/test/java/com/davidedicillo/portalroutine/core/RoutineEngineTest.kt`
- Create: `app/src/main/java/com/davidedicillo/portalroutine/core/RoutineEngine.kt`

- [ ] Write failing tests for active window selection before first window, between windows, after last window, and manual override expiration.
- [ ] Implement only enough `RoutineEngine` code to pass.
- [ ] Add tests for progress calculation and all-children-complete celebration eligibility.
- [ ] Run `./gradlew testDebugUnitTest`.

### Task 3: Local Data And Repository Behavior

**Files:**
- Create: `app/src/main/java/com/davidedicillo/portalroutine/data/*`
- Create: `app/src/test/java/com/davidedicillo/portalroutine/data/RoutineRepositoryTest.kt`

- [ ] Write failing repository tests for seeded defaults, completion toggling, clearing, and reset.
- [ ] Implement Room entities, DAO interfaces, database, and repository.
- [ ] Verify tests pass.

### Task 4: Portal Home Experience

**Files:**
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/davidedicillo/portalroutine/HomeActivity.kt`
- Create: `app/src/main/java/com/davidedicillo/portalroutine/ui/*`
- Create: `app/src/main/res/values/*`

- [ ] Declare a Home-capable `HomeActivity`.
- [ ] Build the full-screen 1280x800-friendly board with header, two child columns, large task cards, progress/reward state, and celebration overlay.
- [ ] Add first-launch parent PIN setup and parent settings escape hatch.
- [ ] Verify task toggles persist after activity recreation.

### Task 5: Parent Admin Server

**Files:**
- Create: `app/src/main/java/com/davidedicillo/portalroutine/admin/*`
- Create: `app/src/main/assets/admin/*`

- [ ] Start a NanoHTTPD server on port `8080`.
- [ ] Require parent PIN login for modifying JSON endpoints.
- [ ] Serve a phone/laptop-friendly admin page for children, windows, tasks, reset settings, manual reset, and history.
- [ ] Surface the local admin URL in parent settings.

### Task 6: Build And Device Verification

**Files:**
- Build artifact: `app/build/outputs/apk/debug/app-debug.apk`

- [ ] Run unit tests.
- [ ] Build the debug APK.
- [ ] Install on `192.168.4.38:5555`.
- [ ] Launch `HomeActivity`, capture a screenshot, and verify the board renders.
- [ ] Toggle a task through ADB input or manual-device-safe launch checks, restart, and verify persistence where possible.
- [ ] Fix any failures and repeat the relevant test/build/device check.
