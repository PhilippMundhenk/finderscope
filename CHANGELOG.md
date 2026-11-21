# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- *Settings → Shutter → Tap to focus*: a tap on the live view focuses and keeps the focus (a
  second tap focuses anew), the shutter and the volume keys take the picture. Hold to focus
  (press, let go) stays the default.

## [0.1.10] - 2026-11-21

### Fixed
- Live view delayed by 0.5 to 1 s and jerky (field log: 15 frames/s from the camera, "decoder
  553 ms"): the camera's H.264 header does not say that frames are never reordered, so phone
  decoders kept a whole buffer of frames before showing any. finderscope now adds that to the
  header before decoding (and switches on the chip makers' low-latency modes).
- Switching the camera off while connected was not noticed; finderscope kept trying to reopen
  the live view. Losing the camera's Wi-Fi now ends the session with a clear message.

## [0.1.9] - 2026-11-21

### Fixed
- Android's network picker could keep searching without ever listing the camera network
  (field test with 0.1.8). finderscope now looks into the phone's own Wi-Fi scan first and asks
  for a camera network seen there by name, and logs what the scan contains. When Android gives
  up, **Join in Wi-Fi settings** opens the phone's Wi-Fi panel; back in finderscope it connects
  on its own if the phone is on a camera network.

## [0.1.8] - 2026-11-21

### Changed
- Less delay in the live view: the decoder runs in low-latency mode and each picture is shown
  the moment it is decoded (before, a decoded frame could wait for the next one to arrive). The
  log reports frames per second from the camera, frames shown, and the decoder's delay every
  10 seconds.

## [0.1.7] - 2026-11-21

### Added
- *Settings → Stay connected in the background*: how long the camera stays connected while
  finderscope is not on screen, 0 to 30 minutes (5 by default; 0.1.6 kept it for 10).

### Fixed
- *Tag pictures with the phone's location* did not tag anything: the cameras accept the position
  with the picture command but ignore it (NX1000 firmware). The position is now written into the
  EXIF of the copy on the phone.

## [0.1.6] - 2026-11-21

### Changed
- The live view sits centred between the top bar and the controls instead of right under the bar.
- Switching to another app no longer disconnects the camera ("network disconnected" on the
  camera): the connection is kept for up to 10 minutes, only the picture is not drawn.
- Start screen: wait until the camera shows its **Cancel** button, then 5 more seconds, before
  connecting.

### Fixed
- Turning the camera to portrait did not turn the picture: the NX1000 sends its turn notices
  under a fixed ID (seen in its firmware) that need not match its description, and finderscope
  dropped notices that did not match. Notices
  are now taken from the camera's address (and logged).

## [0.1.5] - 2026-11-21

Still debug-signed: uninstall the previous version before installing.

### Fixed
- After a picture the NX1000 live view froze (grey shutter, green focus frame) until the camera
  dropped the connection: the camera sends a new AVI header in the middle of the stream after
  each picture, and finderscope read it as one huge chunk. It now takes it as a restart of the
  stream, and finds the next frame whenever the stream stops making sense.
- The first connection after starting Remote Viewfinder failed with "HTTP 404": the camera's web
  server answers 404 for a moment while it starts. That is now "not ready yet", like a refused
  connection. Attempts are at least 1.5 s apart (0.1.4 could try dozens of times a second).
- On the wrong network the search took other devices (a NAS) for the camera; only the camera's
  own address is accepted now.
- Black bar or cut-off live view: the NX1000 stream header always says 320x240; the picture is
  now laid out with the size the decoder actually produces. A decoder error restarts the decoder
  at the next key frame instead of stopping the picture.

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
