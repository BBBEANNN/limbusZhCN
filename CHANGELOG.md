# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added

- Add exact Firebase Auth `genericidp`/`recaptcha` browser redirect routing back into the containerized Limbus activities for Apple ID and reCAPTCHA completion.
- Add an in-app, per-process rolling diagnostic log with uncaught-exception capture, automatic redaction, bounded retention, historical exit data, and a ready-to-copy GitHub Issue template in the exported ZIP.
- Add a device compatibility card covering Android/API level, arm64 availability, runtime bitness, kernel page size, low-RAM state, battery optimization, and OEM background settings for vivo/iQOO and other common Android brands.
- Expand install compatibility from Android 12 / API 31 down to Android 8.0 / API 26, matching the current Limbus Company package minimum while keeping the game-required arm64 runtime boundary.
- Add schema-8 runtime text policy coverage for `story`, `relatedChapterText`, and `openConditionNumber`, and reject definitively corrupted Unicode/control-code translations while preserving Japanese source text.
- Add per-component TMP font-size and auto-size state capture so translated labels can fit fixed game rectangles and restore their original layout when recycled.
- Add the GPL-3.0 project license and a third-party notice inventory for the public source release.
- Add a Limbus Activity classloader probe and prefer the bound `LoadedApk` classloader when replacing VirtualApp stub activities, so AppSealing-added payload dex visibility can be inspected during Unity Activity creation.
- Add Limbus thread context classloader diagnostics during application bind and Unity Activity lifecycle entry, verifying whether Firebase/Unity JNI class lookups can see the AppSealing-mounted payload dex.
- Add a Limbus-only JNI `FindClass` fallback for Firebase classes, routing failed native lookups through the AppSealing-mounted game classloader without enabling the full VirtualApp VM hook.
- Add a Limbus Firebase C++ `auth_resources_lib.jar` fallback loader with the game classloader as parent, so native Firebase Auth listener classes can resolve their Firebase SDK dependencies.
- Add a debug intent option to sync the currently installed Google Play Limbus split APKs before launching the containerized game.
- Add Limbus-specific `ConnectivityManager` context proxy diagnostics so Android 12 network request attribution can be inspected and masked.
- Add native backtrace logging for Limbus `exit`, `_exit`, and `abort` hooks so AppSealing/Unity caller libraries and offsets are visible in logcat.
- Add a targeted libcovault AppSealing `exit_group(0)` patch for the Redmi/Android 12 black-screen path at `libcovault-appsec.so +0x20d40`.
- Add Limbus-specific `/proc/self/fd` readlink path fallback masking so host VM paths under `com.example.limbuszhcn/virtual` are reported as game-visible `/storage/emulated/0` or `/data/user/0/com.ProjectMoon.LimbusCompany` paths.
- Add a debug-build adb intent entry to launch the containerized game directly for log capture.
- Add PlayCore local-testing asset-pack wiring for Limbus, using private `*-master.apk` symlinks to the installed Google Play base/split APKs.
- Add Limbus PackageManager metadata injection for `local_testing_dir` so PlayCore uses `FakeAssetPackService` instead of host Play Store asset delivery.
- Add a short AppSealing-time syscall scanner during Limbus `makeApplication` so native inline syscall patching can run before `Kill Process [50040]`.
- Add Limbus-specific arm64 native syscall scanning for AppSealing/Unity libraries to hook inline `kill`, `tgkill`, `exit`, and `exit_group` paths.
- Add VirtualApp Google suite synchronization before launch, including GMS, GSF, Play Store, Play Games, and split APK native library copying.
- Add VirtualApp launch diagnostics around `AppInstrumentation`, `HCallbackStub`, and AppSealing class loading.
- Add focused launch diagnostics for the host app, VirtualApp adapter, `VActivityManagerService`, `ActivityStack`, and `ShadowActivity`.
- Add VirtualApp native ndk-build packaging for `arm64-v8a` so `libv++_64.so` is included in the debug APK.
- Add `:virtualapp` as a local Gradle module from the vendored VirtualApp Android 12 source.
- Add `LimbusZhCNApplication` to initialize `VirtualCore` before container operations.
- Add installed-source VirtualApp import support so Google Play `base.apk` and split APK paths can be reused without weekly manual APK selection.
- Add installed Google Play game syncing for container imports, copying `base.apk` and installed split APKs through `PackageManager`.
- Add container import metadata for source, split count, and game `versionCode` so Google Play updates can be detected.
- Add `GameStorage` abstraction and container runtime planning interfaces for the container-mode migration.
- Add local container workspace storage spike for split APK import state and container-style `Localize/` file operations.
- Add a container-mode Spike UI for selecting split APKs, importing them into the local workspace, probing container storage, and showing the launch placeholder.
- Add Android-side `.7z` translation archive extraction with manifest generation and path validation.
- Add relay-backed translation update installation flow for version/hash lookup, package download, cache reuse, and extraction.
- Add container-mode translation install and uninstall actions backed by `GameStorage`.
- Add generated patch manifest parsing so cached translation packages can be uninstalled after app restart.
- Add container translation baseline snapshots before patching, enabling restore of overwritten files and removal of newly added patch files.
- Add a container runtime factory and VirtualApp reflection adapter as the entry point for the real container engine integration.
- Add a pinned VirtualApp vendoring script and migration notes for the real container engine work.
- Add direct Shizuku UserService integration for permission checks, game directory probing, and writing extracted translation files into `Localize/en/`.
- Add uninstall translation action that deletes installed patch files through Shizuku based on the latest patch manifest.

