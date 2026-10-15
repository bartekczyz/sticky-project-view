# Sticky Project View: current-project review

## Scope, evidence and conclusions

This reviews the current checkout, not a commit diff: runtime Kotlin/resources, both test source sets, Gradle, workflows, quality configuration and licensing metadata. Product promises, architecture, calculator formulas, coordinate systems, lifecycle contracts and operational instructions live in [PROJECT_KNOWLEDGE.md](PROJECT_KNOWLEDGE.md). Generated descriptors/reports were inspected only as validation evidence, not source deliverables; caches, IDE-local state and binary implementation contents are excluded from the source review.

Most important conclusions:

- The plugin preserves valuable native tree painting and disposable ownership; the eight existing unit/rendering tests pass.
- Horizontal scrolling exposes inconsistent painting coordinates and unconditional horizontal resets (R2); clearing revealed-path state can leave a stale stack (R4). Both have triggers in fixed-height configurations, unlike the conditional variable-height lookup defect (R1), whose shipped Project View reachability remains unverified.
- The overlay handles only left presses on sticky content and does not forward other input or decoration-strip presses (R14). Geometry invalidation deserves focused regressions; delayed attachment, disposal threading and bounded navigation remain risks, not demonstrated IDE failures.
- The IDE test exercises service-driven selection and inspection, not sticky-row clicks or passive startup/update observation. Its inspection calls can repair the state being checked.
- Packaging passes but release readiness is incomplete: the artifact has no change notes (T1), coverage upload has no producer (T2), manual UI CI does not run the IDE scenario (T3), and the changelog PR condition references a nonexistent output (T4). The cached IDE scenario passed on 2026.3 EAP; baseline UI coverage and completed verifier results remain missing. The initial timeouts are not reproduced plugin failures.
- Publishing/signing environment bindings are valid defaults in Platform Gradle Plugin 2.14.0. Do not add a speculative missing-credentials-binding finding. License/header provenance needs clarification, not a legal conclusion (T8).

**Evidence classes:** **source-confirmed discrepancy** means the code establishes the mismatch or unnecessary work, without claiming an executed end-user reproduction; **unverified risk** means an outcome depends on platform behavior, timing or an untested geometry; **design suggestion** is a subjective trade-off. No failing runtime defect was reproduced by the original executor; R4 includes a separately attributed reviewer reproduction. Passing existing tests does not verify the uncovered scenarios. Confidence below applies to the stated evidence, not to an assumed frequency in users' projects. Severity uses High (core behavior unavailable/incorrect in a supported scenario), Medium (conditional behavior/performance/reliability problem), Low (limited impact or diagnostic concern); priority is separately ordered below.

Source references below are repository-relative; production filenames refer to [`src/main/kotlin/info/chrzanowski/stickyprojectview/`](../src/main/kotlin/info/chrzanowski/stickyprojectview/). Line ranges refer to this reviewed checkout.

## Useful existing design choices

- **Native appearance:** `StickyTreePanel.kt:83–110` clips and translates the original `JTree.paint`, retaining tree UI chrome outside cell renderers. The chrome-marker and overlapping-stripe tests validate this choice for their fixtures. Preserve this approach when fixing coordinates rather than replacing it with hand-built label renderers.
- **Owned cleanup:** `StickyProjectViewService.kt:9–16`, `ProjectViewStickyController.kt:26–34,101–110,181–189` and `StickyTreeManager.kt:66–75,204–210` connect project, controller and attachment lifetimes. Detach removes the overlay, disposes listeners and restores the saved layout. No listener leak has been demonstrated.
- **Event coverage and caching:** manager model/expansion/row-height events force recalculation; selection/focus repaint native appearance; unchanged viewport geometry skips the expensive calculator (`StickyTreeManager.kt:39–62,77–109,138–155`). Model replacement explicitly removes the old listener and installs the new one.
- **Bounded and explicit geometry:** calculator iteration is bounded by row count, empty rectangles/trees exit early, half-viewport budgeting limits content, and negative row offsets model subtree handoff (`VisualStickyTreeItems.kt:19–111,195–196`). The fixed-height handoff and child-under-parent paint tests verify useful boundary behavior, not every possible tree.
- **Stable click identity:** navigation resolves the item's current row from its `TreePath` rather than relying solely on a stale row index (`StickyTreeManager.kt:124–135`). Revealed-path handling intentionally avoids immediately re-pinning the clicked item.

## Runtime findings

### R1 — Forward-only lookup is incorrect if variable-height mode is used

- **Category / severity / confidence / evidence:** geometry correctness / Medium, conditional on variable-height mode / High for arithmetic discrepancy, unverified shipped Project View reachability / source-confirmed discrepancy; no executed reproduction.
- **Location:** `VisualStickyTreeItems.kt:170–192` (especially 176–183); consumers at 40–54. Tests force `rowHeight = 20` (`VisualStickyTreeItemsTest.kt:355–362`).
- **Trigger and impact:** `JTree.rowHeight == 0` means variable heights, but the estimate divides pixel y by 1, clamps to the final row, subtracts one and scans only forward. For example, with 100 actual 20-pixel rows and `topY = 200`, it starts at row 98 instead of row 10. It cannot recover the earlier intersecting row; the ancestor chain may come from another subtree and suppress or misidentify sticky rows. Negative row-height values have the same arithmetic issue. Reachability of either mode in the shipped Project View is unverified: reviewer inspection of IDEA 2025.3 `app.jar` found theme `Tree.rowHeight` defaults of 24 in `themes/expUI/expUI_light.theme.json` and `expUI_dark.theme.json`, and 20 in `darcula.theme.json`; `BasicTreeUI.installDefaults` installs the LaF value and `DefaultTreeUI.installDefaults` does not reset it to zero. This is counter-evidence to default exposure, not proof that every pane/theme stays fixed-height. The forward estimate works for uniform positive-height rows.
- **Remedy:** locate the row from actual tree geometry (e.g. closest-row lookup with bounds correction), supporting a backward correction as needed; retain explicit null-bounds/end handling.
- **Verification:** follow-up variable-height renderer fixtures with `rowHeight = 0`, mixed row sizes and adjacent branches; compare selected ancestors against a full bounds scan at each boundary, including deep y values and hidden/visible roots.

