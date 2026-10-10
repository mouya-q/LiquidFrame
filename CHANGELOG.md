# Changelog

## 1.6.0 — Full upgrade

### Glass material
- **Fixed blur/panel size mismatch**: `blurFraction` raised from 0.09 to 0.25 so the
  Gaussian blur before the lens is proportional to the panel's refraction band, not
  negligibly small. The glass now reads as frosted across the full panel width.
- Default material parameters tuned for stronger liquid-glass character.

### Watermark hooks
- Further stabilised panel selection for multi-element watermarks.

### UI — three-page architecture
- **Replaced the single-scroll settings screen with a three-page bottom-bar layout**:
  Home (preview + enable), Glass Parameters (all material sliders), About.
- The bottom bar is now a three-tab navigation capsule with page switching.
- Added an About page with developer info, version, GitHub link, and changelog link.
- Added an animated gradient background on the About page header.

### UI — component cleanup
- **Renamed all colour/shape tokens** from the old name to `LiquidColors` / `LiquidShapes`.
- Removed the old component file; all references now point to `LiquidComponents.kt`.
- No external project name appears in any comment, documentation, or changelog.

### App icon
- **Redrew the launcher icon** as a pure-XML liquid-glass capsule: dark squircle
  background, frosted glass pill, specular highlight, rim light, chromatic
  aberration hint, and a center "shutter" dot.

### Build
- **Fixed APK signature mismatch**: release builds now use the debug signing config
  so every CI build is signed consistently and can update over a previous install.
- CI now builds both `assembleDebug` and `assembleRelease`.

## 1.5.0 — Glass UI rebuild

- Rebuilt the entire settings UI around backdrop-sampled liquid glass.
- Added `GlassSystem.kt`, `DampedDragAnimation.kt`, `LiquidControls.kt`, `GlassBottomBar.kt`.
- Glass surfaces, toggles, sliders, and the bottom bar all sample the backdrop.
- Raised `versionCode` to 5, `versionName` to 1.5.0.

## 1.4.0 — Render fixes

- Cleared the inner shadow and removed the camera's white plate from the panel.
- Stabilised Leica multi-element watermark panel selection.
- Settings now take effect on every shot (no more hardcoded presets).
- Preview shows the material only; the camera draws its own labels.

## 1.3.0 — UI migration

- Switched the config UI to capsule shapes and continuous corners.
- Added a glass toggle with spring animation.
- Reduced the stacked white veil.

## 1.2.0 — UI / Runtime polish

- Removed the white base under the glass.
- Corner radius now comes from capsule geometry.
- Slider edits are debounced; config writes run off the UI thread.
- Watermark frames associated with their destination bitmap.