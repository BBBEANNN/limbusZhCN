# VirtualApp Migration Notes

## Current Decision

Use `ServenScorpion/VirtualApp` as the first real container engine candidate.

- Repository: `https://github.com/ServenScorpion/VirtualApp.git`
- Pinned revision: `ae7c4275096b6614fa1aa7befe326630fd3f8833`
- Rationale: this branch still contains the full `lib` source, AIDL, virtual storage service, and Android 12-oriented hooks.

`FBlackBox/BlackBox` is not the first target because the current `master` branch only contains repository metadata and README-level content.

## Why This Is Not A Normal Dependency

The VirtualApp `lib` module is a full Android virtualization runtime, not a small SDK:

- 661 Java files
- 80+ AIDL files
- Native `jni/` code using `ndkBuild`
- Android Support dependencies
- Legacy Gradle scripts
- Additional `com.xdja.*` extension services and callbacks
- A large manifest with many permissions intended for a generic multi-app host

It cannot be safely added to the current AGP 8 app as a one-line dependency.

## Migration Steps

1. Vendor the pinned upstream lib:

   ```bash
   bash scripts/vendor_virtualapp.sh
   ```

2. Create a local Gradle module for the vendored `lib`.
3. Replace upstream package/authority placeholders with this app's package namespace.
4. Strip nonessential permissions from the merged manifest.
5. Stub or remove unused `com.xdja.*` extensions that are unrelated to Limbus.
6. Build the VirtualApp lib with current AGP/compileSdk.
7. Wire `VirtualAppContainerRuntime` to the real classes instead of reflection-only probing.
8. Implement split APK import for:
   - `base.apk`
   - `split_UnityDataAssetPack.apk`
   - `split_config.arm64_v8a.apk`
9. Start Limbus inside the container and wait for the game to download `Localize/`.
10. Probe and patch the real virtual storage path.

## Known Open Risk

VirtualApp has `VirtualStorageManager` and `VEnvironment.getExternalStorageAppDataDir(userId, packageName)`, which should map to:

```text
virtual/storage/emulated/0/Android/data/com.ProjectMoon.LimbusCompany/
```

This still needs device verification after the runtime is actually packaged.
