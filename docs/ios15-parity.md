# iOS 15 Parity Build Guide

The iOS package is built manually in GitHub Actions. It is unsigned and intended for installation on an iOS 15 test device with TrollStore; the workflow does not use an Apple developer account or App Store signing.

## Run the unsigned IPA workflow

1. Open the fork's **Actions** page and select **iOS Unsigned IPA**.
2. Choose **Run workflow** from the default branch, then set:
   - `ref`: branch or full 40-character commit SHA to build. The default is `feat/ios15-parity`.
   - `configuration`: `Release` for device acceptance or `Debug` for diagnostics.
   - `run_tests`: keep enabled unless a prior run of the same source SHA already passed `ciIosTest`.
3. Confirm the log reports the checked-out source SHA and an installed Xcode version 26 or newer.
4. Download `FuoEvolve-unsigned-ipa-<7-character-source-sha>` and verify the IPA against `SHA256SUMS.txt`.

The workflow also uploads the raw `.app` bundle. Both artifacts are retained for 14 days. Artifact and IPA names use the checked-out commit, which may differ from the workflow file's commit when `ref` points to a feature branch.

## Build 1 acceptance on iOS 15

Install the IPA with TrollStore on a real iOS 15 device and record the device model, iOS version, source SHA, and workflow run URL. Verify:

- the app launches and a provider login persists after relaunch, exercising Keychain access;
- play, pause, track changes, and background audio work;
- NetEase, QQ Music, Bilibili, and YouTube Music each complete one search and one playback attempt;
- the listening-history page shows the played item and its aggregate listening statistics;
- YouTube Music does not crash on its QuickJS challenge path.

Do not add framework-copy, entitlement, or signing fallbacks before an observed CI or device failure identifies the failing step. Windows can compile Kotlin iOS sources into KLIBs and validate shared and Android behavior, but cannot compile the Swift shell, link the iOS framework, run `ciIosTest`, or replace this device acceptance pass.

## Build 2 acceptance on iOS 15

- With Apple Music access denied, open Local Music and verify the optional permission action remains visible and the page is usable.
- Download a song, open the Downloads directory in Local Music, and play it with networking disabled.
- In Files, open On My iPhone / FuoEvolve, add an MP3 or FLAC outside `FeelUOwn`, refresh Local Music, and play it from Imported Files.
- Verify a downloaded file occurs once, retaining its provider title, artist, album, artwork, and duration after restarting the app.
- Change minimum duration and excluded directories, then restore the filters. Eligible tracks should return.
- Edit metadata and save lyrics, refresh, and verify the edits remain. Rename or delete an imported file, refresh, and verify its old entry disappears.
- Grant Apple Music access and verify its library appears without losing downloaded or imported songs.
- Recheck playback history, background audio, and provider login persistence.

## Verification record

On October 17, 2026, the M1 implementation at `0f347ed` passed local `ciArchitectureCheck ciAndroidTest`: 153 JUnit suites, 610 tests, zero failures or skips. The CI aggregate was corrected to include lazily registered KMP tests; before that correction, a successful aggregate run omitted host tests.

At `674b689`, the new iOS local-library implementation and its test sources passed Kotlin iOS simulator KLIB compilation on Windows. This checks Kotlin types and native API declarations; it does not execute tests or compile the Swift scanner. `ciArchitectureCheck` also passed. Local file access is composed in `IosAppContainer`; scanning stays in the platform adapters, and existing physical feature modules retain their ownership.

The integrated implementation passed a fresh local `ciArchitectureCheck ciAndroidTest` run: 154 JUnit suites, 612 tests, zero failures/errors/skips. Task and whole-branch reviews approved the implementation; the final scan-failure fixture was corrected to inject file existence explicitly.

The first manual Apple run, `39016946785` in `polybeta2/FuoEvolve`, returned HTTP 500 on dispatch and remained queued without jobs; cancellation reported it already completed. Run `39017368713` then exposed that checkout requires a full SHA, and run `39017475935` exposed a broken pipe caused by early exit when parsing Xcode output. Both input validation and output consumption were corrected in `55e3dcc`.

Run `39017812603` used default-branch workflow `6b5c1c2` and source `55e3dcc` with Xcode 26.6. `ciIosTest` passed on the macOS runner. Release device linking failed in Kotlin/Native `DevirtualizationAnalysis` with `java.lang.OutOfMemoryError: Java heap space`; packaging was not reached. The follow-up workflow adjusts resources only in CI. Swift/device packaging and real-device acceptance still require a successful later run and installation.
