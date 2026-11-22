# finderscope — notes for Claude Code

* Build/test: `./gradlew :core:check` (JVM, conformance suite, 95 % gate);
  `FINDERSCOPE_JVM_ONLY=1` skips the app. App: `./gradlew :app:assembleDebug`; the Espresso
  suite needs an emulator with KVM (CI runs it: `tools/ci-emulator-tests.sh`).
* Every module keeps 95 % line coverage. Camera behaviour belongs in `core/CameraSession` with a
  scenario in `ConformanceTest`; the app only renders session events.
* Clean room: never copy code, strings or artwork from the original camera apps, nor code, addresses, internal names or files from a camera firmware. New protocol or
  behaviour knowledge goes into docs/PROTOCOL.md / docs/BEHAVIOR.md first, in our own words.
* Any change in what goes over the wire needs the simulator (`RvfCameraSimulator`) to model it
  and a conformance scenario to assert it.
