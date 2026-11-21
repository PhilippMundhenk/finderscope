# The Remote Viewfinder protocol (reverse engineered)

How the 2012–2014 Samsung Wi-Fi cameras talk to a phone in their *Remote Viewfinder* mode. The
camera is the access point and the server; the phone is the client. Everything here is our own
description, from published notes and from observing what the original apps sent; how those
apps behaved around it (timers, shutter semantics, what they did wrong) is in
[BEHAVIOR.md](BEHAVIOR.md). finderscope implements this in `core`: `RvfHttp`, `RvfClient`,
`AviFrameReader`, `SsdpNotices`, `CameraSession`.

Sources: published notes (below) and the behaviour of the camera maker's own Android apps,
*Remote Viewfinder* 2.2.11 for the 2012 models and *SMART CAMERA App* 1.4.0 for the later ones.
Those apps were examined for interoperability only, to learn what the cameras expect; no code,
text or artwork of theirs is part of finderscope.

The published notes:
[ge0rg/samsung-nx-hacks wiki](https://github.com/ge0rg/samsung-nx-hacks/wiki/Remote-Viewfinder)
(plus `xml/Remote-ViewFinder/smp_2_.xml` and `smp_3_.xml`, captured from an NX3000), the comments on
[op-co.de "Hacking the NX300"](https://op-co.de/blog/posts/hacking_the_nx300/) (NX300, NX300M,
NX3000, and NX1000 firmware 1.15), and
[RuiJCS/samsung-s150f-reverse-engineer](https://github.com/RuiJCS/samsung-s150f-reverse-engineer).

```
camera: Wi-Fi -> Remote Viewfinder   opens AP "AP_SSC_<MODEL>_0-xx:xx:xx" (NX1000 fw 1.10: no AP_SSC_
                                     prefix), is DHCP server + gateway 192.168.102.1 (.107 on some NX)
phone joins, then (one request per connection, HTTP/1.0, Connection: close, one at a time):
HEAD :7788/mode/control              optional knock (SMART CAMERA App, every model); MVERSION = newer model
GET  :7676/smp_2_   User-Agent: SEC_RVF_<id> (+ Access-Method: manual)   device description
                    first time: the camera asks "allow?"; 401 = refused, 503 = another phone is connected
GET  :7676/smp_3_   ContentDirectory SCPD: the actions this model offers       (no pairing header)
POST :7676/smp_4_   SOAPACTION: "urn:schemas-upnp-org:service:ContentDirectory:1#GetInfomation"
                    <- capabilities (Flash/Supports, ZoomInfo, AVAILSHOTS, StreamQuality, Version) and
                       StreamUrl/QualityHighUrl  http://<camera>:7679/livestream.avi (640x480)
                                 QualityLowUrl   http://<camera>:7679/qvga_livestream.avi
                       or, on the 2012 firmware, GETIPRESULT = the stream URL
GET  :7679/livestream.avi             <- 206, endless RIFF/AVI, frames in 00dc/00db chunks
first frame: MULTIAF, then ReleaseSelfTimer(RELEASETIMER=0)   (SMART CAMERA App; 2.2.11 sends nothing)
shutter:     MULTIAF (or AF)          <- AFSTATUS: AFFAIL/0 = no focus, else the AF frames ("1x3,")
             Shot                     <- AFSHOTRESULT = URL of the new JPEG, AVAILSHOTS; the stream ends
GET  :7679/livestream.avi             live view again (2.2.11 restarts it before fetching the copy)
GET  <AFSHOTRESULT>_SM                the small copy (no Version / Version < 1.0, e.g. NX1000)
GET  <AFSHOTRESULT>                   the original (Version >= 1.0)
POST /smp_4_ ZoomIN / ZoomOUT         <- CURRENTZOOM     only where the SCPD declares them
```

* **Identity.** The pairing User-Agent goes on the description request only. Both apps send nothing
  of the kind on the SCPD, the SOAP calls, the stream or the downloads; on the NX1000 a repeated
  pairing header is reported to bring the "allow?" prompt back.
  * It is `SEC_RVF_` plus a stable id: the 2012 app uses the phone number, else the Wi-Fi MAC
    without colons. The camera remembers ids it allowed.
  * This app uses a random locally administered MAC (`02:…`), generated once per installation.
  * NX3000 / NX mini want `SEC_RVF_ML_`. The client takes that when `/mode/control` answers with
    `MVERSION`, or after a 401 to the plain prefix.
* **Only declared actions, every declared argument.** The SCPD differs per model, and an
  undeclared action or argument hangs some cameras. `GetInfomation` with `GPSINFO`, for example,
  is a fault on the 2013 compacts but required on the NX3000. Samsung's UPnP stack sends every
  declared input argument and leaves unset ones empty; so does `RvfClient`.
  * The 2012 app connects only if the SCPD has GetInfomation, SetResolution, ZoomIN, ZoomOUT,
    MULTIAF, Shot, ShotWithGPS, SetLED and SetFlash. That is the NX1000 profile of the simulator.
  * The NX1000 / NX1100 declare zoom but zoom only with the lens ring; Samsung's apps hide zoom
    there, and so does finderscope.
* **Order and timing (NX1000).**
  * Strictly one command at a time.
  * Open the live stream right after `GetInfomation`: until it is read the camera's buttons are
    dead, and MULTIAF / Shot only work while it is being read.
  * The camera drops its Wi-Fi after some idle time.
* **Rotation.** GetInfomation `ROTATION` gives the camera's orientation; while connected the camera
  also multicasts SSDP NOTIFYs for its ConnectionManager with a non-standard `NTS:
  ssdp:rotationD|U|L|R` when it is turned.
* **Shutter.** MULTIAF (AF on the Galaxy cameras), then Shot. On NX models a failed focus
  (`AFFAIL`) cancels the shot: `ReleaseSelfTimer(0)` instead, like the camera itself does. UPnP
  error 803 on Shot means "capturing failed, try again"; `AVAILSHOTS` 0 means the card is full.
* **Live stream.**
  * The camera writes `0` for every RIFF/LIST length, so a reader must enter LISTs instead of
    skipping them and trust only leaf chunk sizes.
  * Stills mode is H.264 (Annex B, SPS/PPS in band, 29.97 fps from `strh` 1001/30000); movie mode
    on some models is MJPEG.
  * Only one reader at a time. The camera **ends the stream for every Shot**; Samsung's apps fetch
    the picture and then open the stream again, and so does finderscope (retrying for up to a minute,
    since a dropped stream can leave the port dead that long).
* **Pictures.** `Shot` stores the picture on the card as usual.
  * Where `Shot` declares `REQUESTIMAGE`, `1` asks the camera to offer it for download.
  * Cameras without `<Version>` ≥ 1.0 in GetInfomation, the NX1000 among them, only hand out
    `<AFSHOTRESULT>_SM`, a small copy; that is all Samsung's apps fetch from them. This explains
    the old report that "the download did not work" on the NX1000.
  * finderscope saves that copy as `<name>_preview.JPG`; the original stays on the card.
* **Keep-alive and end.**
  * Neither app sends HeartBeat or any periodic call, and neither says goodbye to these models:
    closing the stream (and leaving the Wi-Fi) ends the session.
  * `HeartBeat(Duration=30)` is sent only where the SCPD declares it (NX3000). GENA events
    (`/smp_5_`) are unreliable and not used.
* **Which Samsung app served which camera.**
  * The 2012 app pairs only with SSIDs **without** `AP_SSC_`, i.e. the old firmware naming (NX1000
    1.10). The SMART CAMERA App refuses SSIDs that *start with* a 2012 model name, so it takes
    `AP_SSC_NX1000_…` (firmware 1.15).
  * Neither app installs or runs on a current phone: the 2012 app targets API 8 and both bring
    32-bit-only video players.

Open: whether the 2012 compacts (WB150F, WB850F, ST200F, DV300F, EX2F) behave exactly like the
NX1000 (the 2012 app treats them the same), and the NX1000 firmware 1.10 stream format (the 2012
app also accepts `.mp4` stream URLs; finderscope reads AVI only).

## Finding the camera

The camera opens its network before its UPnP server listens: a connection to :7676 right after
joining can be refused for several seconds. The camera answers an SSDP M-SEARCH (`ST: ssdp:all`,
sent to 239.255.255.250:1900) with a `LOCATION` such as `http://192.168.102.1:7676/smp_2_` once
the server is up. finderscope first tries the gateway on :7676; while that is refused or times
out it sends a search every second and uses the first answer (or NOTIFY) that names a
MediaServer description, for up to 60 s.

## SSDP notices while connected

While a session runs, the camera multicasts SSDP NOTIFYs (239.255.255.250:1900) about its
ConnectionManager service, USN `<UDN of the description>::urn:schemas-upnp-org:service:ConnectionManager:1`:

* `NTS: ssdp:rotationD|R|U|L` when the camera is turned (D = normal landscape, U = upside down,
  L / R = portrait). The same orientation is in GetInfomation `ROTATION` at the start: most
  models count 1..4 = D, L, U, R, the WB850F counts 0..3 = D, R, U, L.
* `NTS: ssdp:byebye` when the camera leaves Remote Viewfinder mode.

finderscope listens for these on the camera network (with a multicast lock) and ignores notices
whose USN is not the connected camera's.

## AF frames

MULTIAF answers `AFSTATUS` with the frames that are in focus, as `row x column` tokens
(`"1x3,2x3,"`), or `AFFAIL`. The compacts have a 3×3 grid; the 2012 NX models a 3×5 grid whose
column numbers are not left to right: the original app placed camera columns 3, 0, 1, 2, 4 from
left to right (unconfirmed on hardware).
