# FuoEvolve iOS 15 功能补齐与未签名 IPA 流水线

- 日期：2026-10-17
- 状态：待用户审阅
- 基线：`feeluown/FuoEvolve` master `04369ff`（2026-10-14）
- 目标设备：iOS 15 + TrollStore（未签名 IPA）

## 1. 背景与目标

上游 iOS 工程的最低部署目标已经是 15.0，但从未发布过 iOS 版本，也缺少部分 Android 已有能力。本设计只做一个可由用户自己安装、日常可用的 iOS 15 分支，不追求上游合并，也不做 App Store 发行。

目标：

1. 在 GitHub fork 中增加一个**手动触发**的 unsigned IPA 构建，产物可直接用 TrollStore 安装。
2. 接入 iOS 播放历史/统计，复用已有的 SQLDelight 基础设施。
3. 让 iOS 本地音乐支持“下载入库 + Files 放歌”，同时保留一个非阻塞的 Apple Music 资料库授权入口。
4. 不重新实现音乐源；四个 provider 已在共享代码中，重点是 iOS 15 真机验证。
5. 尽量减少 20+ 分钟的 iOS CI 构建次数：本地先跑架构检查与 Android host 测试，iOS 整包只在里程碑手动构建。

## 2. 非目标

- 不上架 App Store，不配置 TestFlight、签名、公证或隐私清单。
- 不做任意文件夹扫描（security-scoped bookmarks）；第一版只覆盖 App Documents 与用户通过 Files 放入沙盒的文件。
- 不把 iOS UI 改写为 SwiftUI；现有 Compose UI 继续复用。
- 不重写四个 provider，也不承诺 YTMusic 在 iOS 15 上无需修复。
- 不做应用图标、启动页、iPad 专项适配。

## 3. 约束

- 用户环境为 Windows + WSL2 + xtool/Swift 6.3.3，没有 Mac。`xtool` 只支持 SwiftPM 工程，本仓库是 `.xcodeproj`；Kotlin/Native Apple 目标的最终二进制必须在 macOS 上产出。因此 iOS 整包只能由 GitHub Actions 构建。
- 本地可验证：Gradle 架构检查、Android host 测试、Android 编译、diff 检查。不能本地编译 `iosMain` 或 Swift 壳。
- fork 公开，保留 GPLv3 `LICENSE`；不向上游推送，不自动创建 PR。
- iOS 15 典型设备为 iPhone 6s/7/SE1（2GB RAM），性能与内存需要真机验收。
- 音乐源中 YTMusic 依赖 `quickjs-kt`；现有 CI 日志显示 QuickJS 与 ICU 目标文件按 iOS 26.5/18.5 编译、链接到 15.0 只有 warning，这是重点运行时风险。

## 4. 总体方案

### 4.1 Fork 与分支

- fork 到 `polybeta2/FuoEvolve`，保留 upstream remote。
- fork 默认分支 `master` 只增加工作流与说明，不改业务代码。
- 功能分支：`feat/ios15-parity`。
- 在 fork 中启用 Actions，禁用上游那些由 push/PR/tag 自动触发的重工作流，只启用本设计的 iOS 手动构建，避免每次 push 消耗 20+ 分钟。
- 工作流文件必须存在于 fork 默认分支才能手动触发；`master` 上的工作流通过 `ref` 输入检出功能分支。

### 4.2 GitHub Actions：unsigned IPA

新增 `.github/workflows/ios-unsigned-ipa.yml`：

- 触发：仅 `workflow_dispatch`。
- 输入：
  - `ref`：要构建的分支或 SHA，默认 `feat/ios15-parity`。
  - `configuration`：`Release`（默认）或 `Debug`。
  - `run_tests`：是否先跑 `ciIosTest`，默认 `true`。
