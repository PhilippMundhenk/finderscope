# finderscope — design notes

Status date: 2026-11-21.

## Goal

A modern, stable, open-source Android client for the Remote Viewfinder mode of the 2012–2014
Samsung Wi-Fi cameras, built from what the cameras expect (PROTOCOL.md) and what the original
apps did (BEHAVIOR.md), without their code. First target: NX1000.

## Architecture

```
core/  (Kotlin, JVM, zero dependencies; tested on the JVM)
  RvfHttp          raw HTTP/1.0 client: one request per connection, exact Content-Length, the
                   endless 206 live stream; pluggable SocketFactory (camera network on Android)
  RvfClient        protocol: /mode/control knock, pairing User-Agent on the description only,
                   SCPD-gated SOAP with every declared argument, GetInfomation, focus, shot
                   (and with GPS), timer LED, flash, photo size, zoom, HeartBeat, stream, download
  AviFrameReader   incremental RIFF/AVI reader for the endless stream (zero container lengths)
  CameraSession    all behaviour: connect, live view with stall recovery and reopen after every
                   picture, press/release/tap shutter, self-timer countdown, settings, notices;
                   one command thread, focus/shot held until the live view is back
  CameraModel      per-model rules (AF grid, cancel on failed focus, zoom), Rotation, gpsInfo,
                   megapixels
  SsdpNotices      the camera's SSDP NOTIFYs while connected (rotation, goodbye)
  RvfCameraSimulator  a camera in Remote Viewfinder mode (NX1000, WB250F, NX3000, DEMO profiles),
                   as strict as the real ones; records every rule a client breaks
  HttpServer, Upnp small helpers for the simulator
app/   (Android 10+, Views + Material 3, ViewBinding)
  MainActivity     connect panel (camera Wi-Fi, address, demo) and the viewfinder (live view,
                   AF overlay, countdown, info, notices, shutter, volume keys, flash, timer,
                   size, zoom with repeat); resumes the session after the screen comes back
  CameraWifi       WifiNetworkSpecifier request for the camera network, gateway, socket
                   factory and datagram binding on that network
  H264Decoder      MediaCodec on the SurfaceView; MJPEG frames go to an ImageView
  AfOverlayView    the camera's AF grid over the picture
  DemoCamera       the simulator on loopback with drawn frames (demo mode, screenshots)
  Pictures         MediaStore Pictures/finderscope, bytes unchanged
  Prefs, LogBus, LogActivity, SettingsActivity
```

## Design decisions

* **Behaviour in core, UI in app.** Every rule the cameras impose lives in `CameraSession` and is
  tested on the JVM against the simulator (`ConformanceTest`), fast and deterministic; the app
  only renders events. The Espresso suite then checks the screens on a device.
* **One command at a time, held while the live view is down.** The NX1000 hangs on overlapping
  or out-of-order commands and only focuses / shoots while its stream is read. The original
  apps did not enforce this; finderscope cannot get it wrong.
* **Recover instead of quitting.** Stalls, dropped streams and failed commands are reported and
  recovered from; only a camera that is gone ends the session.
* **The camera network without touching the user's Wi-Fi.** `WifiNetworkSpecifier` plus binding
  only finderscope's sockets; the original deleted saved networks and toggled radios.
* **Pairing id:** a random locally administered MAC per installation; Android hides the real
  MAC, and a constant one would make every phone the same to the camera.
* **Start-up focus only for the 2013+ firmware** (stream URL in `StreamUrl`): their app did it,
  the 2012 app did not.
* **Pictures byte for byte** (EXIF kept), the full original where the camera offers it, the
  `_SM` preview where it does not (2012 firmware).
* **Demo mode = the test simulator**, so every screenshot and every UI test runs against the
  same camera model.

## Open questions

* Real-camera validation (NX1000 first): pairing prompt, stream format, `_SM` download, the 3×5
  AF column order, rotation notices.
* Whether the 2012 compacts need the timer LED set again after each picture.
