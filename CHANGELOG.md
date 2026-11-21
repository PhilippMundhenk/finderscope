# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Remembers the camera network: next time **Connect to <model>** asks Android for exactly that
  network, which Android approves without its dialog; **Other camera** for a different one. Needs
  the location permission once (Android only tells apps a Wi-Fi name with it).

### Fixed
- Connecting right after joining the camera network failed with "connection refused" because the
  camera's viewfinder service starts a few seconds after its Wi-Fi. finderscope now waits for it
  (*Waiting for the camera…*), retrying and searching by SSDP for up to a minute.
- After a picture the NX1000 live view stalled again and again: the camera keeps the stream open
  but silent while it shows the picture, and finderscope dropped it after 5 s. It now keeps the
  connection for up to 20 s after a picture, waits longer with each stall in a row, and logs how
  much a stalled connection delivered.

## [0.1.1] - 2026-11-21

### Changed
- The live view adapts to the window: top bar, picture and controls stacked in tall windows,
  settings and shutter in columns beside the picture in wide ones (landscape, split screen),
  the controls over the picture's edges only in nearly square windows. Zoom and notices no
  longer cover the picture.

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