- Runner：`macos-26`，Xcode 26.6，JDK 17。
- 缓存：`~/.konan`（key 包含 `libs.versions.toml` 哈希）和 Gradle（`gradle/actions/setup-gradle`）。
- 步骤：
  1. `actions/checkout@v5`，`fetch-depth: 0`，`ref: ${{ inputs.ref }}`。
  2. 准备 Gradle versioning 需要的 master ref（沿用现有 iOS 工作流做法）。
  3. 可选 `./gradlew --warning-mode all ciIosTest`。
  4. 构建 device app：
     ```bash
     xcodebuild \
       -project iosApp/FuoEvolve.xcodeproj \
       -scheme FuoEvolve \
       -configuration "${CONFIGURATION}" \
       -sdk iphoneos \
       -destination "generic/platform=iOS" \
       -derivedDataPath build/ios \
       CODE_SIGNING_ALLOWED=NO \
       CODE_SIGNING_REQUIRED=NO \
       CODE_SIGN_IDENTITY="" \
       DEVELOPMENT_TEAM="" \
       build
     ```
  5. 打包：
     ```bash
     mkdir -p Payload
     cp -R "build/ios/Build/Products/${CONFIGURATION}-iphoneos/FuoEvolve.app" Payload/
     zip -qry "FuoEvolve-unsigned-${GITHUB_SHA::7}.ipa" Payload
     sha256sum "FuoEvolve-unsigned-${GITHUB_SHA::7}.ipa" > SHA256SUMS.txt
     ```
  6. 上传 IPA、SHA256、`.app`（可选）为 artifact，保留 14 天。

- 若 `embedAndSignAppleFrameworkForXcode` 在无签名 device 构建下失败，回退为：
  ```bash
  ./gradlew :shared:linkReleaseFrameworkIosArm64
  ```
  然后把 `Shared.framework` 手动复制进 `FuoEvolve.app/Frameworks`，重新压缩。
- 不添加签名/Apple 账号步骤。TrollStore 安装 unsigned IPA；若 Keychain 因缺少 entitlement 失败，再单独增加 `ldid`/entitlements 方案。

### 4.3 本地验证

在 Windows 上执行：

```bash
./gradlew ciArchitectureCheck
./gradlew ciAndroidTest
git diff --check
```

- `ciArchitectureCheck` 包含 `checkArchitectureBoundaries` 与日志使用检查。
- `ciAndroidTest` 编译 Android app 并运行 Android host 测试，能覆盖 commonMain 的大部分改动。
- 不运行 `ciIosTest`（它依赖 `iosSimulatorArm64Test`，Windows 无法运行）。
- `iosMain`、Swift 壳和整包链接由 GitHub Actions 暴露编译错误。

## 5. 功能设计

### 5.1 播放历史/统计

- `shared/build.gradle.kts` 的 `iosMain.dependencies` 增加 `implementation(project(":persistence:listening"))`。
- `shared/src/iosMain/.../IosAppHost.kt`：
  - 移除 `NoOpListeningHistorySink`。
  - 使用 `SqlDelightListeningHistoryStore(IosListeningHistoryDriverFactory())` 作为 `listeningHistorySink`。
- `SqlDelightListeningHistoryStore` 同时实现 `ListeningHistoryRepository`，`PlaybackFeatureBinding` 会自动把“播放记录”页面接到真实数据。
- 增加 iOS 测试：建库、写入一次播放、读取最近记录；沿用现有 `IosListeningHistoryDriverFactory`。
- 不改共享 UI，不改 Android/桌面。

### 5.2 本地音乐与下载入库

新增文件扫描源，并把多个来源聚合为一个 `LocalMusicRepository`：

- Swift 新增 `iosApp/FuoEvolve/IOSFileMusicLibrary.swift`，实现新的 Kotlin 接口 `IosFileMusicOutput`：
  - `fun tracksJson(): String`
  - 扫描 `Documents/` 及其子目录；跳过隐藏文件、符号链接和不支持的扩展名。
  - 通过 AVFoundation 读取 title/artist/album/duration；缺失时用文件名兜底。
  - 返回 JSON：`relative_path`、`uri`、`title`、`artists`、`album`、`duration_ms`。
  - 支持扩展名：`mp3`、`m4a`、`aac`、`flac`、`wav`、`aif`、`aiff`。AVPlayer 不能可靠播放的 ogg/opus 第一版不纳入。
