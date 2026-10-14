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
- Includes unit, rendering, and UI integration tests.

## Development

Use Java 21 and the Gradle wrapper:

```shell
./gradlew compileKotlin compileTestKotlin compileIntegrationTestKotlin
./gradlew test
./gradlew integrationTest
```

`platformVersion` in `gradle.properties` selects both the compilation platform and the Starter/Driver IDE (currently IDEA 2025.3). Integration tests assert and log the running release/build and the exact selected path. Starter installs the plugin, launches the IDE, and closes it; no separate Robot server is needed. A graphical desktop is required; on Linux use `xvfb-run -a ./gradlew integrationTest`.

The integration test task has a 15-minute execution limit, including Starter's IDE resolution and downloads (each IDE run has an 8-minute limit). Gradle dependency and compilation-platform resolution happen before that task timeout. CI bounds the whole job to 30 minutes. JUnit XML and HTML reports are under `build/test-results/integrationTest` and `build/reports/tests/integrationTest`. Starter logs and IDE error reports are under `out/ide-tests/tests/`; launch names are unique so reruns retain their evidence. IDE-process error reports fail JUnit, including on local runs. CI uploads reports and logs on success or failure, not IDE distributions.
