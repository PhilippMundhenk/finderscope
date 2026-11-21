# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed
- Updates install over the previous version again: releases are signed with one fixed release
  key. 0.1.0 to 0.1.4 had a different throwaway key each, so the first signed release needs one
  uninstall of the old version.

## [0.1.4] - 2026-11-21

### Fixed
- Frames still buffered from before a picture made 0.1.3 bring the shutter back too early and
  show "reconnected" over the "saved" message. Found by the UI tests on the release build.

## [0.1.3] - 2026-11-21

Tagged but not published: replaced by 0.1.4.

### Changed
- While joining the camera Wi-Fi or connecting, the join button is **Cancel** and the other
  start-screen buttons are disabled, so a slow join cannot be started twice.

### Fixed
- After the first picture a real NX1000 showed a spinner and hung until the battery was pulled.
  The likely cause is finderscope's live-view reopens while the camera was still showing the
  picture. finderscope now leaves the stream alone for 30 s after a picture, and after four
  silent reopens in a row it ends the session with a message instead of asking the camera again.
- The end of a session that was already replaced no longer shows the start screen over the new one.

## [0.1.2] - 2026-11-21

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