### Changed

- Replace the bundled Sarasa Gothic SC Bold face with the official Regular face so translated game text uses a lighter weight.
- Share the Android display-field policy between index collection and the controlled term-field subset, and align the native object-field traversal with the same verified display fields.
- Make GitHub Releases the only translation update source and keep only the public Releases URL in advanced settings.
- Compact `AGENTS.md` into a short operational contract and move longer project background into `docs/project-architecture.md`, reducing default Codex context load while keeping architecture details available.
- Make Limbus native DNS and socket connections bypass VirtualApp network strategy unconditionally, with bounded target/result diagnostics for container-only network failures.
- Block Limbus from binding host GMS `MeasurementBrokerService`; Firebase Analytics now fails with `API_UNAVAILABLE` instead of handing a real MeasurementService binder back to the containerized game.
- Isolate Limbus PackageManager access from host package/component/list queries while still allowing explicit host Google package info lookups needed by GMS and Play integration.
- Wrap host GMS `IGmsServiceBroker` connections for Limbus and rewrite `GetServiceRequest` package fields to the host package, avoiding the Play Services broker rejection path.
- Stop the native caller thread after patching the known `libcovault-appsec.so +0x20c58` exit path instead of returning to AppSealing caller code.
- Keep Limbus `requestNetwork` Binder string package arguments on the host package while limiting package masking to the Limbus context proxy, preserving container attribution during login and networking.
- Reduce package-visibility log noise by returning `false` for missing host packages instead of printing full `NameNotFoundException` stacks.
- Route Limbus PlayCore asset-pack queries away from host Play Store ownership checks and into local testing mode.
- Align Limbus-visible `ApplicationInfo` source, public source, split source, native library, and data directories with the real Google Play install.
- Keep virtual GSF available while removing virtual GMS/Play Store before launch, exposing only selected host Google packages to Limbus.
- Stop the current Limbus native caller thread for intercepted `exit`, `_exit`, and `abort` paths instead of returning to AppSealing code.
- Disable the debug APK's Android debuggable flag while retaining the `debug` build type, avoiding Android 12 CheckJNI fatal paths during Limbus startup.
- Avoid foreground `GmsSupport.installGApps(0)` during sync/launch; the container now only probes host-visible Google packages and copies native libraries for Google packages already installed inside VirtualApp.
- Replace the failed `SecurityManager` AppSealing exit trap with stable fallback diagnostics for package paths, split APKs, installer, signatures, and storage directories.
- Move stub Activity launch interception forward to `AppInstrumentation.newActivity()` when Android 12 does not deliver a usable handler launch callback.
- Document AppSealing sealed dex as the current container launch blocker: bypassing AppSealing prevents `UnityPlayerActivity` from entering the virtual classloader.
- Skip VirtualApp native VM hook on Houdini/`libnb.so` translated runtimes to avoid `libv++_64.so` startup crashes on MuMu.
- Bypass Limbus Company's `com.inka.appsealing.AppSealingApplication` inside the virtual process by using the default Android `Application` during `makeApplication`.
- Allow non-critical VirtualApp hook injection failures to be logged and skipped during Android 12 startup.
- Use the complete local NDK `30.0.14904198` for VirtualApp native builds because the local `27.0.12077973` install is incomplete.
- Migrate the vendored VirtualApp module to AGP 8/compileSdk 35 basics, AndroidX annotations/core, explicit namespace, and Android 12 exported manifest requirements.
- Update the VirtualApp launch adapter to trust only VirtualApp-resolved game launch intents and start them through `VActivityManager`.
- Document the current VirtualApp native blocker: the APK must package `libv++_64.so` before the virtual game process can run.
- Switch the documented production plan from Shizuku to container mode after confirming the game rejects Shizuku-visible environments.
- Move Shizuku file operations behind the `GameStorage` abstraction so they remain a debug path instead of the production dependency.
- Align AndroidX dependency versions with AGP 8.8.0 and compileSdk 35.
- Replace the template Compose screen with a Shizuku-focused installation UI.
- Refine the installation UI into a three-step Shizuku, game directory, and translation package workflow with persisted relay settings.
- Prefill relay URL and token from the `zero-autoupdate` defaults when local settings are empty.
- Show whether the container import matches the currently installed Google Play game version.
- Remove the Shizuku UI path and keep the app flow focused on container import, probing, and launch.

