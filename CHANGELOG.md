# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-11-21

First release. Complete against the simulated camera (conformance suite, UI tests on an
emulator); not yet tried on a real camera (#4, #5).

### Added
- Camera-style design: full-screen live view with the controls floating over the picture, a
  round shutter button, round last-picture thumbnail, shots-left counter, flash / timer / size
  chips with icons, zoom pill; a dark start screen with one large *Join camera Wi-Fi* button.
- Live view of the camera (H.264 through MediaCodec, MJPEG), shutter with the camera's press /
  release / slide-off semantics, volume keys as shutter, focus frames, self-timer, flash, photo
  size, zoom with repeat, remaining shots, rotation.
- Joins the camera network through Android's network request; nothing in the saved networks is
  changed, mobile data stays on for the rest of the phone.
- Recovers from a stalled or dropped live view and from failed commands; reopens the live view
  after every picture.
- Pictures copied into Pictures/finderscope byte for byte; optional GPS tag.
- Demo mode with a built-in simulated camera; log screen.
- core: protocol client, session engine, camera simulator, conformance tests against the
  original app's behaviour (docs/BEHAVIOR.md).