### R2 — Sticky painting and navigation ignore horizontal viewport position

- **Category / severity / confidence / evidence:** rendering/navigation correctness / Medium / High / source-confirmed discrepancy.
- **Location:** `StickyTreePanel.kt:96–107`; `ProjectViewStickyController.kt:198–205,152–157`; `StickyTreeManager.kt:134`.
- **Trigger and impact:** at viewport x > 0, overlay placement uses viewport bounds but tree painting translates only y, so sticky content stays in tree x coordinates while ordinary rows are shifted left. A deep row with `rowBounds.x >= panel.width` is skipped even when horizontal scrolling would make it visible. Both sticky navigation and service selection set x to zero, discarding the user's horizontal position. Whether deliberately left-anchored sticky rows are desired is a product decision, but this is not horizontal alignment with the underlying tree.
- **Remedy:** specify the intended horizontal contract, translate paint/clip coordinates consistently with it, and preserve or deliberately reveal x on navigation rather than resetting unconditionally.
- **Verification:** a narrow viewport with long/deep rows at several nonzero x positions; image comparison with native rows, followed by real sticky clicks and assertions on final x. Existing tests use x = 0.

### R3 — Renderer/font changes repaint cached geometry without recalculation

- **Category / severity / confidence / evidence:** update invalidation / Medium / High for missing invalidation, Medium for visible impact / source-confirmed discrepancy with conditional runtime impact.
- **Location:** `StickyTreeManager.kt:88–99,138–155,158–168`; `StickyTreePanel.kt:83–91,125–129`.
- **Trigger and impact:** a font/renderer change that changes row bounds while the viewport rectangle remains equal leaves cached paths, offsets and total height unchanged. Painting/hit testing read new row heights against old calculator offsets. Fixed-height rows may only change appearance and be unaffected; an incidental viewport/model event can also repair state.
- **Remedy:** invalidate geometry after layout-affecting properties, with attention to when Swing updates row bounds; include UI/root-visibility-related changes if those alter geometry in supported panes.
- **Verification:** mutate renderer/font on an attached, stationary variable-height tree; drain EDT layout work and assert updated paths, content height and click intervals without inspection getters or manual `forceUpdate`.

### R4 — Clearing the revealed-path exception does not invalidate its calculated stack

- **Category / severity / confidence / evidence:** navigation/update state / Medium / High / source-confirmed discrepancy with reviewer-reported fixed-height reproduction; not rerun in this revision pass.
- **Location:** `StickyTreeManager.kt:38–45,88–99,124–135`; `VisualStickyTreeItems.kt:54–58,72–78`.
- **Trigger and impact:** after sticky navigation establishes a revealed item, changing selection to another path clears `revealedStickyItem` but only repaints. In the fixed-height test-7 geometry, navigation can converge to `targetY = 0` with stack `[parent]`, permitted by the revealed-path equality exception. Selecting another row without moving the viewport leaves that stack and its shadow at y=0, whereas a fresh no-exception calculation returns empty. This concrete divergence was reported by the reviewer, not rerun in this pass. Clamped or non-converged targets may also retain exception-dependent paths/offsets until geometry changes or a forced event occurs; exclusion of the clicked row alone is not proof of divergence because its top can equal the lookup position either way.
- **Remedy:** force a recalculation when selection actually clears a live revealed-path exception; avoid recalculating every ordinary selection change unnecessarily.
- **Verification:** click a sticky ancestor, change selection at constant viewport geometry, then compare the displayed stack with a fresh no-exception calculation. Assert before calling any service inspection method.

### R5 — Delayed tree availability has no dedicated attachment retry

- **Category / severity / confidence / evidence:** startup/lifecycle / Medium / Medium / unverified risk.
- **Location:** `ProjectViewStickyTreeDecorator.kt:15–20`; `ProjectViewStickyController.kt:26–41,81–96,113–129`.
- **Trigger and impact:** startup's one queued initialization may find no current pane; a pane-shown callback may find a tree without its scroll pane. Those paths do not register a readiness retry. If the platform does not issue another pane-shown event once the tree is ready, ordinary scrolling cannot attach a manager that does not exist. Inspection getters can subsequently attach and conceal this gap. Actual IDE ordering was not observed.
- **Remedy:** confirm platform readiness guarantees first; otherwise use a disposable, bounded readiness hook/retry tied to the active pane, not indefinite polling.
- **Verification:** delay pane/tree/scroll-pane creation through startup and pane switching, checking panel presence passively before any service inspection. Also close the project during the delay.

### R6 — EDT ownership and restoration assume cooperative lifecycle callers

- **Category / severity / confidence / evidence:** lifecycle/threading and interoperability / Medium / Medium / unverified risk.
- **Location:** `ProjectViewStickyController.kt:30–41,77–79,134–143,160–169,181–188`; `StickyTreeManager.kt:204–210`.
- **Trigger and impact:** startup and inspection wrappers dispatch to EDT, but pane callbacks, public `initialize` and disposal do not enforce EDT themselves. A non-EDT caller/disposal could mutate Swing/listener state off-thread. Detach also unconditionally restores the saved layout, potentially overwriting a later layout installed by another participant. Same-tree attachment reuse checks tree identity, not scroll-pane identity (81–95), so moving the same tree to another scroll pane may leave the manager observing the old viewport. These conditions are not demonstrated normal IDE events.
- **Remedy:** document/enforce lifecycle EDT contracts consistent with platform disposal rules; retain identity of the installed layout and restore only when still owned; consider scroll-pane identity in reuse if reparenting is supported.
- **Verification:** pane replacement and same-tree reparenting, project close/plugin unload with EDT assertions, repeated attach/detach listener counts, and another layout installed between attach/detach. Verify the original layout survives normal detach.

### R7 — Four navigation previews are a bound, not a convergence guarantee

- **Category / severity / confidence / evidence:** navigation robustness / Medium / Medium / unverified risk.
- **Location:** `StickyTreeManager.kt:176–201`; tests at `VisualStickyTreeItemsTest.kt:207–292`.
- **Trigger and impact:** target y depends on the stack at that y; the loop returns after four adjustments even without a fixed point. Deep chains, height-budget transitions, handoffs and viewport-end clamping could produce a target that does not reveal the row as intended. No oscillation or failing deep case was reproduced. Clamping near the bottom also means exact top alignment need not be achievable.
- **Remedy:** test convergence and actual visibility invariants before changing the algorithm; if necessary detect cycles or choose a bounded fallback that guarantees visible content, distinguishing feasible alignment from clamping.
- **Verification:** deep/mixed-height paths across many viewport sizes, starts near subtree boundaries and end-of-tree targets; assert final row visibility against recomputed stack and decoration, not only a shallow expected offset.

