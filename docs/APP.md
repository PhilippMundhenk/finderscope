# Using finderscope

## Connecting

1. On the camera: Wi-Fi → *Remote Viewfinder* (on NX models the Wi-Fi menu, on the compacts the
   Wi-Fi mode dial or button). The camera shows its network name, `AP_SSC_<model>_…` on most
   models, `<model>_…` on the 2012 firmware.
   Its viewfinder service is ready about 5 seconds after it shows its **Cancel** button;
   finderscope waits for that by itself.
2. In finderscope: **Join camera Wi-Fi**. Android lists the camera networks nearby; pick yours.
   Android connects the phone to it for finderscope only: other apps keep using mobile data, and
   nothing in your saved Wi-Fi networks changes.
3. The camera asks whether to allow this phone. Confirm on the camera. The NX1000 (and probably
   the other 2012 models) asks again every time Remote Viewfinder is started; the original app
   showed a "confirm on the camera" message for every connection too.

Right after the camera opens its network, its viewfinder service is often not up yet. finderscope
shows *Waiting for the camera…* and keeps trying (and searches for the camera by SSDP) for up to a
minute; **Cancel** stops.

**The same camera next time.** finderscope remembers the camera network it joined last (it asks
once for the location permission, which Android requires to read a Wi-Fi network's name). The
button then reads **Connect to <model>** and asks Android for exactly that network, which Android
approves without its dialog after the first time. **Other camera** shows the list again.

The order does not matter: tap **Join** first and switch Remote Viewfinder on afterwards, or the
other way round. finderscope looks for the camera's network in the phone's Wi-Fi scan for up to
3 minutes and joins it as soon as it appears (*Looking for the camera's Wi-Fi…*).

If Android's list stays empty although the camera shows its network, finderscope offers **Join
in Wi-Fi settings** after the list is closed: pick the camera's network there and come back,
finderscope connects by itself. (It also looks into the phone's own Wi-Fi scan first and asks for
a camera network it sees there by name; the Log says what the scan contained.)

Already on the camera's Wi-Fi (joined in the phone's settings)? Tap **Connect**: the camera
address is filled in from the Wi-Fi (usually `192.168.102.1`). Change it only if your camera
uses another one.

## Taking pictures

* **Shutter:** hold to focus. The focus frames that are sharp light up green; a red centre frame
  means the camera could not focus. Release to take the picture. Slide off the button before
  releasing to cancel. A short tap focuses and shoots in one go.
* **Volume keys:** work like the shutter button (press = focus, release = picture).
* **Tap to focus** (*Settings → Shutter*): instead of holding the shutter, tap the live view to
  focus; the focus is kept until the picture (tap again to focus anew). The shutter and the
  volume keys then take the picture with that focus, or focus and shoot in one go when nothing
  was focused.
* **Could not focus:** on the NX models no picture is taken, like on the camera itself; the
  compacts take it anyway.
* **Self-timer:** *Timer off → 2 s → 10 s*. The countdown shows on the screen; tap the shutter
  again to stop it.
* **Flash** and **photo size:** the choices the camera offers. The remaining shots show at the
  top left.
* **Zoom** (compacts): *W* / *T*, hold to keep zooming. The NX models zoom with the lens ring.

After each picture the live view pauses for a moment (the camera needs it to save the picture)
and comes back on its own. The last picture shows bottom left; tap it to open it.

## Where pictures go

The camera keeps every picture on its card as usual. finderscope also copies it into
*Pictures/finderscope*, unchanged (camera EXIF included). The 2012 models (NX1000, NX20, NX210,
WB150F, WB850F, ST200F, DV300F, EX2F) hand over only a small preview; it is saved as
`SAM_xxxx_preview.JPG`, and the full picture stays on the card. Switch copying off in *Settings*.

*Tag pictures with the phone's location* (Settings) writes the phone's position into the EXIF of
the copy on the phone. The file on the camera's card stays without it: the cameras accept a
position with the picture command but do not use it. It needs the location permission and is off
by default.

The live view is what the camera sends: low resolution at about 15 frames a second (NX1000).
There is no setting for a better one, and the camera's other settings (exposure, ISO, white
balance, ...) cannot be changed over Wi-Fi: Remote Viewfinder only offers focus, shutter, timer,
flash, photo size and zoom.

## Good to know

* Leaving the app ends the session (the camera's screen comes back); returning resumes it.
* Only one phone can be connected at a time.
* Switching to another app keeps the connection (and the camera's live view) for 5 minutes by
  default; *Settings → Stay connected in the background* sets 0 to 30 minutes. The picture is
  back as soon as finderscope is on screen again. After that time finderscope disconnects and
  reconnects on return (the camera may have left Remote Viewfinder by then).
* Turning the camera (portrait, upside down) turns the live view on the phone; the camera
  announces it over the network.
* After each picture the camera pauses the live view while it shows the picture. Set the camera's
  *Quick View* to off or a short time for a quick return; with *hold* the live view waits for the
  camera.
* A lost live view reconnects on its own. If the camera is switched off or leaves Remote
  Viewfinder mode, finderscope says so and goes back to the start screen.
* *Log* (menu) shows every request and answer: the first place to look when a camera behaves
  differently. Please attach it to a [GitHub issue](https://github.com/PhilippMundhenk/finderscope/issues).
* **Try the demo** runs a simulated camera on the phone, for a look around without a camera.