- `shared/src/iosMain/.../IosFileMusicOutput.kt` 定义接口；`IosAppHost.MainViewController` 增加 `fileMusicOutput` 参数；`FuoEvolveApp.swift` 的 `SharedComposeRoot` 传入 `IOSFileMusicLibrary.shared`。
- 改造 `IosLocalMusicRepository` 为聚合源：
  - **Apple Music 资料库**：保留现有 `MPMediaLibrary` 源；只有已授权时才返回，目录 id `ios-media-library`。
  - **下载**：读取 `IosDownloadRepository.tasks` 中 `Completed` 且 `completedUri` 非空的记录，保留 provider 元数据，目录 id `ios-file-downloads`。
  - **导入文件**：扫描 `Documents/` 中不属于下载目录的音频文件，目录 id `ios-file-imports`。
  - 按规范化 `localUri` 去重；下载记录优先于同名扫描文件，避免重复。
  - 下载文件位于 `Documents/FeelUOwn/`；扫描文件 id 使用 `ios-file:<relative_path>`，下载记录沿用 provider track id。
  - `isDatabaseReady()` 返回 true（App 沙盒文件始终可读）；`isDatabaseStale()` 在首次扫描或缓存为空时返回 true。
  - `updateMetadata`/`saveLyrics` 继续使用 NSUserDefaults，键为 track id；文件重命名会得到新 id，旧覆盖数据保留但不复用。
- `Info.plist` 增加：
  ```xml
  <key>UIFileSharingEnabled</key><true/>
  <key>LSSupportsOpeningDocumentsInPlace</key><true/>
  ```
  这样用户可在“文件”App → 我的 iPhone → FuoEvolve 中放歌。
- 播放：`MusicTrack.localUri` 使用 `file://` URL，现有 `IOSNativeAudioEngine` 的 `AVPlayer` 路径可直接播放；实现后在真机验证。
- 不改 Android 的 `MediaStore` 实现；所有新代码限定在 iOS 适配层。

### 5.3 Apple Music 非阻塞授权入口

iOS 的 `hasAudioPermission` 语义改为“本地音乐入口可用”，固定为 true（沙盒文件无需系统媒体库权限）。Apple Music 权限单独作为可选能力：

- `AppPlatformBindings` 增加默认字段：
  ```kotlin
  val hasAppleMusicPermission: Boolean = true,
  val showAppleMusicPermission: Boolean = false,
  val onRequestAppleMusicPermission: () -> Unit = {},
  ```
  Android/桌面保持默认值，行为不变。
- iOS `IosAppHost` 传入：
  - `hasAudioPermission = true`
  - `showAppleMusicPermission = true`
  - `hasAppleMusicPermission` 来自 `MPMediaLibrary.authorizationStatus() == .authorized` 的 Compose 状态
  - `onRequestAppleMusicPermission` 调用 `IOSMediaLibraryOutput.requestPermission` 并更新状态。
- `LocalMusicSection` 增加对应参数；仅当 `showAppleMusicPermission && !hasAppleMusicPermission` 时显示一条非阻塞提示卡：
  - 文案说明“只影响 Apple Music 资料库，不影响下载与文件导入”。
  - 按钮“允许访问”触发系统授权。
  - 授权成功后通过 `LaunchedEffect` 触发一次 `graph.feature.refresh()`。
- 原 `PermissionPanel` 仍保留给 Android；iOS 因 `hasAudioPermission=true` 不会进入阻塞分支。
- `IosLocalMusicRepository.hasPermission` 只表示 Apple Music 媒体库授权；本地文件访问始终可用。`LocalMusicFeature` 的权限来自 `AppPlatformBindings.hasAudioPermission`（iOS 固定 true），因此下载完成后的本地库刷新不会被 Apple Music 权限卡住。
- 更新 `HomeScreen`、`MineHomeFeatureSection`、`AppNavHost` 的参数透传；Android 调用点使用默认值。

