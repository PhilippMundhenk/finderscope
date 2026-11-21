# finderscope — branding guide

One product, one surface: the Android app (plus README and GitHub). Code references:
`app/src/main/res/values/colors.xml`, `values-night/colors.xml`, `values/themes.xml`.

> finderscope is an independent open-source project, not affiliated with Samsung; names are
> used only to describe compatibility.

## 1. Name

| Where | Name |
|---|---|
| Product (titles, docs) | **finderscope** (always lower case, also at the start of a sentence in UI labels; in prose sentences start with it as is) |
| Code name | `finderscope` — package `org.mundhenk.finderscope`, `Theme.Finderscope` |
| Repository | `PhilippMundhenk/finderscope` |

A finderscope is the small scope on a telescope you aim with: you look through it to frame the
target, then take the picture with the big instrument.

## 2. Colours

The viewfinder and the start screen are always dark, like a camera's own screens (`start_bg`
`#0E0E10`, `start_muted` `#9A9AA0`); settings and the log follow the system theme.

| Token | Light | Dark | Used for |
|---|---|---|---|
| `primary` | `#C2410C` | `#FFA26B` | buttons, links on the start screen |
| `on_primary` | `#FFFFFF` | `#4A1503` | text on primary |
| `primary_container` | `#FFE3D3` | `#6B2A0B` | notices |
| `accent` | `#E8590C` | same | shutter button, icon reticle |
| `surface` / `surface_container` | `#FFFFFF` / `#F7F7F7` | `#121212` / `#1E1E1E` | start screen, cards |
| `on_surface` / `on_surface_variant` | `#1A1A1A` / `#666666` | `#EEEEEE` / `#AAAAAA` | text |
| `outline` | `#E3E3E3` | `#333333` | borders |
| `finder_bg` / `finder_fg` / `finder_scrim` | `#000000` / `#FFFFFF` / `#99000000` | same | viewfinder, toolbar, overlays |
| `af_ok` / `af_fail` | `#4ADE80` / `#F87171` | same | focus frames |
| `ok` / `error` | `#2E7D32` / `#C62828` | `#81C995` / `#F28B82` | states |

## 3. Typography

System font, Material 3 scale. The countdown is 96 sp bold, the shutter label titleMedium.

## 4. Components

* **Viewfinder** (live): the camera's picture full-bleed at its true aspect ratio on black,
  system bars hidden (a swipe brings them back). Nothing but the picture and the controls.
* **Top bar** over a black-to-clear gradient (`scrim_top`): close (×), camera name, then chips
  with an icon and a short label: flash (bolt; crossed out for *off*; the camera's word for the
  mode in capitals), self-timer (clock; `2s` / `10s`, dimmed when off), photo size (`20M`),
  settings (gear).
* **Bottom bar** over a clear-to-black gradient (`scrim_bottom`): the last picture as a 56 dp
  circle with a white 2 dp edge (left), the **shutter** (centre), shots left as a large number
  with "left" under it (right).
* **Shutter** (`ShutterView`): 84 dp, white 4 dp ring, `accent` disc inset 9 dp; pressed
  (focusing): disc inset 14 dp in `primary_pressed`; not ready: ring and disc translucent grey.
* **Zoom pill** (compacts): `W` · position in `accent` · `T` on a translucent pill above the shutter.
* **Focus frames**: outlines, `af_ok` green where in focus, `af_fail` red centre frame.
* **Countdown**: 120 sp bold white with a soft shadow, centred. **Notices**: a translucent pill
  above the controls, 4 s.
* **Start screen**: dark, the icon (96 dp) and the name large, tagline, status and hint, one
  60 dp pill button *Join camera Wi-Fi* in `accent` with the Wi-Fi icon, *Connect by address*
  (reveals the address field and *Connect*), *Try the demo* in `accent`, the disclaimer small at
  the bottom.
* Icons are drawn for finderscope (`drawable/ic_*.xml`): bolt, bolt off, timer, close, Wi-Fi.

## 5. Icon

`docs/assets/icon.svg`: four white viewfinder corner brackets around an orange reticle (circle,
four ticks, centre dot) on a rounded dark square. Android: adaptive icon,
`drawable/ic_launcher_foreground.xml` (same shapes in the 72 dp safe zone) on `#1A1A1A`.

## 6. Tone

Short sentences, no exclamation marks, errors say what to do next, camera menu paths in italics
(*Wi-Fi → Remote Viewfinder*).
