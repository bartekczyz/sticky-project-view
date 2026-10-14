# Foldable setup review

Reference: `/Users/hsz/Projects/intellij-foldable-project-view`, commit `b0b315e18f38cf889a09387f9a1c7a009cb2c8fa`, reviewed on 2026-10-14. Its uncommitted, machine-specific `runLatest` configuration was inspected but not copied. The reference repository and existing Sticky review documents were left unchanged.

## Automation and build

| Area | Decision |
| --- | --- |
| GitHub Actions | Match Foldable's current major versions: checkout/upload-artifact v7, setup-java/Gradle setup v6, free-disk-space v2. Keep ZIP distributions intact rather than uploading extracted content. |
| Job safety | Read-only token permissions by default, explicit write permissions only for release work, bounded runtimes, cancellation for superseded build/UI runs, and serialized releases. Bootstrap Java/Gradle in every Gradle job, including release drafts. |
| Inspections/coverage | Retire the unused Qodana job and Codecov upload, matching Foldable's simpler pipeline. No Kover report generator existed; retain the real unit, integration, and Plugin Verifier checks instead. |
| Gradle | Gradle 9.8.1 and its wrapper/distribution checksums were checked against the official release metadata. Use IntelliJ Platform Gradle Plugin 2.19.0 and Java 21. Kotlin 2.3.20 intentionally matches Foldable's pinned compiler rather than chasing a newer compiler independently. |
| Platform baseline | Compile and run Starter/Driver against the same configured IDEA 2025.3.6.1 patch release. Keep the 2025.3 minimum platform family; do not silently raise it to 2026.x. |
| Test classpath | Keep platform API tests isolated from unrelated bundled plugin jars. The two-project test uses a physical temporary directory because real project creation rejects the light fixture's `temp:/` paths on the patched SDK. |
| Dependency updates | Daily Gradle and Actions Dependabot updates already match Foldable; no change needed. |

## Advanced practices

- **Single-source metadata:** generate the Marketplace description from the README's marked section, including all three donation providers. Missing, duplicated, or reversed markers fail packaging. Keep released-version notes ahead of Unreleased notes, preserve category headings, and add the sponsorship nudge exactly once without changing the changelog itself.
- **Settings help and sync:** route only the plugin's settings help topic to its documentation. User preferences use the UI Settings Sync category and publish normalized reload changes to already-open projects; equivalent reloads remain no-ops. Sponsorship timing/dismissal state explicitly does not roam. Unit tests cover this configuration and reload behavior, not a live account-to-account sync session.
- **Optional sponsorship:** GitHub Sponsors, Ko-fi, and PayPal links in settings, README, GitHub funding, and Marketplace metadata; sponsor actions in the Project View options and context menus. Notifications require 10 eligible project openings and 14 elapsed days, claim a 90-day snooze before display, avoid concurrent duplicates, and offer permanent dismissal. No usage information is transmitted.
- **Existing lifecycle safeguards retained:** disposal-bound message-bus connections, disposed-project checks, EDT dispatch, overlay detachment, and listener restoration already exist in `StickyProjectViewService`, `ProjectViewStickyController`, and `StickyTreeManager`, with toggle/disposal tests. Foldable's expensive VCS/rule matching debounce is not appropriate for sticky viewport updates, which should stay immediate.
- **Domain-specific functionality not imported:** YAML folding rules/presets, JSON schemas, rule scopes, default project rule sets, and the folding preview are Foldable features, not setup improvements for Sticky Project View.

## Release prerequisites

- `.github/FUNDING.yml` enables the GitHub Sponsor button when it reaches the repository's default branch. These local commits do not publish changes to GitHub or alter Foldable.
- Configure repository secrets `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, and `PRIVATE_KEY_PASSWORD` before a signed Marketplace release. The repository secret listing was empty during this audit; no credentials were created or changed. Gradle Plugin 2.19 uses these environment variables as its signing/publishing defaults.
- GitHub Actions must be permitted to create pull requests for the changelog update workflow. The repository currently has `default_workflow_permissions=read` and `can_approve_pull_request_reviews=false`; the owner must enable **Allow GitHub Actions to create and approve pull requests** under **Settings → Actions → General → Workflow permissions** before releasing. Repository settings were not changed. Retain Sticky's changelog-only detection/staging/commit guards, which are stricter than Foldable's release workflow.
- Local desktop integration tests require a graphical session. Linux CI uses `xvfb-run`; Windows and macOS keep their native wrapper commands. Reports and uniquely named IDE logs are uploaded on success or failure. Native drag-and-drop transfers remain outside the existing UI test coverage.
- Offline release fixtures reuse the main project's populated `.intellijPlatform` metadata cache and Gradle dependency cache. Run a compile/build online before running those fixtures in a fresh checkout.

## Verification

- `./gradlew check buildPlugin verifyPlugin` passed: **46 unit tests**, zero failures/errors/skips, and an installable `build/distributions/sticky-project-view-0.0.2.zip`.
- Plugin Verifier reported compatibility with all four recommended IDE builds: `IU-253.33813.55`, `IU-261.27258.48`, `IU-262.10968.63`, and `IU-263.6800.24`.
- `./gradlew integrationTest` passed on macOS: **4 tests**, zero failures/errors/skips, including real IDEA 2025.3.6.1 launches, sticky interactions, and persistence across restart. Local verification used Java 21 and an absolute-only process `PATH`, without changing the user's shell configuration.
- Both Python scripts passed (**6 workflow guards and 3 release/metadata tests**, including four invalid-marker cases); actionlint **1.7.12** found no workflow issues. The funding YAML and wrapper checksum were validated. GitHub-hosted jobs, Marketplace publishing, and Windows/Linux desktop runs were not executed locally.

## Follow-ups

- Verifier flags existing deprecated or scheduled-for-removal APIs in `MyBundle`, `NavigateStickyAncestorAction`, `ProjectViewStickyController`, and `StickyTreePathPresentation`. They did not cause compatibility failures on the checked builds; updating them is a separate maintenance task.
- Desktop logs contain bundled Docker shutdown and Starter/Allure reporting warnings despite successful JUnit results; headless packaging also logs bundled IDE shutdown warnings. These were retained, not suppressed or used to weaken tests, and should be revisited with future IDE/Starter updates.