# Sticky Project View

Sticky Project View adds sticky parent nodes to the IntelliJ Project View.

It keeps expanded parent directories pinned at the top while you scroll and preserves the native Project View look:

- expanded parent directories stay pinned at the top while you scroll
- sticky rows keep native Project View rendering, including arrows, icons, colors, and selection
- clicking a sticky row reveals and selects that node in the tree while keeping its parents pinned above it

## Features

- Keeps the current directory chain visible while navigating deep project trees.
- Reuses real `JTree` painting instead of rebuilding rows, so sticky items match Project View styling.
- Supports clicking sticky rows to navigate directly to that node.
- Preserves native expansion, double-click, context menus, wheel scrolling, and drag gestures; separator and shadow input reaches the tree underneath.
- Shows full file/directory paths on hover (logical ancestor chains for non-file nodes). Single-click label navigation waits for the double-click interval so it does not interfere with native gestures.
- Includes unit, rendering, and UI integration tests.

## Controls

In the Project View context menu, choose **Sticky Project View → Enable Sticky Rows** to toggle sticky rows. This IDE-wide preference applies to every open project and survives restart. Disabling removes the overlay and restores the native scroll-pane layout.

Choose **Sticky Project View → Configure Sticky Rows...**, or open **Settings → Tools → Sticky Project View**, to set the maximum sticky content height to **10%–90% of the viewport** (default **50%**). Apply updates every open project immediately, without scrolling. The budget excludes the separator/shadow and retains a row margin for the native tree below the stack; Reset restores the currently saved preferences.

The integration suite checks repeated toggling and persistence across two IDEA launches sharing isolated configuration storage; each launch retains separate logs.

Choose **Sticky Project View → Navigate Sticky Ancestors...** in Project View, or search for **Navigate Sticky Ancestors** in Find Action. Use arrow keys and Enter to reveal/select an ancestor; Escape cancels without changing selection and returns focus to the tree. Full paths distinguish duplicate names, and speed search filters the chooser. Assign a shortcut in **Settings → Keymap**; no default shortcut is imposed. The overlay itself never takes keyboard focus.

## Development

Use Java 21 and the Gradle wrapper:

```shell
./gradlew compileKotlin compileTestKotlin compileIntegrationTestKotlin
./gradlew test
./gradlew integrationTest
```

`platformVersion` in `gradle.properties` selects both the compilation platform and the Starter/Driver IDE (currently IDEA 2025.3). Integration tests assert and log the running release/build and the exact selected path. Starter installs the plugin, launches the IDE, and closes it; no separate Robot server is needed. A graphical desktop is required; on Linux use `xvfb-run -a ./gradlew integrationTest`.

Pointer tests cover coordinate/metadata routing and suppression of reveal during double-click/drag gestures. The IDE scenario exercises hover paths, popup selection/anchoring, double-click expansion, single-click reveal, and wheel scrolling (including an unobscured-tree control). Wheel events are posted to the IDE event queue to exercise native dispatch and scrolling: Driver Robot wheel injection does not deliver events on the tested macOS environment. OS-level wheel injection and OS-native drag-and-drop transfer are not covered by these tests.

The integration test task has a 15-minute execution limit, including Starter's IDE resolution and downloads (each IDE run has an 8-minute limit). Gradle dependency and compilation-platform resolution happen before that task timeout. CI bounds the whole job to 30 minutes. JUnit XML and HTML reports are under `build/test-results/integrationTest` and `build/reports/tests/integrationTest`. Starter logs and IDE error reports are under `out/ide-tests/tests/`; launch names are unique so reruns retain their evidence. IDE-process error reports fail JUnit, including on local runs. CI uploads reports and logs on success or failure, not IDE distributions.
