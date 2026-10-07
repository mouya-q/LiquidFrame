# Changelog

## 1.3.0 — MeloX UI migration & glass fixes

### UI — MeloX component migration

- **Switched the entire config UI to MeloX-style components** (ported from lladlam/MeloX-Android,
  GPL-3.0): capsule shapes from `com.kyant.shapes:Capsule`, continuous corners from
  `com.kyant.capsule:ContinuousRoundedRectangle`, and MeloX's colour/shape tokens.
- **New glass toggle**: a 64×28dp capsule track with a 40×24dp thumb that slides with a spring
  animation. Uses MeloX's accent colours (green `0xFF34C759` / `0xFF30D158`) without requiring
  the kyant0 backdrop RuntimeShader (which needs API 33+), keeping minSdk 26.
- Cards and settings sections now use `ContinuousRoundedRectangle(22.dp)` / `(16.dp)` instead of
  plain `RoundedCornerShape`.
- Deleted the old `IOSComponents.kt`; all references in `ConfigActivity` now point to
  `MeloXComponents.kt`.
- Added kyant0 dependency stack: `io.github.kyant0:backdrop:2.0.0` + `shapes:1.2.0` + `capsule:2.1.3`.
- Raised `compileSdk` from 35 to 37 to satisfy kyant0 AAR metadata.

### Glass — grey-bottom fix

- **Reduced the stacked white veil** that read as a grey slab on the lower half of the capsule:
  `tintAlpha` 0.10 → 0.08, `surfaceAlpha` 0.06 → 0.04, `interiorLift` 10 → 6. The capsule no
  longer has a pale band opposite the rim highlight.
- **Removed the downward text-shadow smear**: `setShadowLayer` dy was `+0.02H`, which dragged a
  pale smear into the capsule's lower half. Changed to `dy=0` so the white glow stays centred on
  the glyph.

### Watermark text

- **Now prefers `ro.product.marketname`** (the human-readable marketing name like "15 Ultra")
  over `Build.MODEL` (the internal device code like "25010PN30C"), so the preview badge matches
  what the camera actually prints.
- Added `getSystemProperty` helper using reflection on `android.os.SystemProperties`.

### Preview geometry

- **Fixed capsule overflow**: the preview container now uses `aspectRatio(900/420)` instead of a
  fixed `height(190.dp)`, so the capsule never clips outside the preview frame regardless of
  screen width.

## 1.2.0 — UI / Runtime polish

This pass focuses on turning LiquidFrame from a tuning prototype into a small, system-like product.

### Material

- **The white base under the glass is gone.** The camera bakes an opaque background sheet into its
  watermark panel, and the optics were refracting *that sheet* instead of the photograph — which is
  why the panel read as a flat pale wash. The panel interior is now re-sourced from the real
  photograph directly above it before the lens runs, so the glass magnifies the scene.
- Corner radius now comes from the capsule geometry (full radius, height / 2). Previously a
  silhouette measurement ran on the finished JPEG, could never find a corner in fully opaque pixels,
  and silently fell back to 0.3 × short side — rounding the capsule off.
- Preview and capture now share the same geometry rules.

### Watermark text

- The preview no longer hardcodes a device name. The label is resolved at runtime from the installed
  camera package and the device itself, following the same principle as HyperCeiler's watermark rule
  (locate the camera's own watermark provider instead of embedding a string). Nothing is hardcoded.
- Removed the synthetic fallback scene's baked panel and fake label; the fallback is now a plain
  photographic gradient so the glass always samples real content.

### UI

- Rebuilt the module settings screen around a clear hierarchy: preview → core switch → display → material → detail → maintenance.
- Reduced the use of glass surfaces to the preview itself; settings groups are now quiet, flat and iOS-inspired.
- Added a concise enabled-state indicator and clearer descriptions for settings that affect the actual camera pipeline.
- Reworked the Material Lab navigation and spacing so it behaves like a product surface rather than a component demo.
- Replaced the old launcher artwork with a full-bleed glass icon; there is no baked outer border or pre-rounded mask.

### Runtime

- Slider edits are debounced before writing the shared configuration file.
- Configuration writes now run off the UI thread.
- Configuration files are written through a temporary file before replacement to avoid partial reads.
- The preview renderer now runs on a dedicated background executor and drops stale results.
- Preview backdrops use Android's system document picker instead of broad storage permissions.
- Watermark frames are associated with their destination bitmap instead of a process-global "current" slot, reducing cross-capture races.
- Bitmap crop padding is derived from the panel's short side to avoid unnecessarily large temporary buffers.

### Project hygiene

- Removed the old gallery icon/demo visual language in favor of the shared LiquidFrame identity.
- Kept the original optics and panel-scan implementation intact; this pass is primarily product polish and runtime hardening rather than a material rewrite.