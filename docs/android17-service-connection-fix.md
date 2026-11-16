# Android 17 service connection compatibility

The Samsung SM-S9180 diagnostic captured on 2026-11-16 reports Android 17 / API 37.
Both game launches fail in the framework `IServiceConnection.Stub.onTransact` with
`AbstractMethodError`: the device calls
`connected(ComponentName, IBinder, android.app.IBinderSession, boolean)`, while
`ServiceConnectionDelegate` only implements the older two/three-argument callbacks.
The archive was already cached; the Japanese source resources were not ready.

## Fix boundary

Inspect the runtime interface signature, rather than assuming a particular Android
version. For the session-aware signature, decode the connected Binder transaction
in the delegate and forward the original session Binder alongside the wrapped
service Binder and resolved component. Do not reference the hidden IBinderSession
type in the APK: older Android releases must remain loadable. Force this new
callback through Binder for local asInterface lookups as well. Preserve the
existing two/three-argument implementation on older frameworks.

This does not change game APKs, native hooks, accounts, downloaded game resources,
or the translation index. The later native crash in the diagnostic must be
rechecked after fixing the first fatal callback; a device run is still required.

## Distribution

The upstream signing private key is unavailable. A build with a new key cannot
replace an upstream installation. Publish an explicitly named, separately
installable fork (`com.example.limbuszhcn.android17`) so the original installation
and its data remain intact. Keep host provider authorities consistent with the
selected application ID, and retain the existing no-cleanup rule for both known
host IDs. Reuse the fork signing key for all future fork updates. Never publish
the private key or signing properties.

Build with the documented JBR and NDK, run the existing host tests and Binder
parcel regression tests, verify APK signature/manifest/ABI/ZIP alignment, and
record remaining device-validation limits in the Release notes.

```bash
./gradlew --console=plain --no-daemon --max-workers=2 \
  -PlimbusApplicationId=com.example.limbuszhcn.android17 \
  '-PlimbusAppLabel=邊獄漢化 Android 17 修復版' \
  :app:testDebugUnitTest :app:assembleDebug
```

The application self-update policy remains upstream's signed-update policy; obtain
this independently signed fork's subsequent APKs from its own GitHub Releases.

## Validation on 2026-11-16

- Full JBR 21.0.9 / Android SDK 35 / NDK 30.0.14904198 source build succeeded.
- 134 host tests: 131 passed, 3 existing skips, no failures or errors.
- All six new Robolectric API 35 tests passed: runtime signature selection,
  service/session Binder identity and component forwarding, null/disconnection
  callbacks, descriptor enforcement, unexpected trailing arguments, and rejected
  transactions. These exercise the new wire codec; they are not an Android 17
  device or game-login test.
- APK identity: `com.example.limbuszhcn.android17`, version 1.5.3 / code 9,
  non-debuggable, arm64-v8a. All 106 provider authorities use the fork package.
- APK v2 signature and 16 KiB ZIP alignment verified. All native ELF PT_LOAD
  segments have at least 16 KiB alignment. The test-only hidden interface is
  absent from production DEX type tables.
- Signing certificate SHA-256:
  `dcb7967fb77928513b42656b07611fec05d82cfe8255c57a1f9df16584429534`.
- No phone is connected to this build environment. Samsung/API 37 launch,
  login, resource download, and translation display remain to be verified.
