# Project View performance investigation — 2026-10-15

## Supplied diagnostics

Archive: `idea-logs-20261015-0902156536573830429259586.zip` (77 entries, approximately 20 MB uncompressed).
The log covers five launches of IDEA `IU-253.33813.55` / `2025.3.6.1`, with Sticky Project View `0.0.2` loaded.

| Evidence | Observed EDT work |
| --- | --- |
| `threadDumps-freeze-20261015-075319-…/threadDump-20261015-075319.txt` | `DefaultTreeLayoutCache.updateExpandedPaths`; reported freeze: 5,213 ms. |
| `threadDumps-freeze-20261015-090048-…/threadDump-20261015-090048.txt` | `DefaultTreeLayoutCache$NodeList.add`, recursively called from `updateExpandedPaths`; reported freeze: 6,423 ms. |
| `bg-wa/thread-dump-162982269.txt` | `TreePath.equals` / `HashMap.get` / `DefaultTreeLayoutCache.getOrCreateNode` during expansion; a background write action waited over 10 seconds. |
| `threadDumps--20261015-090117-…/threadDump-20261015-090215.txt` | A later manual sample is in core service initialization through `ActionsOnSaveManager`, not tree layout. |

The first three EDT samples share native expansion/restoration and asynchronous model-insertion callers:

```text
AsyncTreeModel insertion / CachedPresentationTree update
  → Tree$ExpandImpl.expandPaths
  → DefaultTreeUI.endBulkOperation
  → DefaultTreeLayoutCache.updateExpandedPaths
```

None contains a Sticky execution frame. Active plugin coroutine scopes in dump diagnostics establish presence, not responsibility.
There is no demonstrated monitor-owner deadlock in these captures. They do not exclude indirect plugin overhead.

`idea.log` contains 80 warning records, no error-level records, and no `PluginException` records.
Five `ActionUpdater` warnings report cumulative EDT-acquisition delays of 5,343, 16,834, 12,769, 14,964, and 3,153 ms in core `ToolWindowHeader$2`.
These are congestion symptoms, not evidence that Swing tree operations should be moved to a background thread.

Other warnings include corrupt `configs-dsl-kotlin-latest-2026.2.jar` archives, Gradle/JDK resolution problems, bundled-plugin dependencies, missing color resources, and startup/cloud-service notices.
They are separate from Sticky exceptions; no user dependency caches, licenses, or unrelated project settings were changed to hide them.

Indexing reports 501,396 scanned files on the first full scan and 242,843 on later full scans for `IntelliJPlatformGradlePlugin`.
Those are scanned-file counts, **not** measurements of expanded Project View rows.

## Confirmed plugin-side problem and fix

`StickyTreeManager` synchronously recalculated sticky rows for every model and expansion notification.
A non-suspending debugger trace confirmed recalculation inside `DefaultTreeModel.fireTreeNodesInserted` before notification dispatch returned.
Listeners can be called before the native UI listener has updated its layout; repeated queries also amplify insertion/expansion bursts.

The regression uses a real `DefaultTreeModel` and a 2,001-row Swing tree, with the same operations on a detached baseline:

| Workload | Before | After | Detached baseline |
| --- | ---: | ---: | ---: |
| 200 insertions in one EDT turn | 4,752 counted row lookups | 24 | 0 |
| 100 mixed model/viewport/selection updates | 4,800 counted row lookups | Bounded by regression assertion | 0 |

These are counts of `getPathForRow` / `getRowBounds`, not a claimed wall-clock speedup for the original project.
Reproduce with:

```shell
./gradlew test --tests info.chrzanowski.stickyprojectview.StickyTreeManagerTest
```

Structural updates now request one queued EDT refresh per burst, after synchronous listeners finish.
Intermediate viewport/selection/focus notifications do not force layout while a structural refresh is pending.
Ordinary scrolling remains immediate; explicit refresh consumes pending work, and disposal cancels it.
Tests cover expansion bursts, changed/inserted/removed/reloaded nodes, model replacement, row-height changes, navigation, and disposal.

## Real-IDE validation

`StickyProjectViewUiTest.largeTreeExpansionAndModelUpdates` creates 60 branches with 40 files each, plus the existing deep-tree fixture.
It expands 2,717 rows with the overlay disabled and enabled, inserts and removes 200 files through asynchronous VFS/model updates, checks exact selection and ancestor labels, and verifies collapse clears the overlay.
Observed expansion completion was approximately one second in each mode, including the driver's polling interval; this is a smoke comparison, not a benchmark proving equal performance.
The insertion trace confirmed 2,717 → 2,917 rows and 40 → 240 children in the changed directory.

The stress test also caught a diagnostic `selectPath` helper bug: the selected last leaf of a branch was covered by the overlay.
Runtime values showed a selected row at `y=59236`, viewport `y=59188`, and a 175-pixel overlay.
Its bounded scroll correction now accounts for the complete panel height, including decoration; the test asserts that the selected row is below it.
This is a fix to programmatic selection, not a claim to replace the IDE's own navigation behavior.

## Final verification

- `./gradlew test integrationTest buildPlugin --console=plain --quiet` passed: 61 unit tests and 5 integration tests, with no failures or skips.
- IDE compilation passed; inspections reported no problems in the main changed implementation files, burst tests, and UI test.
- Installable artifact: `build/distributions/sticky-project-view-0.0.2.zip`.
- Agent-created debugger probes were removed; existing user breakpoints were preserved.

## Native-platform limitation

[IJPL-242319](https://youtrack.jetbrains.com/issue/IJPL-242319/EDT-freeze-in-DefaultTreeLayoutCache.updateExpandedPaths-when-expanding-large-project-trees) is a closely matching upstream tree-layout report.
It is listed in [2026.2 release notes](https://youtrack.jetbrains.com/articles/IDEA-A-2100662707/IntelliJ-IDEA-2026.2-262.8665.258-build-Release-Notes), but the exact source fix, fixed build, and any `253.x` backport were not verified.

The supplied multi-second native freezes were not reproduced on the smaller generated fixture, so they are **not claimed fixed**.
The changes remove independently proven plugin overhead without patching IntelliJ internals, changing the supported IDE baseline, or disabling native tree behavior.