### R8 — Paint eligibility and hit-test eligibility differ

- **Category / severity / confidence / evidence:** interaction consistency / Low / High / source-confirmed discrepancy.
- **Location:** `StickyTreePanel.kt:87–99,120–133,169–175`; calculator height at `VisualStickyTreeItems.kt:70–71`.
- **Trigger and impact:** paint skips null row bounds and rows whose indentation x is beyond panel width; hit testing still assigns their vertical intervals using fallback heights. An invisible interval may therefore accept a click. Panel paint/hit/decoration heights also coerce to at least the tree default, while calculator heights coerce only to 1; mismatched actual/default bounds can create overlapping intervals. Reverse paint order and forward hit order deliberately favor the parent during handoff; overlap itself is not a proven bug.
- **Remedy:** define clickability for blank full-row regions explicitly and share placement/eligibility calculations where visual and clickable rows should coincide, preserving intended parent precedence.
- **Verification:** null/missing bounds, deep indentation beyond width, partial handoffs, actual heights below a positive default; click interval edges and decoration-only pixels. Existing stripe test validates paint order but not corresponding overlap clicks.

### R9 — Descendant scans repeat on the scrolling path

- **Category / severity / confidence / evidence:** performance / Medium / High for work structure, unmeasured impact / unverified performance risk.
- **Location:** `VisualStickyTreeItems.kt:39–48,84,128–167`; `StickyTreeManager.kt:39,88–109,176–197`.
- **Trigger and impact:** for each accepted ancestor, `lastVisibleSubtreeRow` scans successive displayed descendants, including nested ranges already visited for other ancestors. With N displayed rows and D sticky candidates this can approach O(D*N) row visits; each descendant comparison loops over ancestor path segments and obtains path arrays. Repeated ancestor-chain construction adds depth-dependent work. A viewport event executes this synchronously; navigation may run four previews, another diagnostic preview, then a forced calculation. The budget limits accepted depth but not subtree breadth. No latency or allocation benchmark was collected.
- **Remedy:** profile large expanded trees first; consider per-calculation subtree-end reuse or a geometry/index approach that avoids repeated ranges while respecting model invalidation. Do not sacrifice native painting or introduce broad abstractions without evidence.
- **Verification:** count row/path queries and profile EDT time for broad and deep expanded fixtures at multiple viewport heights; compare scaling and preserve handoff results. Separate debug-on from debug-off runs.

### R10 — Diagnostic strings and an extra preview are built with debug disabled

- **Category / severity / confidence / evidence:** performance/diagnostics / Low / High / source-confirmed unnecessary work, no measured slowdown.
- **Location:** `VisualStickyTreeItems.kt:21–25,50–105`; `StickyTreePanel.kt:84,93–101,113`; `StickyTreeManager.kt:127–131`; guard at `StickyTreeDebugLogger.kt:149–152`.
- **Trigger and impact:** each recalculation builds the steps list and interpolated path/bounds descriptions before entering the logger guard; each paint similarly builds placements. Navigation unconditionally recalculates a preview height used only for logging. Frequent EDT scrolling/painting therefore allocates diagnostic text even with logging disabled. The logger's own visible-row/stack formatting is correctly inside the guard.
- **Remedy:** gate caller-side detail collection and the logging-only preview, or make diagnostics lazy without moving UI reads off the EDT.
- **Verification:** allocation/query-count comparison with debug disabled/enabled on repeated scroll, paint and clicks; assert identical geometry and retained diagnostics when enabled.

### R11 — Debug snapshots contain project labels

- **Category / severity / confidence / evidence:** diagnostic data handling / Low / High / source-confirmed behavior; retention policy is a design suggestion.
- **Location:** `StickyTreeDebugLogger.kt:89–97,101–145`; diagnostic callers in R10.
- **Trigger and impact:** when debug is enabled, visible row labels and full tree-path component text are written to logs. Support bundles may expose project/directory/file names. This is not a claim of network transmission or secret-file content disclosure; disabled-debug caller allocations are in-memory work, not logged output.
- **Remedy:** document what debug logging includes and consider redaction/opt-in guidance for shared logs rather than removing useful local diagnostics by default.
- **Verification:** enable this logger with synthetic sensitive-looking labels and inspect only the resulting diagnostic output; confirm debug-off emits no such messages. Not executed here.

### R12 — Integration observations can repair the state under test

- **Category / severity / confidence / evidence:** test validity / Medium / High / source-confirmed coverage limitation.
- **Location:** `ProjectViewStickyController.kt:44–57,91–95,126–132`; `StickyProjectViewUiTest.kt:55–78`.
- **Trigger and impact:** `isAttached` and getters refresh attachment and force updates on an existing tree; they may instead detach for an absent pane/tree/scroll pane or replace a changed tree. Thus polling can create or repair the overlay before asserting it. The IDE test selects via the service and checks stack labels/positive height, not physical sticky clicks, native appearance or passive startup/update success. This limits the test's claim, not proof that production startup is broken.
- **Remedy:** add passive attachment/stack observations for lifecycle tests, and a separate real UI scroll/click scenario; keep service-driven inspection as useful diagnostics.
- **Verification:** deliberately suppress normal initialization or an update in a follow-up regression exercise and verify passive tests detect it; assert attachment before invoking repairing getters. Then exercise native input and compare selected target paths.

### R13 — Selection helper can report an unrelated existing selection as success

