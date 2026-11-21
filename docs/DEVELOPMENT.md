# Development

```
core/   pure Kotlin/JVM, zero dependencies: protocol, session engine, simulator
app/    Android app (Android 10+)
tools/  ci-emulator-tests.sh (Espresso suite on the CI emulator)
docs/   PROTOCOL.md, BEHAVIOR.md, DESIGN.md, APP.md, BRANDING.md
```

Requires JDK 17+. For the Android module, `local.properties` needs `sdk.dir=…` or `ANDROID_HOME`
must be set; `FINDERSCOPE_JVM_ONLY=1` skips the app module. Work is tracked in
[GitHub issues](https://github.com/PhilippMundhenk/finderscope/issues).

## Build and test

Every module has a **95 % line-coverage gate** (JaCoCo); `check` fails below it.

* `./gradlew :core:check` — protocol tests, the simulator, and `ConformanceTest`: the scenarios of
  [BEHAVIOR.md](BEHAVIOR.md), each driving `CameraSession` against the simulated camera and
  checking the wire traffic (focus before shot, nothing overlapping, stream reopened after every
  picture, failures recovered, ...). Then the gate.
* `./gradlew :app:assembleDebug` — APK.
* `./gradlew :app:connectedDebugAndroidTest :app:jacocoAndroidTestCoverageVerification` — the
  Espresso suite on a device or emulator against the simulator on loopback: demo flow with every
  control, H.264 from the device's encoder decoded by MediaCodec, rotation, focus failure,
  preview copy, card full, resume after leaving the screen, settings, log. Then the gate.

Measured on 2026-11-21: core 98.7 %, app 97.2 % lines (the CI log prints the per-file numbers
and uncovered lines: `tools/coverage-summary.py`).

## CI and releases

`.github/workflows/ci.yml`: core tests with the gate, debug + release APK, the Espresso suite
with its gate on an emulator (API 34). On `main` the emulator job also publishes the
documentation screenshots (`ScreenshotTest`, demo camera) on the `screenshots` branch; copy
them into `docs/screenshots/` when the UI changed. Tags `vX.Y.Z` publish a GitHub release with the APK
(`git tag v0.1.0 && git push --tags`; date the CHANGELOG section first). The release APK is
signed with the repository secrets `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` when they exist, otherwise debug-signed.

## Clean room

finderscope must stay free of the original apps' code, text and artwork. Protocol knowledge goes
into PROTOCOL.md / BEHAVIOR.md in our own words first; code is written from those documents.
