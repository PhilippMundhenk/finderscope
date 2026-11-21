# How the original app behaved, and what finderscope does instead

finderscope replaces the discontinued "Remote Viewfinder" app that the camera maker shipped for
its 2012 Wi-Fi cameras (and the later companion app for the 2013/2014 models). It is a
clean-room reimplementation: the original apps were studied only to learn the protocol and the
behaviour a camera expects. No code, text or artwork of theirs is part of this project. This
page describes that behaviour in our own words, decides what finderscope keeps, and lists the
conformance scenarios the tests check. The wire protocol itself is in [PROTOCOL.md](PROTOCOL.md).

## Features of the original, and ours

| Feature | Original | finderscope |
|---|---|---|
| Joining the camera | Scans Wi-Fi itself, auto-joins one camera network, a picker for several; deletes the user's saved camera networks and toggles Wi-Fi / mobile data on exit | Asks Android for the camera network (system dialog, nothing deleted or toggled), keeps mobile data for the rest of the phone, binds only its own sockets to the camera |
| Finding the camera | SSDP search every second; starts on the next ConnectionManager packet (stalls if the packets come in the "wrong" order) | Asks the Wi-Fi gateway directly, retries while the camera starts up and then searches by SSDP (any answer naming the description, in any order); remembers the last camera network and asks for exactly it next time |
| Which cameras | 2012 networks only (no `AP_SSC_` prefix); later models sent to another app | Every model that speaks the protocol, 2012 to 2014; behaviour per model from the camera's own action list |
| Live view | Native player, fixed screen boxes, stretched | MediaCodec (H.264) or JPEG frames, true aspect ratio, rotation applied |
| Shutter | Press = focus, release = picture; drag off the button = cancel; 2 s debounce | Same press / release / drag-off semantics, plus a tap = focus and shoot, plus the volume keys |
| Focus frames | Grid of frames from the camera's answer (3×3 compacts, 3×5 NX) | Same grid, drawn over the true picture area |
| Failed focus | NX: no picture; compacts: shoot anyway | NX: no picture, as on the camera; compacts: shoot anyway (the camera's own behaviour); both shown clearly |
| Self-timer | Off / 2 s / 10 s, counted down in the app; *off* actually sent "2" (bug) | Off / 2 s / 10 s with the right values; cancel by tapping again |
| Flash | Off / auto by menu position | The camera's own list, sent as the camera expects |
| Photo size | List from the camera; current size assumed to be the first; wrong labels for unknown sizes | List from the camera, megapixels computed from the size |
| Zoom | Compacts only, one step per tap | Compacts only, step per tap, repeats while held |
| GPS | Silent, wrong hemisphere letters south / west | Opt-in, correct N/S/E/W |
| After a picture | Live view restarted, small preview shown; saved only on request, re-encoded, EXIF lost | Live view restarted, preview shown; optional automatic save of the camera's own bytes (EXIF kept), full size where the camera offers it |
| Card full / numbering exhausted | Message, shutter off; at connect the app quits | Message, shutter off, session stays (view still works) |
| Errors | Every failed camera command ends the app; no reconnect anywhere | A failed command is reported and can be retried; a lost live view or Wi-Fi blip reconnects on its own |
| Stalled live view | Noticed after 10–20 s, app quits | Noticed after a few seconds (longer after a picture, and longer with each stall in a row), live view reopened; the log says how many bytes came on the stalled connection |
| Leaving the app | Session keeps running in the background until an idle timer (10 min, silent) | Connection kept while away (the camera leaves Remote Viewfinder when it ends, and closing the live view at the wrong moment can hang an NX1000); the picture is not drawn meanwhile; ended after a set time away (5 minutes by default, 0 to 30 in Settings) and resumed on return |
| Phone battery | Quits at 7 % | Not the app's business |
| Without a camera | Nothing | Demo mode with a built-in simulated camera |

## Connection and session (original)

1. Joins the camera's own Wi-Fi (the camera is the access point and the gateway).
2. Searches with SSDP; fetches the description named in the answer, sending the pairing
   identifier only on that request. The camera asks its user "allow this device?" the first
   time.
3. Accepts the camera only if it is a MediaServer and its action list has the nine actions the
   app needs (information, photo size, zoom in/out, focus, shot, shot with GPS, timer LED,
   flash).
4. Sends GetInfomation, then opens the live stream named in it. Nothing else is sent until the
   user acts. Timers: no session within 3 minutes = failure; a command unanswered for 30 s
   ends the session.
5. Ends on: the camera's SSDP goodbye, Wi-Fi loss, a stalled live view, 10 minutes without a
   touch, the back key (with confirmation).

## Shutter, focus and capture (original)

* Press: one focus command. Its answer is either a failure marker or a list of the AF frames
  that are in focus ("row x column"), drawn on a 3×3 grid (compacts) or a 3×5 grid (NX20,
  NX210, NX1000). On the 3×5 grid the camera's column numbers are not left to right: the
  original placed them in the order 3, 0, 1, 2, 4 (unconfirmed, see the open questions).
* Release: once the focus answer is in, either the self-timer countdown (2 or 10 s, kept in the
  app) or the shot right away. Release outside the button, or tapping during the countdown,
  cancels and sends the "release self-timer" command.
* Shot: the live view is stopped, the shot command sent (or the GPS variant), the answer names
  the new picture on the card and the remaining shots. The live view is opened again, then the
  small copy (`<picture>_SM`) is fetched and shown. An empty picture name left the original
  stuck with no live view.
* On a real NX1000 the live-view connection is not ended by the shot: the camera stops its
  encoder, takes the picture, starts the encoder again and only then answers Shot; the open
  connection then carries a complete new AVI header followed by frames (see PROTOCOL.md). 0.1.x
  read that header as one enormous chunk and showed nothing more (field test: frozen picture,
  grey shutter). finderscope reads a new header as a restart of the stream, searches for the
  next frame when it ever loses the chunk structure, keeps the connection, and brings the
  shutter back with the first new frame.
* Closing or reopening the stream while the camera takes the picture hangs the NX1000 (seen
  with 0.1.1, explained by the firmware). finderscope never touches the stream during a Shot
  and leaves it alone for 30 s afterwards; after four silent reopens in a row it gives up. The
  simulator treats a stream opened or closed during the picture as a hang and stops answering.
* The camera asks the user to allow the phone at every start of Remote Viewfinder; it keeps the
  "allowed" state only while the mode runs.
* Remaining shots 0 = card full, -1 = folder / file numbering exhausted.

## Rotation (original)

The camera's orientation comes from GetInfomation (`ROTATION`; the WB850 numbers it differently)
and, while connected, from SSDP notices about its ConnectionManager carrying
`NTS: ssdp:rotationD|U|L|R`. The live view and the preview are turned accordingly.

## Conformance scenarios

The simulator (`core`, `RvfCameraSimulator`) behaves like the cameras above and records every
request; the scenario tests (`core/src/test/.../ConformanceTest.kt`) drive finderscope's session
engine through each scenario and compare what goes over the wire with what the original sent,
except where this page says finderscope deliberately differs.

| # | Scenario | Wire behaviour expected (as the original) | finderscope difference |
|---|---|---|---|
| 1 | First connect | description (pairing id, once), action list, GetInfomation without arguments, live stream; nothing else until the user acts | none (no start-up focus for 2012 cameras) |
| 2 | SSDP answers in odd order | original may wait for the next notice | first answer naming the description wins |
| 3 | Action list incomplete / not a MediaServer | never connects | connects, offers what the camera has |
| 5 | Focus ok, no timer | focus on press, shot only after the focus answer, then stream again, then `_SM` | same |
| 6 | Focus fails on an NX | release self-timer, no shot | same |
| 7 | Focus fails on a compact | shot anyway | same |
| 8 | Drag off the shutter | release self-timer, no shot | same |
| 9 | Timer 2 s / 10 s | timer LED set when chosen, shot 2 / 10 s after release, cancel = release self-timer | same |
| 10 | Timer off | sends "2" (bug) | sends "0" |
| 11 | Flash | flash mode command | camera's own values |
| 12 | Photo size | resolution "W×H", remaining shots updated | same |
| 13 | Card full / numbering exhausted | message; quits if full at connect | message, session stays |
| 14 | Zoom | one step per tap, none at the ends, none on NX | same, plus repeat while held |
| 15 | GPS | shot with GPS, N/E always | opt-in, correct letters |
| 16 | Shot fails (UPnP 803) | session ends | message, retry possible |
| 17 | Empty picture name | stuck | live view back, message |
| 18 | Command unanswered 30 s | session ends | session ends with a clear message |
| 19 | Live view stalls | quits after 10–20 s | reopens after a few seconds |
| 20 | Camera says goodbye (SSDP) | session ends | same, with a message |
| 23 | Rotation notice | view turned | same |
| 27 | Command during focus | sent at once (overlap; can hang the camera) | queued: never two commands at once |

Scenarios 4 (rejected network names), 21/22 (lease expiry, Wi-Fi drop), 24 to 26 (idle exit,
back key, playback screen) concern the original's own UI and Wi-Fi handling and have no wire
counterpart worth reproducing.

## Open questions

* The 3×5 column order on the NX models (3, 0, 1, 2, 4) needs a real NX1000 to confirm.
* Whether the 2012 compacts need the self-timer LED set again after each picture (the original
  did not).