- **Category / severity / confidence / evidence:** integration interface correctness / Medium / High / source-confirmed discrepancy.
- **Location:** `ProjectViewStickyController.kt:59–74,146–157`; `StickyProjectViewUiTest.kt:60–68`.
- **Trigger and impact:** a resolvable requested file plus a rejected selection callback can still pass `waitFor` because it waits for processing, not successful completion. The continuation checks only whether any current selection has a displayed row, then scrolls it. The helper can return true for an old/unrelated selection. Unresolvable files return false early. The integration test's final ancestor checks add evidence, but it does not assert the selected leaf equals `DeepFile120.txt`.
- **Remedy:** require callback success and verify the requested file's actual selected path; document a background-only blocking contract or avoid waiting on EDT (which can block event delivery for up to 30 seconds).
- **Verification:** preselect another displayed row, request a resolvable but rejected/unsupported selection, and assert false/no false target claim; cover timeout and missing-file cases and assert the exact selected file after success. No rejected-callback or EDT-wait failure was reproduced here.

### R14 — Overlay intercepts input outside its supported left-press action

- **Category / severity / confidence / evidence:** interaction correctness / Medium / High for local handling and geometry, unverified IDE-wide effect / source-confirmed input-handling discrepancy; no end-to-end IDE reproduction.
- **Location:** `StickyTreePanel.kt:33–48,54–67,120–135,169–181,197–215`; `ProjectViewStickyController.kt:134–140,199–205`.
- **Trigger and impact:** the panel has a mouse listener and sits at z-order zero over the viewport, including a separator and shadow of at least five pixels. Under ordinary Swing targeting, presses within these bounds go to the overlay, not the underlying tree; returning without consuming does not redispatch to a sibling. Only left presses on content hits invoke navigation. A press in the decoration strip has no item and no forwarding, so top pixels of the real row beneath it are not selectable through the tree's normal handler. Right presses receive no native context-menu handling here; double presses invoke the same navigation action rather than the tree's native double-click action. Native drag initiation and tree tooltip/hover handling are not delegated by this panel either. The source establishes missing delegation, not that every IDE action is unavailable: global/platform handlers might supply some behavior, and their effects were not tested. Nonopacity does not itself provide input transparency; this finding does not claim all event types (for example wheel events) are intercepted identically.
- **Remedy:** exclude decoration-only pixels from the interactive hit region or redispatch unhandled events to the underlying tree with converted coordinates. Define sticky-row context-menu, double-click, drag and hover semantics explicitly; forward supported interactions to the represented path without duplicating the left-navigation action.
- **Verification:** dispatch real Swing-targeted left/right presses at separator/shadow y-coordinates and assert underlying tree selection/popup behavior; directly dispatching only to the panel is insufficient to test routing. Exercise right/double-click, drag and tooltip behavior on sticky content in an IDE, checking the represented path and avoiding duplicate actions. Existing tests cover only synthetic left content presses.

## Behavioral coverage assessment

Unit source: [`VisualStickyTreeItemsTest.kt`](../src/test/kotlin/info/chrzanowski/stickyprojectview/VisualStickyTreeItemsTest.kt). All fixtures hide the root, use default Swing trees (or instrumented subclasses), set row height to 20 and execute on EDT. The eight executed tests cover:

| Test (source lines) | Evidence established by assertions | Important limit |
| --- | --- | --- |
| Full height while subtree visible (19–39) | Two pinned ancestors, offsets 0/20, content height 40 | One shallow fixed-height chain |
| Subtree slides out (41–60) | Negative offset -1 and content height 19 | One boundary pixel, not a full handoff sweep |
| Collapse without scrolling (62–91) | Manager reacts to collapse and panel becomes empty | No model replacement or listener-cleanup assertion |
| Child paints under parent (93–134) | Overlap pixels honor parent precedence | Stripe fixture, no overlapping click assertion |
| Chrome outside renderer (136–166) | Original tree painting includes custom marker | Not all real IDE arrows/icons/focus/themes |
| Separator/shadow (168–204) | Non-background border and translucent shadow | One fixture/theme, no viewport-height/visibility budget assertion |
| Click reveals below parents (206–254) | Synthetic left press selects child, removes it from stack, positions row at content height | No IDE-native mouse event, horizontal scroll or deep navigation |
| Navigation target offset (256–293) | Target equals child y minus one remaining 20-pixel ancestor | One internal helper result, not convergence across depths |

