---
sessionId: session-261014-135312-ln3a
---

# Requirements

### Active Follow-up
Replace the deprecated `AnActionEvent.createFromAnAction` call in `src/test/kotlin/info/chrzanowski/stickyprojectview/StickyAncestorNavigationTest.kt`. The user confirmed a test-only migration, not an explanation or broader API cleanup.

The preceding seven-stage runtime/settings/CI/release implementation is complete and is not reopened by this follow-up.

### Acceptance Criteria
- Use the supported, action-aware `AnActionEvent.createEvent` overload available in the configured IDEA `2025.3.6.1` SDK; do not suppress the deprecation or upgrade dependencies.
- Preserve the project data context, `ActionPlaces.UNKNOWN`, non-UI invocation, absent input event, and independent event presentation.
- Retain all existing registration, EDT, shortcut, unavailable/disabled-state, navigation, and stale-choice assertions and preference cleanup.
- Add focused guards that the event resolves the fixture project and action updates cannot mutate the registered action's template presentation.
- Leave production code, plugin registrations, build configuration, other deprecated APIs, and unrelated tests unchanged.

### Delivery Contract
Carry forward the user's dedicated changelog-entry/separate-commit requirement: each of the two follow-up stages gets one bullet under the current `CHANGELOG.md` → `Unreleased` → `Changed` section and its own path-limited commit.

Implementation commits may contain only the selected test file and `CHANGELOG.md`. Preserve unrelated staged/untracked work, including the review documents, `.agents/`, and `.junie/`; do not reset the index, rewrite history, bypass existing signing, push, or release. Report commit SHAs and actual validation outcomes.

# Technical Design

### Investigation Basis
- `StickyAncestorNavigationTest.testActionRegistrationAndUnavailableStates()` uses the removal-deprecated factory at line 23. An exact search found no other `createFromAnAction` calls or existing `createEvent` helpers under `src`.
- `src/main/kotlin/info/chrzanowski/stickyprojectview/NavigateStickyAncestorAction.kt` reads `event.project` and modifies `event.presentation.isEnabled`. Losing the project could make the unavailable-state assertion pass for the wrong reason; sharing the template presentation could leak changes to later tests.
- `build.gradle.kts` uses the platform test framework, JUnit 4, and Java 21. `gradle.properties` currently selects IDEA `2025.3.6.1`; its indexed SDK declares the replacement overload. Reuse the existing `BasePlatformTestCase` and preference-restoration pattern.

### Factory Replacement
Import `com.intellij.openapi.actionSystem.ActionUiKind` and replace only the existing event construction:

```kotlin
val event = AnActionEvent.createEvent(
    action,
    SimpleDataContext.getProjectContext(project),
    null,
    ActionPlaces.UNKNOWN,
    ActionUiKind.NONE,
    null,
)
```

This matches the deprecated wrapper's delegation: the null presentation requests a clone of the action template, and the final null preserves the absent input event. Use the action-aware overload to retain action-specific event initialization. No helper abstraction, constructor fallback, or suppression is needed for one call site.

### Regression Guards
Extend the same test method to assert `event.project` is the fixture project and `event.presentation` is not the registered action's template instance. Capture the template's enabled state before updates and verify that unavailable/disabled updates leave it unchanged. Preserve every existing assertion, the `try/finally` settings restoration, and the separate snapshot/navigation test.

# Validation

- During implementation, run `./gradlew test --tests 'info.chrzanowski.stickyprojectview.StickyAncestorNavigationTest'` on Java 21 after the factory migration. Confirm both existing test methods pass and compilation no longer reports the selected deprecated call.
- After adding the context/presentation guards, run `./gradlew test` to catch fixture-state leakage across the unit suite. Report actual counts and failures rather than reusing historical results.
- Confirm there are no remaining `createFromAnAction` calls under `src`, no new deprecation suppressions, and no production/build changes.
- UI integration, packaging, and compatibility-verifier runs are unnecessary for this test-only change. No builds or tests were executed during this planning update.

# Delivery Steps

### ✓ Step 1: Migrate ancestor action-event construction
The unavailable-state test uses the supported factory with the same event semantics and unchanged existing assertions.

- In `StickyAncestorNavigationTest.kt`, import `ActionUiKind` and replace the deprecated call with the six-argument action-aware `createEvent` invocation specified in Technical Design.
- Retain the project data context, cloned presentation, unknown place, `NONE` UI kind, and null input event; leave preference cleanup and snapshot/navigation coverage intact.
- Run the focused test class, including compilation against the configured SDK.
- Add the `Unreleased` → `Changed` bullet: `Replace deprecated action-event construction in ancestor-navigation tests.`
- Commit only the test file and that changelog entry as `test: migrate ancestor action event factory`.

### ✓ Step 2: Guard event context and presentation isolation
The unavailable-state coverage detects missing project context and mutations leaking into the action template.

- Extend `testActionRegistrationAndUnavailableStates()` with project-identity and independent-presentation assertions.
- Capture the template's enabled state before action updates and assert it remains unchanged after both unavailable and disabled updates, without changing the existing settings restoration.
- Run the full unit suite and confirm the deprecated factory is absent under `src`; record actual results and any blockers.
- Add the `Unreleased` → `Changed` bullet: `Guard ancestor action tests against lost project context and shared presentation state.`
- Commit only the test file and that changelog entry as `test: guard ancestor event context and presentation`.