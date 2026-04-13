# Sticky Project View

Sticky Project View adds sticky parent nodes to the IntelliJ Project View, similar to sticky lines in the editor.

<!-- Plugin description -->
Sticky Project View adds sticky parent nodes to the IntelliJ Project View.

It keeps expanded parent directories pinned at the top while you scroll and preserves the native Project View look:

- expanded parent directories stay pinned at the top while you scroll
- sticky rows keep native Project View rendering, including arrows, icons, colors, and selection
- clicking a sticky row reveals and selects that node in the tree while keeping its parents pinned above it
<!-- Plugin description end -->

## Features

- Keeps the current directory chain visible while navigating deep project trees.
- Reuses real `JTree` painting instead of rebuilding rows, so sticky items match Project View styling.
- Supports clicking sticky rows to navigate directly to that node.
- Includes unit, rendering, and UI integration tests.

## Development

```bash
./gradlew runIde
```

```bash
./gradlew buildPlugin
```

```bash
./gradlew test
```

```bash
./gradlew integrationTest
```
