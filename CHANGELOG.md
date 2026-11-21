# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
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