### Removed

- Remove relay endpoint, token, version/hash API, relay download code, and channel-selection UI.
- Remove Shizuku dependencies, manifest permission/provider, UserService AIDL, Shizuku file gateway code, and related unit tests.

### Fixed

- Isolate the confirmed `libcovault-appsec.so +0x248d4` Android 13 background-thread fault instead of escalating it into a whole-game SIGSEGV, fixing the v1.2 launch-after-logo regression reported in Issue #1.
- Skip the legacy ART `jmethodID` VM hook for containerized microG Services/FakeStore, preventing Android 13 login helper processes from crashing in `libv++_64.so::hookAndroidVM()`.
- Stop unhandled synchronous SIGSEGV handlers from returning to an unchanged faulting context and flooding logcat, and preserve sanitized native tombstones as protobuf instead of corrupting them through UTF-8 conversion.
- Fix Chinese card names, tabs, skill titles and descriptions overlapping or clipping by auto-sizing short labels, slightly reducing long-body text, and preserving the game's original line spacing.
- Preserve translated-font provenance by exact generated text content, so IL2CPP string copies no longer fall back to mixed Japanese/Chinese glyph faces.
- Derive formatted keyword names, bracket contents and leading skill conditions from the downloaded translation package, prioritize its `Bufs*`/`BattleKeywords*` glossaries over conflicting page text, and support one-character or expanding Chinese terms without repeated replacement.
- Reject partial embedded-term results that still contain Japanese kana, preventing the runtime fallback from creating new mixed Chinese/Japanese fragments when a complete package mapping is unavailable.
- Keep the Chinese font's own SDF material instead of copying incompatible outline parameters from the original font, preventing broken or filled-in glyph strokes.
- Allow screenshots of the Limbus guest by clearing only its `FLAG_SECURE` window bit during Activity resume and window-session add/relayout calls.
- Prefer Limbus' Firebase resource jars before the host APK in the Firebase C++ fallback loader, so real Firebase Auth SDK classes are not shadowed by the container's minimal shim classes during Google sign-in.
- Preserve Limbus Credentials `HiddenActivity` GMS Parcelable and Binder extras by launching it through a client-local VirtualApp stub instead of unmarshalling the request in the VAMS server process.
- Fix the remaining Limbus container black screen by preserving libc `getcwd()` pointer-return semantics instead of exposing the raw syscall byte count, which previously became `strlen(0x2)` during Addressables initialization.
- Fix Limbus startup crash caused by host GMS `MeasurementBrokerService` rejecting `GetServiceRequest` with `Unknown calling package name 'com.ProjectMoon.LimbusCompany'`.
- Reduce host environment leakage during Limbus package and intent queries by avoiding generic host PackageManager fallback for virtual-package misses.
- Fix missing Google Play services detection inside the Limbus container by exposing host `com.google.android.gms` through the Limbus PackageManager hook.
- Fix stale PlayCore local-testing asset-pack symlink handling after Google Play game updates by detecting broken symlinks with `lstat`.
- Patch the known libcovault `exit_group(0)` syscall reached after `libcovault-appsec.so +0x20c58` calls `exit(0)`.
- Explicitly allow Limbus `SIGPWR` and `SIGXCPU` in native signal guards to avoid Unity il2cpp `abort()` during startup.
- Fix the Limbus black-screen blocker caused by PlayCore binding host Play Store `AssetModuleService` and receiving `API_NOT_AVAILABLE(-5)` / package-ownership rejection.
- Fix recursive PackageManager hook calls while injecting PlayCore `local_testing_dir` metadata by adding a thread-local guard.
- Fix AppSealing inline syscall detection by scanning backward from `svc #0` for recent `x8` / `w8` syscall-number setup instead of requiring the `mov` to be the immediately preceding instruction.
- Fix unstable inline syscall interception by replacing Limbus terminating `svc` instructions with `mov x0,#0` instead of attaching `MSHookFunction` directly to `svc` addresses.
- Extend Limbus container native signal guards to block `SIGTERM`, `SIGABRT`, `SIGQUIT`, `SIGTRAP`, `SIGUSR1`, and `SIGUSR2` in `kill`, `tkill`, `tgkill`, `pthread_kill`, `raise`, and direct syscall paths.
- Block additional Limbus container native termination paths including `exit`, `_exit`, `abort`, signal APIs, and direct `syscall(__NR_exit*)` calls.
- Fix Android 13 VirtualApp package-manager flag handling for `Integer`, `Long`, and flags wrapper arguments.
- Fix Android 13/MIUI storage probing failures by using a private synthetic TF root and tolerating removable-storage `SecurityException`.
- Fix Limbus visible `LoadedApk` data directory patching on Android 12 where `mDataDir` is a `String`, not a `File`.
- Fix native `readlinkat` reverse path handling for non-null-terminated buffers and direct `syscall(__NR_readlinkat)` calls.
- Prevent `ShadowActivity` from falling back to the real system game when the virtual classloader cannot load the target Activity.
- Classify host `:pN` stub processes as VirtualApp client processes before server-side activity mapping is fully ready.
- Guard Android 12 `ActivityThread.AppBindData` and `VMRuntime` reflection writes to prevent null-reference crashes during virtual application binding.
- Fix the VirtualApp `ShadowActivity` recursion by using `VClient` process config before `VirtualRuntime` is initialized.
- Bind the virtual application before `ShadowActivity` starts the real target Activity, avoiding a pre-bind `getCurrentApplication()` null crash.
- Fix a container launch loop where starting the game could resolve back to the host launcher and repeatedly reopen the localizer.
- Fix Android 12 startup blockers in VirtualApp system update, lock settings, notification, context, application thread, and stub activity compatibility paths.
- Fix VirtualApp startup crashes caused by legacy phone/SMS observers requiring restricted permissions.
- Fix Android 12 VirtualApp package parsing by avoiding direct dependency on unavailable `PackageParser.CallbackImpl` constructors.
- Fix VirtualApp handler hook initialization when reflected Android message constants are unavailable.
- Replace the proprietary Safekey integration with an unsupported no-op service stub so the vendored module can compile.
- Fix Android 12 archive extraction by replacing `Path.of()` usage with runtime-compatible path normalization.
- Fix translation installation target mapping so existing `EN_*.json` game files are overwritten instead of only writing no-prefix copies.

