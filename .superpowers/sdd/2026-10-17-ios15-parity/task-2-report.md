# Task 2 Report: iOS file/download local library

## Outcome

Implemented the iOS sandbox file scanner and the three-source local-library
aggregation described by the approved iOS 15 parity spec.

The local library now combines:

- Apple Music media-library tracks in ios-media-library;
- completed, existing downloads in ios-file-downloads;
- supported audio files outside Documents/FeelUOwn/ in ios-file-imports.

Sandbox readiness is independent of Apple Music authorization. A denied Apple
Music permission only removes that source; downloads and imported files still
load.

## Implementation details

### Swift file scanner

- Added IOSFileMusicLibrary.shared, conforming to IosFileMusicOutput.
- Recursively enumerates Documents/.
- Skips hidden entries, symbolic links, directories, and unsupported file
  extensions.
- Supports mp3, m4a, aac, flac, wav, aif, and aiff.
- Reads title, artist, album, and duration from AVFoundation, with filename and
  unknown-artist fallbacks.
- Emits the specified JSON root and fields.
- Registered the source in the Xcode project.
- Enabled Files app sharing and opening documents in place.

### Kotlin aggregation

- Added the IosFileMusicOutput bridge and an empty default implementation so
  old tests and call sites remain source-compatible.
- IosLocalMusicRepository accepts injectable file output, download snapshot,
  existence check, and NSUserDefaults.
- File scanning, JSON parsing, existence checks, and candidate construction run
  under Dispatchers.Default.
- Repository state publication is serialized by a mutex after background work
  completes.
- Source and item failures are isolated; coroutine cancellation is rethrown.
- Completed download metadata wins URI collisions while missing legacy duration
  can be recovered from the scanner result for the same canonical file URL.
- File URLs are canonicalized through Foundation, covering dot-path and percent
  encoding aliases.
- Deleted download files and removed imports disappear on the next refresh.
- Existing metadata and lyric override keys remain unchanged.
- The existing Apple directory display name 歌曲 is preserved.

### Download task persistence

- Kept the existing line-oriented task format backward-compatible.
- Appended duration, provider id/name, cover URL, and lyrics fields.
- Optional text fields are JSON-string encoded so multiline lyrics do not split
  persisted task records.
- Exposed internal encode/decode helpers for focused round-trip coverage.

### Wiring

- Added fileMusicOutput to the exported Kotlin host arguments.
- Swift passes IOSFileMusicLibrary.shared.
- IosAppContainer constructs IosDownloadRepository before
  IosLocalMusicRepository and injects a current task snapshot lambda.

## Test coverage added

IosLocalMusicRepositoryTest covers:

- aggregation of Apple, download, and imported-file sources;
- denied Apple permission with sandbox sources still ready;
- malformed file JSON and failed Apple scans isolated from other sources;
- canonical URI deduplication and download metadata precedence;
- legacy download duration recovery from scanned metadata;
- per-directory exclusion using canonical trailing-slash aliases;
- minimum-duration filtering;
- deleted/moved files disappearing after refresh;
- metadata and lyric overrides surviving refresh;
- persisted provider metadata and multiline lyrics round-tripping.

The pre-existing IosPlatformRepositoriesTest constructor and 歌曲 assertion
remain compatible.

## Verification

Passed locally:

- gradlew.bat :shared:compileKotlinIosSimulatorArm64
- gradlew.bat :shared:compileTestKotlinIosSimulatorArm64 --quiet
- gradlew.bat ciArchitectureCheck --quiet
- git diff --check for Task 2 files
- XML parse of Info.plist
- static Xcode project registration check for the new Swift source

Not run locally:

- iOS test binaries. Windows can compile the Kotlin iOS test sources but cannot
  execute the simulator tests.
- Swift/Xcode application compilation and runtime scanning. These require the
  macOS CI job and device/simulator verification.

Therefore there is no claimed native RED/GREEN execution evidence for the new
iOS tests; executable iOS tests and the Swift bridge remain pending CI.

## Deferred

- Optional Apple Music permission UI is Task 3.
- Device validation of Files import, offline playback, AVFoundation metadata,
  and refresh after external file changes remains part of Build 2 acceptance.
