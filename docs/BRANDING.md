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

The viewfinder is always dark (it shows a picture); the start screen and settings follow the
system theme.

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

Black brand bar · start-screen card (status, hint, *Join camera Wi-Fi* filled, address, *Connect*
outlined) · viewfinder with the picture centred at its true aspect ratio · info chip top left,
notice bar at the bottom (scrim) · control bar: tonal round buttons on black, the orange shutter
button in the middle · focus frames as outlines, green / red.

## 5. Icon

`docs/assets/icon.svg`: four white viewfinder corner brackets around an orange reticle (circle,
four ticks, centre dot) on a rounded dark square. Android: adaptive icon,
`drawable/ic_launcher_foreground.xml` (same shapes in the 72 dp safe zone) on `#1A1A1A`.

## 6. Tone

Short sentences, no exclamation marks, errors say what to do next, camera menu paths in italics
(*Wi-Fi → Remote Viewfinder*).