### 5.4 音乐源

- 不新增 provider 代码。NetEase、QQ Music、Bilibili、YouTube Music 都已在 `commonMain`，并随 iOS framework 编译。
- iOS 已有 QQ QRC inflater、NetEase SecureRandom、Ktor Darwin 网络层。
- Build 1 真机验收四个源的搜索/播放；YTMusic 额外验证 QuickJS challenge。
- 若 YTMusic 在 iOS 15 崩溃或播放失败：
  1. 先确认是否由 QuickJS/ICU 的 min-OS warning 引起；
  2. 尝试用最低 iOS 15 重新编译 QuickJS 依赖；
  3. 若不可行，记录为已知限制，不阻塞其他源与本地功能。

## 6. 错误处理与边界

- 文件扫描失败：返回空列表并保留其他来源；UI 显示现有错误信息，不崩溃。
- Apple Music 未授权/被拒：提示卡保留，文件与下载源正常。
- 下载记录与扫描文件重复：按 URI 去重，下载元数据优先。
- 文件被删除/移动：下次刷新剔除；id 变化时旧元数据覆盖不再生效。
- TrollStore Keychain 失败：作为 Build 1 验收项；若失败，增加 entitlements + ldid 签名/重打包步骤。
- 不支持的音频扩展名：不进入本地库，避免“能显示不能播”。

## 7. 验收

### Build 1（fork + CI + 播放历史）

- 触发一次手动 IPA 构建，安装到 iOS 15 设备。
- 启动、登录一个 provider（验证 Keychain）、播放/暂停/切歌、后台音频。
- 网易/QQ/B站/YTMusic 各做一次搜索与播放。
- 播放记录页出现最近记录；“常听/统计”有数据。
- 确认 YTMusic 是否受 QuickJS min-OS 影响。

### Build 2（本地音乐 + Apple Music 入口）

- 下载一首歌，本地音乐出现“下载”目录，能离线播放。
- 通过 Files 放一首歌到 FuoEvolve Documents，刷新后出现“导入文件”。
- Apple Music 提示卡不阻塞页面；授权后出现资料库曲目。
- 本地文件播放、元数据、最小 duration 过滤、重复文件去重正常。
- 播放历史仍正常，Android 无回归。

## 8. 风险

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| QuickJS/ICU 目标文件 min-OS 高于 15 | YTMusic 在 iOS 15 可能崩溃 | Build 1 真机验证；必要时重编 QuickJS；否则记录限制 |
| TrollStore 下 Keychain 缺 entitlement | provider 登录无法持久化 | Build 1 验证；失败则增加 entitlements + ldid |
| 无签名 device 构建的 framework 嵌入失败 | IPA 构建失败 | 回退为手动 `linkReleaseFrameworkIosArm64` + 复制 |
| iOS 15 旧设备性能/内存 | 启动慢、播放卡顿 | 真机验收；本阶段不做性能优化 |
| 共享 UI 权限参数改动影响 Android | 编译/行为回归 | `ciAndroidTest` + 已有测试；Android 使用默认值 |
| 文件元数据读取阻塞 | 本地库刷新慢 | 扫描放在后台 dispatcher；限制扩展名与目录 |

## 9. 里程碑

1. **M1**：fork、工作流、播放历史；Build 1；真机冒烟。
2. **M2**：文件扫描、下载入库、Files 分享、Apple Music 提示卡；Build 2；真机验收。
3. **M3**：根据 Build 1/2 结果修复 YTMusic/Keychain/构建问题；必要时再触发一次 IPA。

## 10. 决策记录

- 采用“先基线后功能”的方案 A，减少基线问题与功能问题混杂。
- 保留 Apple Music 授权入口，但必须是非阻塞；文件/下载是第一优先级。
- 不做任意文件夹扫描、不做 SwiftUI 重写、不做签名/上架。
- 构建只手动触发；不因 push 自动构建。
- 本地无法完整编译 iOS，验收以 CI 构建 + 用户 iOS 15 真机为准。
