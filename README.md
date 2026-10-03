# LiquidFrame

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

Xiaomi Camera composites a watermark into every photo: a background panel with the device model,
the date, and often a Leica or brand mark. LiquidFrame intercepts the finished bitmap after that
composite and rebuilds **only the background panel** as a refracting glass surface — the camera's
own typography, logo and metadata are left exactly as it drew them.

"Liquid Glass" here means a surface that does more than blur:

| Property | What it contributes |
| --- | --- |
| **Refraction (lens)** | The backdrop is sampled at an offset that grows toward the silhouette, so the panel bends and magnifies the scene behind it instead of just fogging it. |
| **Chromatic dispersion** | The refraction splits into a seven-tap spectrum, which is antisymmetric across the diagonals — a prism on opposite edges rather than a uniform colour fringe. |
| **Vibrancy** | A saturation lift on the captured backdrop. Real glass concentrates colour; without it the material reads as grey fog next to saturated content. |
| **Specular rim** | A hemispheric normal along the rounded edge, lit from two directions, giving two opposing highlights that rotate with the light. |
| **Inner shadow** | A soft edge-anchoring shade, so the panel reads as a solid object rather than a sticker. |

The module works on the two Xiaomi Camera builds it was reverse-engineered against; see
[Limitations](#limitations-and-known-issues) for what that does and does not promise.

## Screenshots

The companion app renders the material over a deliberately hostile backdrop — saturated colour
fields with hard edges and fine grain — so the lens, dispersion and rim are judged against content
that has structure to bend. The watermark below is composited exactly as Xiaomi Camera produces it
(the panel is a pre-baked image the camera draws, with its labels on top), and the material is
applied to that finished bitmap by the same optics the module runs.

| Baseline (panel as drawn) | Frosted (blur + tint, no lens) | Liquid glass (adds lens) |
| --- | --- | --- |
| ![baseline](01-watermark-only-crop.png) | ![frosted](02-frosted-only-crop.png) | ![lens](03-liquid-glass-crop.png) |

High-dispersion close-up — the spectral split is antisymmetric across the diagonals, so it reads
as a prism on opposite edges rather than a uniform colour fringe:

![dispersion](04-high-dispersion-crop.png)

> These are renders from the material's own optics, generated off-device by
> `docs/RenderSamples.kt` over a synthetic scene (`./gradlew`-independent, run with the standalone
> Kotlin compiler — see `docs/renders.txt`). They are the reference the implementation is checked
> against, not device captures.

## Repository layout

```
app/                    LSPosed module (the watermark hook)
  src/main/java/com/mouya/LiquidFrame/
    WatermarkHooks.kt     hook installation and the composite pipeline
    ConfigStore.kt        settings shared between the module and the camera process
    GlassConfig.kt        the camera-side view of those settings
    ConfigActivity.kt     settings UI with a live preview
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

- Android 13+ (API 33) for the material itself. The watermark pipeline is API 26+, but the
  refraction needs `RuntimeShader`; below API 33 the module leaves the watermark untouched.
- Xiaomi Camera as `com.android.camera` or `com.miui.camera`.
- LSPosed (or another Xposed API 82+ compatible framework).
- Root, and a writable `/data/local/tmp` — settings and logs live there.

**Companion app (`gallery/`)**

- Android 13+ (API 33). `miuix-blur` hard-codes that floor.
- No root and no LSPosed required; it is an ordinary app.

## Installation

1. Build or download `app-debug.apk`, and install it.
2. Enable the module in LSPosed and set its **scope to Xiaomi Camera only** (`com.android.camera`).
3. Force-stop Xiaomi Camera, then reopen it.
4. Take a photo with a watermark enabled.

To confirm it ran, open the module's settings app: its log shows the detected panel geometry and
the material's parameter values for the last capture.

## Configuration

Open the module from the launcher. Settings are written to `/data/local/tmp/lf_config.txt`, which
both the settings app and the camera process read, so a change applies to the next capture without
restarting anything.

The settings screen renders a **live preview** with the same optics the module uses, so the
material can be judged and tuned without taking a photo. The controls are:

| Setting | Effect |
| --- | --- |
| Enable | Master switch. |
| Refraction band height | Thickness of the refracting band along the silhouette. Too small reads as a hairline; too large warps the whole panel like a fisheye. |
| Refraction strength | Peak displacement at the silhouette. Useful range is roughly 1–3× the band height. |
| Blur | Frosting of the captured backdrop. Keep it low: heavy blur destroys the detail the lens is supposed to bend. |
| Interior lift | Additive brightness across the panel. This is the readability knob for the camera's own labels. |
| Tint | Strength of the tint painted over the refracted backdrop. |
| Rim highlight | Strength of the specular edge. |
| Inner shadow | Edge-anchoring shade. |
| Highlight angle | Direction of the rim light, in degrees. |
| Depth effect | Leans the surface normal toward the centre so the glass reads as a dome. |
| Chromatic aberration | Strength of the spectral split; `0` disables it. |
| Adaptive | Scales tint, veil and rim against the scene's mean luminance. |
| Preserve content | Keep the camera's own labels on top of the material. |

"Reset to defaults" restores the shipped values; "Copy log" puts the last run's diagnostics on the
clipboard.

## The material

### How it renders

The optics are a port of the reference renderer used by the miuix ecosystem
([Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass), Apache-2.0). The same
mathematics exists in two backends, because the two places it must run have different constraints:

- **`app/glass/LiquidGlassOptics.kt`** — a CPU implementation on integer pixel buffers. Xiaomi
  Camera composites a watermark while producing a JPEG: there is no hardware-accelerated canvas
  and no live view hierarchy to sample, so the material is applied to the finished bitmap. This
  file has **no Android dependency at all**, which is what makes the optics testable off-device.
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

The material's real-time form cannot be injected into Xiaomi Camera. Both reverse-engineered
camera builds are **classic View applications with no Compose runtime**: a scan of their DEX
string tables finds zero `androidx/compose` references (only `Lmiux/...` View classes). Hosting a
Compose UI inside them would mean shipping the entire Compose runtime into the camera process.

So the project is split: the module applies the material on the CPU to the finished bitmap, and
the companion app hosts it in real time over miuix components — including a floating bottom
navigation bar whose rim visibly bends the content drifting past behind it.

The companion app builds on [`miuix`](https://github.com/compose-miuix-ui/miuix) (Xiaomi's
open-source design system, Apache-2.0). `miuix-blur` supplies the backdrop engine — graphics-layer
capture, the blur cascade, and the `runtimeShaderEffect` extension point — as well as
`buildBloomStrokeShader`, which is a specular rim model of exactly the kind this material needs.
What miuix deliberately leaves to the application is the **lens**, which is what `gallery/glass/`
provides.

## How the hook works

Everything below was established by disassembling the target APKs. The pipeline, for
`com.android.camera` `1+6.4.000250.2` (the HyperOS 4 port for the Xiaomi 10) and cross-checked
against `6.4.000370.0` (Xiaomi 13):

```
LE5/b->h(...)                          app side "processWatermark": lifts the I420 frame
                                       into a full-size photo Bitmap
watermark/b->b(Application, Bitmap, Dc/b, I) -> Bitmap
watermark/b->c(b, Context, Bitmap, Dc/b, I, String, I) -> Bitmap
watermark/c->c(Context, Bitmap, Dc/b, I, Cc/a, String, Z,
               PorterDuff$Mode, String, o9/O) -> Bitmap
Fe/a->j(Fe/a, Bitmap src, ColorSpace, I, I, String, I) -> Bitmap     <-- the composite
```

`Fe/a->j` creates **one** output bitmap, blits the photo into it, and draws the watermark element
tree over it in painter order. Two consequences drive the design:

1. The output bitmap is the **full photo with the watermark already composited**, so the pixels
   behind the panel are the scene itself. The glass refracts real photo content without the module
   ever needing to reach for the photo bitmap separately.
2. There is **no canvas-level scale factor**. dp values in the per-style `config.json` are
   multiplied by `min(photoW, photoH) / 1080` once at layout time, after which every element is
   already in absolute output pixels.

The module therefore post-processes the finished composite. Three hooks are installed:

| Hook | Purpose |
| --- | --- |
| `pe.o-><init>(Bitmap)` | Called once per render by `Fe/a->j` with the bitmap everything is drawn into. Captures the live destination. |
| `pe.o->h(FFFF, Paint)` | The element tree's `Canvas.drawRect`, i.e. the background panel. Only two callers exist in the whole app, both background draws; the flat canvas clear is a separate `Canvas.drawColor` and never reaches here. |
| `Fe/a->j` | The composite. Its returned bitmap is post-processed in place. |

Two details are easy to get wrong and are handled explicitly:

- **The rectangle passed to `pe.o->h` is element-local — always `(0, 0, w, h)`.** Absolute
  placement lives in `Canvas.translate` calls made by the parent group, so the rectangle is mapped
  through the canvas's own matrix before use.
- **Background panels are pre-rendered WebPs**, not drawn primitives
  (`assets/watermarks/<style>/<id>/icon_background_{light,dark}_blur.webp`). The corner radius is
  baked into the WebP's alpha plus `rect_params.rect_radius` in the style's `config.json`, so the
  module **measures** the radius from the panel's own silhouette rather than assuming it.

Because obfuscated names can change between camera builds, the panel is also recoverable from the
pixels alone: `PanelScan` searches for the camera's opaque background band and measures its bounds
and corner radius. If a future build renames the hooked classes, the module still finds the panel.

The hook targets are currently discovered with [DexKit](https://github.com/LuckyPray/DexKit) at
runtime, following the same approach [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler)
uses for its camera hooks. Rather than hardcoding `Fe.a` and `pe.o`, the module queries by
signature: the canvas wrapper class by its `Bitmap` constructor, the composite method by its
`Bitmap` return type and parameter count, and the `drawRect` wrapper by its `void` return type and
5-parameter shape. HyperCeiler's own note on the matter is that every major camera version
invalidates hardcoded signatures, which is exactly the fragility this removes. If DexKit cannot
initialize or find a target, the module falls back to the known obfuscated names from the
reverse-engineered builds. See `hyperceiler-findings.md` for the analysis and the equivalent
queries.

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
so `org.jetbrains.kotlin.android` is deliberately not applied.

`gradle.properties` contains HTTP proxy settings for a development machine behind a transparent
proxy. Delete the `systemProp.*proxyHost` block on a host with direct network access.

## Troubleshooting

**Nothing changed in the photo.** Check the module's log (open its settings app). Likely causes, in
order:

- The module is not scoped to `com.android.camera`. LSPosed scoping is the most common cause.
- Xiaomi Camera was not force-stopped after enabling the module.
- The log says `skip: no watermark panel found`. This build's watermark layout is not one the
  scanner recognises — please open an issue with the log and the watermark style you used.
- The watermark style you selected uses an opaque background rather than a translucent one. The
  module replaces the camera's background panel; styles that draw no panel have nothing to replace.

**The panel turns grey or loses its texture.** The material is being applied to a panel the camera
baked at full opacity in a way the classifier did not expect. Note the style in an issue.

**The labels disappeared.** Turn on "Preserve content". The module only keeps labels it can
identify as content, which requires the panel background to be reasonably flat; a photographic
panel has no flat tone to distinguish labels from.

**The gallery app shows no glass.** It requires API 33+ for `RuntimeShader`. Below that it draws
without the material by design rather than crashing.

## Limitations and known issues

- **Hook targets are discovered at runtime with [DexKit](https://github.com/LuckyPray/DexKit)**, not
  hardcoded. The module queries for the canvas wrapper class (by its `Bitmap` constructor), the
  composite method (by its `Bitmap` return type and parameter count), and the `drawRect` wrapper
  (by its `void` return type and 5-parameter signature). This is the same approach
  [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) uses for its camera hooks, and the
  reason it is worth adopting is their own comment in `UnlockLeica.kt`: *"跨一个大版本就需要改一下特征点"*
  (every major version needs its signature points changed). If DexKit fails to initialize or find
  a target, the module falls back to the known obfuscated names from the reverse-engineered builds.
  See `hyperceiler-findings.md` for the full analysis.
- **Not yet verified on a physical device end to end.** The optics are verified off-device by
  rendering to images and inspecting them; the hook's runtime behaviour needs log confirmation on
  the target phone.
- **The companion app's default material values are a starting point.** They were calibrated
  against the reference's Apple comparison, but the right values depend on the host palette and
  wallpaper — use the in-app tuner.
- **Overlay surfaces in the companion app are not glass.** Dialogs and bottom sheets dim or cover
  the backdrop, so the material would have nothing to refract. This is deliberate, and documented
  in-app.
- **Only the background panel is replaced.** The camera's typography and logos are preserved by
  design; this is not a watermark editor.
- **Root and LSPosed are required.** There is no non-root path to modify another app's bitmap.

## Credits and licensing

- The liquid glass optics are adapted from
  [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Apache-2.0). The
  reference's shader source, constants and the `1.5 ×` gradient factor are used as-is; the CPU
  port and the analytic rim/inner-shadow models are this project's.
- The companion app is built on
  [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) (Apache-2.0), Xiaomi's
  design system. Its blur engine supplies the backdrop capture and the specular rim model.
- The `lens()` and `vibrancy()` effects adapted into `gallery/glass/` follow miuix's own example
  implementations (`example/shared/.../component/liquid/`), which are themselves Apache-2.0.

All adapted files carry their upstream attribution in a header comment.

This project is licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE).