IDE source: [`StickyProjectViewUiTest.kt`](../src/integrationTest/kotlin/info/chrzanowski/stickyprojectview/StickyProjectViewUiTest.kt#L34-L113). It creates six nested directories, per-directory keep files, 160 deep files and 80 root files, opens Project View in an IU Starter/Driver session, requests `DeepFile120.txt` through the project service, and expects the final six sticky labels and positive panel height. This is a useful packaged-plugin integration scenario; after an initial setup-inclusive timeout, the cached Step 4 rerun passed on IU 263.6800.24 (2026.3 EAP; ledger below). It contains no real sticky click, no screenshot/native paint assertion, no passive startup assertion, and no exact selected-leaf assertion (R12/R13). Its overall scenario depth does not directly exercise the manager's click-navigation convergence loop.

Additional uncovered scenarios (coverage gaps, not independently proven bugs):

- **Geometry:** visible root; empty/negative/zero/small viewports and exact half-budget thresholds; multiple subtree boundaries; mixed/zero row heights; decoration-inclusive height and resize behavior; horizontal scroll and deeply indented rows. Content budget excludes separator/shadow, so exact navigation alignment with content does not ensure the row begins below all decoration; assess desired appearance before treating this as an error.
- **Updates:** model insert/remove/change/structure events, model replacement and old-listener removal, font/renderer/UI changes at unchanged geometry; selection clearing revealed state and scrolling away/back; viewport width/height changes.
- **Lifecycle:** unavailable or delayed Project View, pane replacement, repeated attach/detach, same-tree reparenting, disposal/unload and EDT guarantees; layout restoration and listener counts. Calling `manager.dispose()` in tests is cleanup, not a proof that listeners were all removed.
- **Navigation/input:** deep chains and clamped bottom targets, clicks on parent/child overlap and decoration, right-click/non-left input, selection/focus/theme appearance and exact file identity; rejected/timed-out service selection.
- **Performance:** large expanded models, repeated descendant traversal, diagnostic allocation, and native paint cost per sticky row. No timing benchmark, allocation profile, leak test or theme matrix was run.

## Checks performed and remaining uncertainty

- **Executed:** `./gradlew test` from the repository root on 2026-10-14, with a 120-second command cap. Exit 0, `BUILD SUCCESSFUL in 28s`, 14 actionable tasks executed. Generated `build/test-results/test/TEST-info.chrzanowski.stickyprojectview.VisualStickyTreeItemsTest.xml` confirms **8 tests, 0 failures, 0 errors, 0 skipped**, suite time **0.377 s**. Generated reports are validation evidence, not reviewed source or deliverables.
- **Non-fatal output:** “Following 1 plugins could not be created: plugins/fullLine/lib/modules/intellij.fullLine.yaml.jar”; JVM CDS warning caused by the custom system class loader; Gradle deprecated-feature warning about Gradle 10 compatibility. The Kotlin Gradle plugin configuration-error check task was skipped; this is not a skipped unit test. Warning origins and release implications have not been investigated in this runtime step.
- **Inspected:** the runtime classes cited above, service/startup entry points, all eight test bodies and the IDE test. Existing local documentation was used for continuity. No production code, tests or configuration were changed; no new reproducer was added, as required by the documentation-only scope. This revision reread the panel's input/decoration code and changed only this review; builds/tests were not rerun for the documentation corrections. R1's platform-theme counter-evidence and R4's concrete stale-stack reproduction are attributed to the supplied reviewer report, not new platform inspection or execution in this pass. The reviewer also reported `./gradlew test --rerun-tasks --offline -q` passing with 8 tests, zero failures/errors/skips; this is separate from the original execution recorded above.
- **Not executed:** new targeted regression scenarios, manual IDE interaction, debugger runs, profiling or local Qodana/Codecov actions. Step 4 packaging/verification/UI attempts are recorded below. No signing/publishing/release-changing operations were invoked.
- **Uncertainty:** passing Swing fixtures is not compatibility verification for every Project View pane, platform version or theme. Source-confirmed findings were not reproduced with additional fixtures, so platform reachability/frequency and measured user impact remain open where explicitly noted.

## Runtime follow-up ordering (input to the final backlog)

These are recommendations only; no fixes or tests were added.

| Priority | Work / finding IDs | Expected effort | Acceptance evidence |
| --- | --- | --- | --- |
| 1 | Correct horizontal coordinates/resets and revealed-state invalidation (R2/R4) | Small–Medium | Nonzero-x paint/click regressions; selection at y=0 removes stale stack without inspection repair |
| 2 | Restore unhandled overlay input and define interaction semantics (R14) | Medium | Decoration presses reach the tree; IDE context-menu/double-click/drag/hover checks |
| 3 | Strengthen passive integration observations and selection result (R12/R13) | Medium | Startup failure is observable; exact requested leaf selected; real sticky click |
| 4 | Validate conditional geometry modes and invalidation (R1/R3) | Medium | Establish actual Project View row-height modes; variable-height bounds oracle and stationary font/renderer mutation tests |
| 5 | Establish attachment readiness, EDT and restoration guarantees (R5/R6) | Medium | Delayed pane, switch, close/unload and layout/listener lifecycle checks |
| 6 | Validate navigation convergence and click geometry (R7/R8) | Medium | Deep/clamped navigation matrix and interval/paint agreement |
| 7 | Measure traversal before optimizing; eliminate debug-off detail work (R9/R10) | Medium for traversal, Small for logging | Allocation/query counts and scaling evidence, unchanged geometry |
| 8 | Document debug-label exposure (R11) | Small | Clear diagnostic-sharing guidance and debug gating check |

## Build, quality and release assessment

### What already works, and important non-findings

- The wrapper pins Gradle 9.4.1; settings pin Kotlin 2.1.20, Platform Plugin 2.14.0 and Changelog 2.5.0. CI consistently selects Java 21. Packaging and unit tests work on this checkout under host Java 25.0.3; that limited evidence is not a claim of official Kotlin/Gradle compatibility for all tasks or a clean Java-21 CI reproduction. Gradle 10 deprecation warnings do not mean the current Gradle build failed.
- Packaging generated the expected plugin ID/version, minimum IDE build 253, platform/lang requirements, bundle and startup registration. The archive contains the plugin JAR and searchable-options JAR. Native runtime code does not introduce a separate backend or production network client; packaging's marketplace stack traces came from the IDE subprocess and are not evidence of plugin data transmission.
- Platform Plugin **2.14.0** [upstream `IntelliJPlatformExtension.kt`](https://github.com/JetBrains/intellij-platform-gradle-plugin/blob/2.14.0/src/main/kotlin/org/jetbrains/intellij/platform/gradle/extensions/IntelliJPlatformExtension.kt) lines 657–665 and 772–782 bind `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` by convention. The workflow's environment names are correct without explicit assignments. No secrets, signing task or publication task were inspected/executed; actual credentials, Marketplace registration and signature validity remain unknown.
- No explicit verifier target list is written in the project, but the attempted task resolved an IDEA 2025.3.6.1 target. It is incorrect to report “no verifier targets” solely from absent DSL. No completed verifier report was obtained.
- Daily Dependabot updates cover Gradle and Actions. Qodana and Codecov are configured separately from unit tests/verifier checks. Tool/action versions differing numerically is not by itself proof of incompatibility.

### T1 — Change-notes property mismatch omits packaged release notes

- **Category / severity / confidence / evidence:** release metadata correctness / Medium / High / source-confirmed discrepancy, observed in packaged output.
- **Location:** `build.gradle.kts:46–60`, `gradle.properties:1–2`, `CHANGELOG.md:7–22`; generated `build/tmp/patchPluginXml/plugin.xml` and nested archive descriptor.
- **Trigger and impact:** ordinary `buildPlugin` with this checkout has `version=0.0.2` but no `pluginVersion`. The mapped provider is absent, so even the `getUnreleased()` fallback inside the map is never evaluated. Generated and archive descriptors have version 0.0.2 and no `<change-notes>`, despite populated release history. Packaging succeeds, making this easy to miss.
- **Remedy:** use the declared project-version provider consistently and validate generated notes for both released and unreleased versions; do not merely change the changelog text.
- **Verification:** build without ad hoc properties and inspect the final ZIP's nested descriptor for 0.0.2 notes; cover missing-version fallback separately. No repair was made.

### T2 — Coverage upload expects a report the build does not generate

- **Category / severity / confidence / evidence:** CI/coverage observability / Medium / High / source-confirmed configuration discrepancy, no remote action reproduction.
- **Location:** `build.gradle.kts:5–9,27–43`, `settings.gradle.kts:5–15`, `.github/workflows/build.yml:110–127`, `codecov.yml:1–10`.
- **Trigger and impact:** CI runs `check` then uploads `build/reports/kover/report.xml`, but no Kover plugin/report producer is configured. The inspected `check` dry-run includes unit tests, not coverage generation. An upload cannot supply the intended report from a clean checkout; whether the action warns or fails depends on its defaults/context. Informational Codecov statuses and a 0% threshold are not enforced coverage requirements, nor evidence that existing tests are ineffective.
- **Remedy:** intentionally configure and invoke coverage generation, or remove the misleading upload expectation; choose enforcement only after agreeing a coverage policy.
- **Verification:** clean CI-equivalent `check`/report task, assert XML exists with production classes, and inspect upload/status behavior including PRs without secrets.

### T3 — Manual UI workflow runs unit tests, not the Starter/Driver scenario

- **Category / severity / confidence / evidence:** CI/test coverage / Medium / High / source-confirmed task mismatch.
- **Location:** `.github/workflows/run-ui-tests.yml:20–30,51–65`, `build.gradle.kts:75–110`; integration test at lines 39–44.
- **Trigger and impact:** manual dispatch starts a robot-server IDE and waits at port 8082, then executes `test`, which is the eight Swing/JUnit-4 tests. The custom `integrationTest` is a different JUnit-5 Starter/Driver launcher and is not wired into `check` either. Green manual UI runs therefore cannot establish that the actual IDE scenario passed. Linux also launches system `gradle` rather than the pinned wrapper, creating an avoidable environment dependency.
- **Remedy:** select one intended harness, run its actual task and collect its reports; use the wrapper on Linux. Do not assume changing only `test` to `integrationTest` makes the separately launched robot IDE useful to Starter.
- **Verification:** CI logs show discovery/execution of `StickyProjectViewUiTest`, its result XML, and correct IDE lifecycle cleanup on all intended OSes; validate real click/passive checks separately (R12/R13).

### T4 — Changelog PR condition references an output with no producer

- **Category / severity / confidence / evidence:** release automation correctness / Medium / High / source-confirmed workflow discrepancy.
- **Location:** `.github/workflows/release.yml:47–57,74–100`.
- **Trigger and impact:** after publication, `steps.properties.outputs.changelog != ''` refers to no step ID/output in that workflow. It cannot signal the actual patched changelog; the intended PR branch is gated off rather than persisting release-note changes. The workflow was not executed, so this is not a reproduced remote publication failure.
- **Remedy:** explicitly calculate whether changelog content changed and gate the PR on that real result; account for no-op patches and checkout at a release tag.
- **Verification:** nonpublishing workflow fixture/dry validation for changed and unchanged notes, ensuring only real diffs create a PR. Never exercise this by publishing a test release to production.

### T5 — Compatibility validation is moving and incomplete

- **Category / severity / confidence / evidence:** reproducibility/platform coverage / Medium / High for configuration and attempted target, Medium for future impact / unverified compatibility risk.
- **Location:** `build.gradle.kts:38,46–61,75–90`; `StickyProjectViewUiTest.kt:39–42`; descriptor source; `settings.gradle.kts:7,14`; wrapper properties line 3.
- **Trigger and impact:** compile platform is 2025.3, generated support starts at 253 with no upper bound, verifier targets are implicit, and the IU test does not pin a version. During this attempt Starter selected 263.6800.24 (2026.3 EAP). Effective targets can evolve independently; a failed setup or pass on an unrelated EAP would not prove 2025.3 UI behavior. The bounded verifier run never reached compatibility analysis. This is not evidence that plugin APIs are currently incompatible.
- **Remedy:** define an explicit support policy, pin a baseline UI IDE and intentionally add forward coverage; record effective verifier targets and complete those checks. Keep open-ended metadata only if deliberate, rather than assuming an upper bound is always required.
- **Verification:** successful verifier reports for agreed builds/products plus an IDE scenario on the advertised baseline, with version identifiers retained in CI artifacts.

### T6 — Java/tool compatibility assumptions are not fully reproducible

- **Category / severity / confidence / evidence:** build maintainability / Low / High for configuration, unverified impact / unverified risk.
- **Location:** `build.gradle.kts` (no explicit toolchain/target), `settings.gradle.kts:7,13–14`, `.github/workflows/build.yml:47–51`, wrapper properties line 3.
- **Trigger and impact:** CI uses Java 21, host uses 25.0.3, and Foojay registration is not a toolchain declaration. Sampled packaged Kotlin class major is 65 (Java 21), so no Java-25 bytecode defect was observed. Kotlin 2.1.20/Gradle 9.4.1 successfully completed the exercised incremental tasks but that does not certify all plugin APIs or clean builds; Gradle warned about deprecated features incompatible with Gradle 10.
- **Remedy:** document/pin the intended build JDK and target, check supported tool-version ranges before upgrades and locate deprecation origins before Gradle 10; avoid a speculative dependency upgrade as a review “fix.”
- **Verification:** clean Java-21 build/test with `--warning-mode all`, compare class targets and artifact metadata across approved developer/CI JDKs. Not run here.

### T7 — Inspection age and workflow permission scope deserve review

- **Category / severity / confidence / evidence:** quality/security hardening / Low / High for configuration, unverified exploit/analysis impact / design suggestion and unverified risk.
- **Location:** `qodana.yml:5–12`, `.github/workflows/build.yml:134–137,148–165`, `.github/dependabot.yml:5–15`.
- **Trigger and impact:** Qodana community 2024.3/JDK21 analyzes a Kotlin-2.1.20/IDEA-2025.3 project; action v2025.1.1 does not itself update that image. No local inspection result establishes its effective language/API analysis. The inspection job requests contents/checks/PR write permissions and checks out the PR head; actual fork permissions are subject to GitHub restrictions. No token leak/exploit is established. Root Gradle/Actions Dependabot entries do not explicitly maintain the Qodana image tag in this YAML.
- **Remedy:** validate the chosen linter against the build, review whether each write permission is required, and assign image-update ownership. Do not invent a repository requirement for a particular inspection profile or action-pinning policy.
- **Verification:** inspect a safe PR Qodana run for setup and analysis coverage; review effective permissions for internal/fork events without exposing tokens. Not executed here.

### T8 — Root license and source headers require provenance clarification

- **Category / severity / confidence / evidence:** distribution/provenance maintainability / Medium pending clarification / High for notice mismatch, unknown obligations / source-confirmed metadata discrepancy, not a legal determination.
- **Location:** `LICENSE:1–22`; Apache-2.0 JetBrains 2000–2025 header on eight production files (for example `StickyTreeManager.kt:1`); exceptions and resource inventory in the knowledge dossier.
- **Trigger and impact:** a distributor sees a root MIT license for Jakub Chrzanowski alongside Apache-labeled source. Multiple notices may legitimately reflect derivation, but provenance and intended notice inclusion are not explained. The review cannot determine ownership, relicensing rights or noncompliance from headers alone.
- **Remedy:** ask maintainers to establish origins and intended licenses, retain required notices and obtain appropriate licensing advice before changing headers or distribution metadata.
- **Verification:** provenance review against upstream history/author confirmation and a distribution notice audit. No license/header changes or legal conclusion were made.

### T9 — Template remnants and unignored UI output obscure project intent

- **Category / severity / confidence / evidence:** repository hygiene / Low / High / source-confirmed remnants; cleanup is a design suggestion.
- **Location:** `StickyProjectViewBundle.kt:7–19`, `src/main/resources/messages/StickyProjectViewBundle.properties`, `src/test/testData/rename/`, `build.gradle.kts:26`, `.gitignore:1–7`.
- **Trigger and impact:** unused `MyBundle` sample messages and XML rename fixtures do not exercise sticky behavior; the version-catalog comment does not match direct dependency declarations. This session's UI runs created unignored `out/` (7.1 GB, including `out/dev-run/` and `out/ide-tests/`) and `allure-results/` (964 KB after rerun), adding thousands of untracked cache/log files. These are not runtime defects or additional deliverables. The session-created trees were removed after evidence capture; future UI runs can recreate the same unignored output.
- **Remedy:** classify retained samples and document/remove only confirmed unused remnants; direct or ignore generated UI artifacts deliberately so subsequent runs do not add large untracked trees.
- **Verification:** reference/resource searches before removal and a clean-status check after running the actual UI task. Do not delete unknown pre-existing local files as cleanup.

### T10 — Release-note/version/channel consistency lacks explicit guards

- **Category / severity / confidence / evidence:** release reliability / Medium / High for configuration, unverified release impact / source-confirmed discrepancy and policy risk.
- **Location:** `.github/workflows/build.yml:225–246`, `.github/workflows/release.yml:7–8,28–32,47–72`, `gradle.properties:2`, `CHANGELOG.md:5–7`, `build.gradle.kts:69–73`.
- **Trigger and impact:** version is already 0.0.2 with a released changelog section and empty Unreleased; draft generation always uses Unreleased, so it does not use the existing 0.0.2 notes. It deletes all draft releases before creation. Release checkout uses the tag but has no explicit tag/project-version check; both prereleased/released events invoke the same default-channel publishing path, with no explicit prerelease routing or concurrency/idempotency guard. Whether an existing tag/publication would reject or duplicate a release was not tested. Draft deletion is configured behavior, not something performed here.
- **Remedy:** define release version bump/notes rules, validate tag/version before upload, preserve unrelated drafts, decide prerelease channels and duplicate-event policy explicitly.
- **Verification:** offline event fixtures for empty/populated notes, tag mismatch, unrelated drafts and prerelease-to-release promotion, with mocked/nonpublishing release operations; inspect generated metadata before any authorized real release.

## Step 4 validation ledger (2026-10-14)

Commands ran from the repository root on macOS. Host `java -version` reported Temurin **25.0.3+9-LTS**. Times are observed command/Gradle durations, not performance benchmarks. Existing test results above were retained without a broad rerun.

| Exact command | Bound / observed outcome | Evidence and limitation |
| --- | --- | --- |
| `./gradlew buildPlugin` | 120 s cap; exit 0, success in about 24 s | 14 actionable tasks, compilation largely up-to-date; produced `build/distributions/sticky-project-view-0.0.2.zip`. Not a clean build. |
| `./gradlew verifyPlugin` | 120 s cap; forcibly terminated | Last output: calculating task graph. No verifier result; network/setup cause not established by this attempt alone. |
| `./gradlew verifyPlugin --offline` | 60 s cap; exit 1, `BUILD FAILED in 28s` | Dependency calculation failed: `idea:idea:2025.3.6.1` uncached in `detachedConfiguration11`. Confirms a missing offline prerequisite, not missing effective targets or plugin incompatibility. No repeated large download attempted. |
| `./gradlew integrationTest --offline` (initial) | 120 s cap; forcibly terminated, not a test failure | Most of the cap went to setup/download/extraction. Before cleanup, the initial-run Allure attachment `allure-results/9d60f47a-0ec4-4e22-9c83-c47071121258-attachment.txt` recorded IDE start at 14:42:32, custom plugin `Sticky Project View (sticky-project-view, 0.0.2)` loaded at 14:42:33, temporary project opened/indexing, and IDE shutdown at 14:43:18: about 46 s of IDE runtime. The rerun later overwrote Starter's `out/.../idea.log`; it is not evidence for this initial attempt. The test permits `waitForIndicators(5.minutes)`. No final assertion result; Gradle offline mode does **not** prevent Starter's own downloads. |
| `./gradlew integrationTest --offline` (cached rerun) | 600 s cap; exit 0, `BUILD SUCCESSFUL`, about 53 s | Cached IU 263.6800.24 reused; existing scenario **PASSED**. `build/test-results/integrationTest/TEST-info.chrzanowski.stickyprojectview.StickyProjectViewUiTest.xml` confirms 1 test, 0 failures/errors/skips, suite time 50.505 s. This is 2026.3 EAP, not the 2025.3 compile/baseline platform. |
| `./gradlew check --dry-run --offline` | 60 s cap; exit 0, 3 s | Task graph contains `test`/`check`, not `integrationTest`/coverage. All tasks skipped by dry-run; this is not a new passing test execution. |
| `unzip -l build/distributions/sticky-project-view-0.0.2.zip` | 20 s cap; exit 0 | Plugin and searchable-options JAR entries present. |
| `unzip -p build/libs/sticky-project-view-0.0.2.jar META-INF/plugin.xml` | 20 s cap; exit 0 | Matches generated descriptor. A read-only Python `zipfile` nested-archive check also confirmed final ZIP descriptor and sampled class header `cafebabe00000041` (major 65). |

**Warnings and UI evidence limits:** packaging emitted CDS/custom-classloader warnings, missing SQL/Sass color-scheme resources, headless JCEF disablement, an Eel Java-home-path warning involving `~/.dotnet/tools`, “No searchable options found,” marketplace SSL/coroutine cancellation traces and a scope-not-completed-in-10s shutdown warning. These did not change its exit 0; their root causes were not isolated and they are not assigned to plugin runtime changes. UI/check setup repeated the fullLine YAML plugin-creation warning and Gradle-10 deprecations. The initial UI attempt also logged Allure “Could not update test case: no test case running,” `hdiutil` attach/detach deprecations and an ignored remote class-resolution exception for `org.jetbrains.builtInWebServer.BuiltInServerOptions`. The successful rerun still emitted the Allure update/attachment errors and ignored class-resolution exception; they did not fail the Gradle test. Only the existing service-driven scenario's assertions are established, not real sticky clicks, passive startup correctness or baseline compatibility.

**Side effects and safety:** the initial bounded UI launch left child Java processes; termination was requested only for tool-reported UI worker/child PIDs (99704, 99713, 1006, 1007). The successful rerun ended with only its tool-reported Gradle daemon (4517), not UI children. `stat -f '%N birth=%SB' -t '%Y-%m-%d %H:%M:%S' out out/dev-run out/ide-tests/cache allure-results` confirmed this session created `out/`, `out/dev-run/` and the cache at 14:41:28, and `allure-results/` at 14:43:26 on 2026-10-14. `du -sh out allure-results` reported 7.1 GB and 964 KB after rerun (Allure was 256 KB before rerun). The initial log facts above were captured from the Allure attachment before cleanup; the rerun overwrote the corresponding Starter `out/.../idea.log`. After confirming the trees were session-created, only `out/` and `allure-results/` were removed; the 7.1 GB and 964 KB untracked trees are no longer present. Future Starter runs still write to unignored output paths by default. Do not remove `.agents/`, `.junie/`, `build/` or unrelated artifacts. Only the two planned docs were edited; existing staging was left untouched. No publication, signing, changelog mutation, GitHub release/draft operation or credential-value inspection occurred.

**Skipped and unresolved:** no Qodana container run, Codecov upload, Java-21 clean build, manual IDE interactions, longer verifier rerun, baseline-2025.3 UI run or proposed regression tests. Missing verifier prerequisites and baseline coverage prevent a release-ready verdict despite the successful cached EAP scenario. Official version-range support and all warning origins are not established by these incremental checks. Version-specific upstream conventions were read through a bounded `curl` request after the web fetch tool returned a response-parser error; no external files were added to the repository.

**Documentation validation:** the original link checker incorrectly stripped colon suffixes, masking one invalid `:34–113` path. That link now uses `#L34-L113`. The corrected read-only Python check URL-decodes local paths and strips only fragments, checks file existence (not Markdown renderer anchor support), and confirms all 24 numbered findings have category/severity/confidence/evidence, location, trigger/impact, remedy and verification fields. No source/configuration changes were made for validation.

## Final cross-project improvement backlog

Recommendations only. Ordering combines release prerequisites with user impact and evidence strength; it does not promote conditional R1 above default-configuration R2/R4. Effort is relative, not a time estimate. Each item inherits trigger/remedy/verification details from its findings.

| Rank | Follow-up / IDs | Impact / confidence | Effort | Acceptance evidence |
| --- | --- | --- | --- | --- |
| 1 | Repair release metadata and changelog persistence; guard version/channel flow (T1/T4/T10) | Release correctness / High source confidence | Small–Medium | Packaged notes, real change output, safe event fixtures and tag/version checks |
| 2 | Fix horizontal coordinates/resets and revealed-state invalidation (R2/R4) | Default-configuration correctness / High | Small–Medium | Nonzero-x regressions and stale stack removed without inspection repair |
| 3 | Run the actual IDE test on a deliberate baseline; complete compatibility checks (T3/T5) | Missing readiness evidence / High configuration confidence | Medium | Scenario XML and verifier reports with recorded IDE versions |
| 4 | Restore unhandled overlay input (R14) | Native interaction / High source, unverified IDE-wide effects | Medium | Decoration routing, native popup/double-click/drag/hover checks |
| 5 | Strengthen passive observations and exact selection result (R12/R13) | Test validity / High | Medium | Suppressed startup detected, exact selected leaf and real sticky click |
| 6 | Establish licensing provenance before distribution changes (T8) | Distribution clarity / High mismatch, unknown obligations | Small–Medium investigation | Maintainer provenance decision and notice audit |
| 7 | Correct coverage reporting (T2) | CI observability / High | Small–Medium | XML producer and verified clean-CI upload, explicit status policy |
| 8 | Validate conditional row geometry and invalidation (R1/R3) | Conditional correctness / High arithmetic, unverified default reachability | Medium | Actual row-height modes, bounds oracle, stationary renderer/font tests |
| 9 | Establish lifecycle/EDT/layout guarantees (R5/R6) | Conditional reliability / Medium | Medium | Delayed readiness, switching/reparenting, unload and listener/layout tests |
| 10 | Validate bounded navigation and click geometry (R7/R8) | Conditional navigation / Medium–High | Medium | Deep/clamped matrix and paint/hit agreement |
| 11 | Measure traversal, gate debug-off work and document labels (R9/R10/R11) | Performance/privacy hygiene / High source, unmeasured cost | Medium traversal; Small logging/docs | Query/allocation profiles, unchanged geometry, sharing guidance |
| 12 | Reproduce Java-21 clean build, inspect deprecations and Qodana scope/permissions (T6/T7) | Build hardening / High config, unverified impact | Small–Medium | Clean CI-JDK evidence, identified warnings and safe inspection run |
| 13 | Classify template remnants and generated UI output (T9) | Maintainability / High | Small | Confirmed references and scoped cleanup/ignore policy |
