# LiquidFrame

[English](README.md) | [简体中文](README_CN.md)

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://www.android.com/)
[![LSPosed](https://img.shields.io/badge/LSPosed-Module-6F42C1)](https://github.com/LSPosed/LSPosed)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/mouya-q/LiquidFrame/build.yml?label=Build)](../../actions)

An LSPosed module that replaces the background of Xiaomi Camera's photo watermark with an
Apple-style **Liquid Glass** material, plus a companion HyperOS (miuix) app for designing and
tuning that material.

> This is an independent project. It is not affiliated with, endorsed by, or supported by
> Xiaomi, Leica, or Apple.

---

## Table of contents

- [What it does](#what-it-does)
- [Screenshots](#screenshots)
- [Repository layout](#repository-layout)
- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [The material](#the-material)
  - [How it renders](#how-it-renders)
  - [Why a companion app exists](#why-a-companion-app-exists)
- [How the hook works](#how-the-hook-works)
- [Building](#building)
- [Troubleshooting](#troubleshooting)
- [Limitations and known issues](#limitations-and-known-issues)
- [Credits and licensing](#credits-and-licensing)

---

## What it does

Xiaomi Camera creates a watermark in its photo-output pipeline. LiquidFrame snapshots clean pixels
from the YUV-to-Bitmap source, records the exact Bitmap returned by Xiaomi's watermark compositor,
and replaces only that composed object immediately before frame encoding. It restores the source
pixels under the stock plate and draws one glass capsule; the camera's separate original-photo
branch remains untouched.

"Liquid Glass" here means a surface that does more than blur:

| Property | What it contributes |
| --- | --- |
| **Refraction (lens)** | The backdrop is sampled at an offset that grows toward the silhouette, so the panel bends and magnifies the scene behind it instead of just fogging it. |
| **Chromatic dispersion** | The refraction splits into a seven-tap spectrum, which is antisymmetric across the diagonals — a prism on opposite edges rather than a uniform colour fringe. |
| **Vibrancy** | A saturation lift on the captured backdrop. Real glass concentrates colour; without it the material reads as grey fog next to saturated content. |
| **Specular rim** | A hemispheric normal along the rounded edge, lit from two directions, giving two opposing highlights that rotate with the light. |
| **Inner shadow** | A soft edge-anchoring shade, so the panel reads as a solid object rather than a sticker. |

The primary reverse-engineering target is Xiaomi 17 Camera `6.7.000480.0`, with a legacy 6.4 fallback and conservative DEX structural discovery for other builds; see
[Limitations](#limitations-and-known-issues) for the exact supported path and open gaps.

## Screenshots

The companion app renders the material over a deliberately hostile backdrop — saturated colour
fields with hard edges and fine grain — so the lens, dispersion and rim are judged against content
that has structure to bend. The images below are off-device material demonstrations comparing the
baseline, frosting and refraction. They are not camera captures from the new watermark hook.

| Baseline (panel as drawn) | Frosted (blur + tint, no lens) | Liquid glass (adds lens) |
| --- | --- | --- |
| ![baseline](docs/01-watermark-only-crop.png) | ![frosted](docs/02-frosted-only-crop.png) | ![lens](docs/03-liquid-glass-crop.png) |

High-dispersion close-up — the spectral split is antisymmetric across the diagonals, so it reads
as a prism on opposite edges rather than a uniform colour fringe:

![dispersion](docs/04-high-dispersion-crop.png)

> These are renders from the material's own optics, generated off-device by
> `docs/RenderSamples.kt` over a synthetic scene (`./gradlew`-independent, run with the standalone
> Kotlin compiler — see `docs/renders.txt`). They are the reference the implementation is checked
> against, not device captures.

## Repository layout

```
app/                    LSPosed module (the watermark hook)
  src/main/java/com/mouya/LiquidFrame/
    WatermarkHooks.kt     hook installation and the composite pipeline
    DexKitHelper.kt       DEX discovery, reflection verification and late retry
    ConfigStore.kt        settings shared between the module and the camera process
    GlassConfig.kt        the camera-side view of those settings
    ConfigActivity.kt     settings UI: Compose + iOS-style glass, live preview
    dex/DexScanner.kt     lightweight DEX reader for capture records, receivers and frame adapters
    glass/                the optics engine (no Android dependency)
      LiquidGlassOptics.kt  CPU port of the material
      GlassParams.kt        every quantity in the material
      PanelScan.kt          locating the watermark panel from pixels
gallery/                HyperOS (miuix) app: the material as live components
  src/main/java/com/mouya/LiquidFrame/gallery/
    GlassNavigationBar / LiquidBackdrop / GalleryApp
    glass/                the real-time (GPU) material
      Lens.kt               AGSL rounded-rect refraction lens
      Glass.kt              GlassSpec and Modifier.liquidGlass
    screens/              material, controls and overlays pages
docs/                   reference renders
```

## Requirements

**Module (`app/`)**

- Android 8.0+ (API 26). The material runs on the CPU over integer pixel buffers, so it has no
  `RuntimeShader` floor; the settings UI is Jetpack Compose with a hand-drawn glass surface for the
  same reason.
- Xiaomi Camera as `com.android.camera` or `com.miui.camera`.
- LSPosed (or another Xposed API 82+ compatible framework).
- Root, and a writable `/data/local/tmp` — settings and logs live there.

**Companion app (`gallery/`)**

- Android 13+ (API 33). `miuix-blur` hard-codes that floor.
- No root and no LSPosed required; it is an ordinary app.

## Installation

1. Build or download `app-debug.apk`, and install it.
2. Enable the module in LSPosed and set its **scope to the installed Xiaomi Camera package** (`com.android.camera` or `com.miui.camera`).
3. Force-stop Xiaomi Camera, then reopen it.
4. Take a photo with a watermark enabled.

Check the module log for the `capture context`, `YUV source adapter`, `watermark factory`, and
`final capture encoder` hooks. A successful shot should log
`final capture encoder: replaced composed stock layer with glass`. If the structure cannot be
verified, the camera's original result is kept. Missing metadata fields are intentionally omitted;
they are not filled with guessed values.

## Configuration

Open the module from the launcher. Settings are stored in the app-private files directory
and mirrored to `/data/local/tmp/lf_config.txt` for the camera process. The shared-file write may
need root authorization; the settings page reports whether synchronization actually succeeded. A
successful sync applies to the next capture without restarting the camera.

The settings screen renders a **live preview** with the same optics and capsule proportions used
for capture. Settings are grouped into collapsible sections (Core, Rendering, Capture Info, Brand,
Layout, and Maintenance); logo and position choices use native Compose drop-down menus rather than
always-visible option rows. The controls are:

| Setting | Effect |
| --- | --- |
| Enable | Master switch. |
| Refraction band height | Thickness of the refracting band along the silhouette. Too small reads as a hairline; too large warps the whole panel like a fisheye. |
| Refraction strength | Peak displacement at the silhouette. Useful range is roughly 1–3× the band height. |
| Blur | Frosting of the captured backdrop. Keep it low: heavy blur destroys the detail the lens is supposed to bend. |
| Interior lift | Additive brightness across the panel. This is the readability knob for the new capsule text. |
| Tint | Strength of the tint painted over the refracted backdrop. |
| Rim highlight | Strength of the specular edge. |
| Inner shadow | Edge-anchoring shade. |
| Highlight angle | Direction of the rim light, in degrees. |
| Depth effect | Leans the surface normal toward the centre so the glass reads as a dome. |
| Chromatic aberration | Strength of the spectral split; `0` disables it. |
| Adaptive | Scales tint, veil and rim against the scene's mean luminance. |
| Capture metadata | Rebuilds the capsule from real capture fields; missing fields are omitted. |

"Reset to defaults" restores the shipped values; "Copy log" puts the last run's diagnostics on the
clipboard.

## The material

### How it renders

The optics are a port of the reference renderer used by the miuix ecosystem
(Apache-2.0; attribution and upstream link are recorded in the `NOTICE` file). The same
mathematics exists in two backends, because the two places it must run have different constraints:

- **`app/glass/LiquidGlassOptics.kt`** — a CPU implementation on integer pixel buffers. The
  hook applies it to a cropped region of the verified clean source frame immediately before the
  camera's final Bitmap-to-frame conversion. This file has **no Android dependency at all**,
  which is what makes the optics testable off-device.
- **`gallery/glass/Lens.kt`** — an AGSL `RuntimeShader`, applied as a live `RenderEffect` chain
  through miuix's backdrop engine. This is the real-time path used by the companion app.

Both keep the reference's own mathematics rather than an approximation of it:

- the signed distance field and its gradient are the reference's `sdRoundedRect` /
  `gradSdRoundedRect`;
- the displacement profile is `circleMap(1 - -sd / refractionHeight)` — the sagitta of a unit
  circle, not a linear ramp — multiplied by a negative amount so the backdrop is pulled inward and
  the panel magnifies what is behind it;
- the surface normal is `normalize(gradSdRoundedRect(c, halfSize, min(1.5r, minHalf)) + depth * normalize(c))`.
  The `1.5 ×` factor is the reference's own: the upstream commit that introduced it exists solely
  to match Apple's render;
- dispersion is the reference's seven-tap spectrum with its exact channel divisors (`1/3.5`
  dominant, `1/3.0` for pure red and blue, `1/7.0` cross-talk);
- the effect order is the documented one: **colour filter → blur → lens**.

Two things the reference expresses as *drawing operations* are modelled analytically, because a
shader has no stroke or blur-mask to lean on:

- the **rim highlight** is the reference's blurred white stroke, combined with the directional
  weighting of its `Default` highlight shader;
- the **inner shadow** is its recorded layer — the shape filled, the offset shape cleared, then
  blurred.

Default proportions are calibrated against the reference project's own Apple comparison, in which
a 300 px glass uses a 20 px band with a 60 px displacement.

### Why a companion app exists

The material's real-time form cannot be injected into Xiaomi Camera. The inspected camera DEX is a **classic View application with no Compose runtime**: its string
table contains no `androidx/compose` references (only `Lmiux/...` View classes). Hosting a
Compose UI inside the camera process would mean shipping the entire Compose runtime into it.

So the project is split: the module applies the material on the CPU to the source photo Bitmap
inside the camera's capture pipeline, and the companion app hosts it in real time over miuix
components — including a floating bottom navigation bar whose rim visibly bends the content
drifting past behind it.

The companion app builds on [`miuix`](https://github.com/compose-miuix-ui/miuix) (Xiaomi's
open-source design system, Apache-2.0). `miuix-blur` supplies the backdrop engine — graphics-layer
capture, the blur cascade, and the `runtimeShaderEffect` extension point — as well as
`buildBloomStrokeShader`, which is a specular rim model of exactly the kind this material needs.
What miuix deliberately leaves to the application is the **lens**, which is what `gallery/glass/`
provides.

## How the hook works

The primary implementation target is **Xiaomi 17 Camera `6.7.000480.0`**. The supplied archive
contains its disassembly index and class/method text, not the original APK binary. The module keeps
a legacy 6.4 fallback and uses structural DEX discovery for other installed camera builds.

### Real capture metadata and final composition

The 6.7 still-photo entry point is `W8/d.f(Ki/a, boolean, int) -> Ki/f`. Its typed record carries
35mm-equivalent focal length (`S`), aperture (`F`), exposure nanoseconds (`J`), ISO (`I`), capture
time (`J`) and `Location`. The matching `Ki/b` diagnostic names this focal value
`mFocalLength35mm`; the capsule's `100mm` is therefore a 35mm-equivalent value, not physical lens
focal length. Values are range-checked and omitted when absent.

The capture path first converts YUV to a clean Bitmap through
`com.xiaomi.libyuv.YuvUtils.I420ToBitmap`, may scale that source, then calls
`com.xiaomi.cam.watermark.a.c(...)` which delegates to the original watermark composition in
`com.xiaomi.cam.watermark.b.c(...)`. The returned Bitmap is the **stock-composited photo**. It is
then passed to `Mi/b.c(I, Bitmap)`, `Mi/b.b(Bitmap)` or `Mi/b.a(Bitmap)` for `Ki/f` frame creation.

LiquidFrame snapshots the clean source (full-frame up to 64 MiB, otherwise a bounded bottom recovery
strip) and records the factory's exact returned Bitmap identity. Immediately before frame creation,
it patches only that same object: restore the source pixels, render the glass capsule once, then let
the camera encode it. The separate untouched-original branch is excluded by identity. The factory's
selection-preview path remains separately rendered, so the UI preview and final capture share the
same capsule renderer without drawing the capture capsule too early.

### Cross-version discovery and boundaries

The DEX scanner recognizes capture records by the `S,F,J,I` field run followed by timestamp `J` and
`Location`, records the actual capture argument index, and finds the final Bitmap-to-frame-wrapper
adapters using their field/constructor shape and relationship to the capture record and Xiaomi
watermark types. Reflection revalidates every chosen method. It scans installed base/split APKs and
retries after the background scan completes. Unknown structures fail open and retain the stock photo.

The structural approach broadens compatibility; it does not prove every camera version or format.
HEIF/MIVI3, RAW, Live Photo and direct byte-stream paths still require per-build verification.

## Building

Requirements: JDK 21 and an Android SDK with platform `android-37` installed.

```bash
./gradlew assembleDebug
```

Artifacts:

```
app/build/outputs/apk/debug/app-debug.apk          # the LSPosed module
gallery/build/outputs/apk/debug/gallery-debug.apk  # the companion app
```

Toolchain: Android Gradle Plugin **9.4.1**, Kotlin **2.4.20**, Gradle **9.8.0**. The
`miuix 0.9.4 / Compose 1.12` dependency chain refuses to be consumed by older tooling (their AAR
metadata rejects it), which is why these floors are where they are. AGP 9 supplies Kotlin itself,
so `org.jetbrains.kotlin.android` is deliberately not applied; the Compose compiler is applied
separately via `org.jetbrains.kotlin.plugin.compose`.

CI builds and verifies debug APKs for both `:app` and `:gallery`. Release signing is opt-in; see
[`SIGNING_SETUP.md`](SIGNING_SETUP.md).

## Troubleshooting

**The original watermark is still present.** Check for `YUV source adapter hooked`,
`watermark factory hooked`, and `final capture encoder hooked`. A successful shot should log
`replaced composed stock layer with glass`. If the final encoder is not reached, the mode may use
another image branch; the module intentionally leaves the stock photo unchanged.

**The YUV source adapter is missing.** Some camera builds may rename or relocate `YuvUtils`. The
factory input is used as a fallback source, but replacement is skipped if source dimensions or
Bitmap identity cannot be verified. Include the complete module log and camera version when
reporting a miss.

**A metadata field is missing.** This is deliberate fail-closed behaviour: a field is omitted unless
it came from the current capture record. Compare the capsule with the saved photo's EXIF and note the
capture mode, file format (JPEG/HEIF), and camera version.

**The photo looks wrong or capture slows down.** Turn off the module master switch to restore the
stock camera path, then report the original photo, module log, and camera version. Use the original
photo file rather than a screenshot for pixel-level diagnosis.

## Limitations and known issues

- **Primary reverse-engineering target:** Xiaomi 17 Camera `6.7.000480.0`. The `W8/d.f` capture
  receiver, `Ki/a` metadata record, `YuvUtils` clean-frame source and `Mi/b` frame adapters are
  identified from the supplied DEX disassembly. A literal 6.4 receiver fallback and structural
  matching broaden the compatibility surface, but an unseen camera build still needs verification.
- **Bitmap watermark path first.** HEIF/MIVI3, RAW, Live Photo, and any route that bypasses the
  current Bitmap-to-frame conversion are not yet verified.
- **No Android build or Xiaomi 17 device test was possible in this environment.** The Android SDK
  is absent and the Gradle Wrapper download failed because network access was unavailable. This
  source package must be built locally or in CI and then tested on the actual camera build.
- **The original watermark is replaced as a whole.** Its text and logo are not preserved; the new
  capsule displays fields available in the current capture record.
- **Some capture fields may be unavailable.** Camera2 capabilities, shooting mode, or Xiaomi's
  internal adapter may omit a CaptureResult field. The module omits it instead of inventing a value.
- **Root and LSPosed are required.** The modification operates inside the camera process.

## Credits and licensing

- All third-party attributions, licenses and upstream links are collected in the `NOTICE` file.
  The liquid glass optics are adapted from an Apache-2.0 reference renderer (see `NOTICE`). The
  reference's shader source, constants and the `1.5 ×` gradient factor are used as-is; the CPU
  port and the analytic rim/inner-shadow models are this project's.
- The companion app is built on
  [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) (Apache-2.0), Xiaomi's
  design system. Its blur engine supplies the backdrop capture and the specular rim model.
- The `lens()` and `vibrancy()` effects adapted into `gallery/glass/` follow miuix's own example
  implementations (`example/shared/.../component/liquid/`), which are themselves Apache-2.0.

All adapted files point at the `NOTICE` file, which carries the full upstream attributions
and licenses. This project is licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE).