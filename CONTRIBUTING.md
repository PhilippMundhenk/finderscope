# Contributing

Bug reports, real-camera reports (with the log from the app's *Log* screen), fixes and small
focused features are welcome. Work is tracked in
[GitHub issues](https://github.com/PhilippMundhenk/finderscope/issues); open one before a larger change.

## Setup and checks

See docs/DEVELOPMENT.md. JDK 17+, Android SDK for the app.

    ./gradlew :core:check                                   # protocol, session, conformance + gate
    ./gradlew :app:connectedDebugAndroidTest :app:jacocoAndroidTestReport :app:jacocoAndroidTestCoverageVerification

Every module must stay at or above 95 % line coverage; add tests with the code, not after.

## Clean room

Do not contribute code, text or images taken from the original camera apps. Describe what a
camera does in docs/PROTOCOL.md or docs/BEHAVIOR.md and implement from that.

## Pull requests

Branch off `main`, open a PR; CI runs the tests, the gates and the APK build. Commit subjects
reference the issue (`... (#12)`), bodies say what and why.

## Releases

Add the notable changes to `CHANGELOG.md`, date the section, then
`git tag vX.Y.Z && git push --tags`: CI attaches the APK to a GitHub release.
