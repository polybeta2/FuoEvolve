# iOS 15 Parity Build Guide

The iOS package is built manually in GitHub Actions. It is unsigned and intended for installation on an iOS 15 test device with TrollStore; the workflow does not use an Apple developer account or App Store signing.

## Run the unsigned IPA workflow

1. Open the fork's **Actions** page and select **iOS Unsigned IPA**.
2. Choose **Run workflow** from the default branch, then set:
   - `ref`: branch or commit to build. The default is `feat/ios15-parity`.
   - `configuration`: `Release` for device acceptance or `Debug` for diagnostics.
   - `run_tests`: keep enabled unless a prior run of the same source SHA already passed `ciIosTest`.
3. Confirm the log reports the checked-out source SHA and an installed Xcode version 26 or newer.
4. Download `FuoEvolve-unsigned-ipa-<source-sha>` and verify the IPA against `SHA256SUMS.txt`.

The workflow also uploads the raw `.app` bundle. Both artifacts are retained for 14 days. Artifact and IPA names use the checked-out commit, which may differ from the workflow file's commit when `ref` points to a feature branch.

## Build 1 acceptance on iOS 15

Install the IPA with TrollStore on a real iOS 15 device and record the device model, iOS version, source SHA, and workflow run URL. Verify:

- the app launches and a provider login persists after relaunch, exercising Keychain access;
- play, pause, track changes, and background audio work;
- NetEase, QQ Music, Bilibili, and YouTube Music each complete one search and one playback attempt;
- the listening-history page shows the played item and its aggregate listening statistics;
- YouTube Music does not crash on its QuickJS challenge path.

Do not add framework-copy, entitlement, or signing fallbacks before an observed CI or device failure identifies the failing step. Windows can validate shared and Android behavior but cannot compile the Swift shell, link the iOS framework, run `ciIosTest`, or replace this device acceptance pass.