### Verified

- Verify on Redmi/Android 12 at `192.168.39.124:5555` that selecting Google login from the container launches the system Google account selector above the client-local Credentials `HiddenActivity`; both `ShadowActivity$P0` records remain in the task and the previous VAMS null-record crash/host ANR no longer occurs.
- Verify on Redmi/Android 12 at `192.168.64.102:5555` that both the original and containerized Limbus show the same network error while YumeBox is stopped, then both reach the login method dialog after its validated VPN NetworkAgent is restored. The container remains in `ShadowActivity$P0` under host UID `10259` and its network request is assigned to the YumeBox VPN.
- Verify `:app:testDebugUnitTest :app:assembleDebug` after making the Limbus native network path bypass VirtualApp strategy and adding bounded DNS/connect diagnostics.
- Verify `:app:testDebugUnitTest :app:assembleDebug` after switching Activity creation to the bound `LoadedApk` classloader and blocking host GMS `MeasurementBrokerService`.
- Verify `:app:testDebugUnitTest :app:assembleDebug` after adding the Limbus-only JNI `FindClass` fallback and Firebase C++ jar loader.
- Verify on a Redmi/Android 12 device that Limbus `versionCode=441` installs and launches through `ShadowActivity$P0`, reaches `PlayVideo -> PlayWarningAnim -> PlayDone`, loads Firebase C++ Auth listener classes through the fallback, and continues into Firebase Auth sign-out callbacks; the process stays alive but the framebuffer remains pure black.
- Verify on a Redmi/Android 12 device that Google Play Limbus `versionCode=441` / `versionName=1.107.1` syncs from installed `base.apk`, `split_UnityDataAssetPack.apk`, and `split_config.arm64_v8a.apk`, starts through `ShadowActivity$P0`, reaches `PlayVideo -> PlayWarningAnim -> PlayDone`, uses `FakeAssetPackService`, and keeps the containerized process alive, but the visible screen remains pure black.
- Verify the Limbus Activity classloader can load both `com.google.firebase.FirebaseApp` and `com.unity3d.player.UnityPlayerActivity`; Unity native Firebase lookup still logs `Java class com/google/firebase/FirebaseApp not found`.
- Verify host GMS `MeasurementBrokerService` is now blocked for Limbus and the previous `Unknown calling package name 'com.ProjectMoon.LimbusCompany'` MeasurementService failure is replaced by `ConnectionResult{statusCode=API_UNAVAILABLE}`.
- Verify `:app:testDebugUnitTest :app:assembleDebug` after adding GMS broker package rewriting, host PackageManager isolation, and the adjusted libcovault exit handling.
- Verify on Redmi/Android 12 at `192.168.64.103:5555` that Limbus `v1.107.0` starts through `ShadowActivity$P0`, reaches `PlayVideo -> PlayWarningAnim -> PlayDone`, uses `FakeAssetPackService`, and keeps the containerized process alive, but the visible screen remains pure black. A final adb state read was not completed because the device disconnected from adb.
- Verify `:app:testDebugUnitTest :app:assembleDebug` after adding v438 sync, connectivity attribution masking, and PlayCore symlink refresh handling.
- Verify on Redmi/Android 12 at `192.168.64.103:5555` that Google Play Limbus `v438` / `v1.107.0` syncs from installed `base.apk`, `split_UnityDataAssetPack.apk`, and `split_config.arm64_v8a.apk`, starts through `ShadowActivity$P0`, reaches the game login UI, and no longer remains on a pure black screen. The remaining visible failure is the game's `The network is unstable` dialog while YumeBox logs DoH and `api.ip.sb` DNS failures.
- Verify `:app:testDebugUnitTest :app:assembleDebug` after adding native exit backtraces and the targeted libcovault `exit_group` patch.
- Verify `:app:testDebugUnitTest :app:assembleDebug` after explicitly allowing `SIGPWR` / `SIGXCPU` and masking Limbus fd readlink paths.
- Verify on Redmi/Android 12 at `192.168.64.105:5555` that `pthread_kill(..., 30)` is allowed, il2cpp no longer logs `abort >>>`, and `/proc/self/fd` readlinks from host VM storage are masked back to `/storage/emulated/0/...`; the game process still remains on a pure black screen and `libcovault-appsec.so +0x20c58` still triggers `exit(0)`.
- Verify on Redmi/Android 12 at `192.168.64.105:5555` that the containerized Limbus process remains alive after patching `libcovault-appsec.so +0x20d40`, but the visible screen is still pure black; blocking `SIGXCPU` / `SIGPWR` is not viable because it triggers an il2cpp `abort()` during initialization.
- Verify `:app:testDebugUnitTest :app:assembleDebug` after routing Limbus PlayCore asset packs to local testing mode.
- Verify the rebuilt APK installs on Redmi/Android 12 at `192.168.64.105:5555`, launches Google Play Limbus v436 through `ShadowActivity$P0`, keeps `com.ProjectMoon.LimbusCompany` processes alive, uses `FakeAssetPackService` for `UnityDataAssetPack` / `UnityStreamingAssetsPack`, and no longer logs PlayCore `API_NOT_AVAILABLE(-5)`.
- Verify the rebuilt APK installs on Redmi/Android 12 at `192.168.39.124:5555`, launches Google Play Limbus v436 through `ShadowActivity$P0`, keeps the containerized game process alive beyond 45 seconds, and writes runtime files under the VirtualApp game data directory.
- Verify `:app:testDebugUnitTest :app:assembleDebug` after extending Limbus native terminating-signal guards and removing the host launcher fallback.
- Verify the rebuilt APK installs on Redmi/Android 12 at `192.168.39.124:5555`, syncs Google Play Limbus v436, loads AppSealing sealed dex, and still exits near Activity resume with `am_proc_died ... reason=2`.
- Verify `:app:testDebugUnitTest :app:assembleDebug` after adding native syscall scanning.
- Verify the rebuilt APK installs on Redmi/Android 13, syncs Google Play Limbus v436 with 3 split APKs, and launches far enough for `UnityPlayerActivity` to draw before AppSealing kills the `p0` process with `SIGKILL`.
- Verify current syscall scanning reaches the `p1` AppSealing service process, but is still too late to save the `p0` Unity Activity process.
- Verify the container now installs GMS, GSF, Play Store, and Play Games before game launch; remaining runtime blocker is PlayCore rejecting MuMu's Play Store certificate as non-official Phonesky.
- Verify the AppSealing diagnostics build with `:app:testDebugUnitTest :app:assembleDebug`; runtime log capture is pending because no adb device is currently attached.
- Verify `AppInstrumentation.newActivity()` receives the `ShadowActivity$P0` launch and can decode the target `StubActivityRecord`.
- Verify `com.unity3d.player.UnityPlayerActivity` is absent when AppSealing is bypassed, because the sealed dex payload is not mounted into the virtual classloader.
- Verify MuMu Android 12 rejects `System.setSecurityManager`, so Java-level `System.exit` trapping cannot preserve AppSealing side effects on this device.
- Verify `:app:testDebugUnitTest :app:assembleDebug` after the AppInstrumentation/AppSealing diagnostics.
- Verify `AppSealingApplication` was the cause of the virtual process `System.exit(0)` during `LoadedApk.makeApplication`.
- Verify bypassing `AppSealingApplication` lets virtual binding complete through `VClient.bindApplication done`.
- Verify redispatching from `ShadowActivity` back through `VActivityManager` still loops on MuMu/Android 12, so the remaining blocker is `HCallbackStub` transaction adaptation rather than AppSealing startup.
- Verify `:app:testDebugUnitTest :app:assembleDebug` with JDK 17 after the VirtualApp launch diagnostics and AppSealing bypass.
- Verify the previous stub Activity loop now advances into `VClient.bindApplication`.
- Identify the current MuMu blocker as a native SIGSEGV in `libv++_64.so` `measureNativeOffset()` / `hookAndroidVM()` under Houdini ARM64 translation.
- Verify `:app:testDebugUnitTest :app:assembleDebug` after removing the host launcher fallback.
- Verify the fixed debug APK installs successfully on `127.0.0.1:7555`.
- Verify the debug APK contains `lib/arm64-v8a/libv++_64.so`.
- Verify launching creates `com.example.limbuszhcn:p0` and reaches `ShadowActivity$P0`; current remaining blocker is stub Activity visibility/start loop before Unity logs appear.
- Verify `:app:testDebugUnitTest :app:assembleDebug` with the Android Studio JBR and local proxy settings.
- Verify the debug APK can sync installed Limbus Company `v424` into the VirtualApp package cache on the emulator.
- Verify launching reaches the VirtualApp virtual process before stopping on missing native `libv++_64.so`.
- Build, install, and launch the debug APK on the connected emulator at `127.0.0.1:7555`; confirm the installed Limbus package exposes `base.apk`, `split_UnityDataAssetPack.apk`, and `split_config.arm64_v8a.apk`.
- Build and install the debug APK to the connected emulator at `127.0.0.1:7555`.
- Confirm `EN_LoginUIText.json`, `EN_MainUIText.json`, and `EN_Bufs-a1c6p2.json` in the game `Localize/en/` directory contain Chinese text after installation.

### Documentation

- Document the OurPlay container/localization reference model: `gameplugins/<pkg>` storage, language `manifest.json`, target path types, and VM data-directory redirection.
- Switch the planned update path to Android-side `.7z` extraction first, with relay conversion as a fallback after validating a real upstream package.
- Clarify that upstream translation packages are only available as `.7z` and must be converted by the relay server for Android.
- Add translation package download planning based on the `zero-autoupdate` relay/update workflow.
- Plan the Android app architecture, module boundaries, data flow, and project conventions in `AGENTS.md`.
