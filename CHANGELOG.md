# Changelog

## 1.8.0 — Reference-aligned glass architecture

### Bottom bar
- **Three-layer glass stack**: the bar is now three independent materials —
  a panel layer with constant 24dp lens and full shadow/inner shadow,
  a capture layer whose lens scales with press progress (skipped entirely at
  rest, never `lens(0, 0)`), and a moving droplet selection layer that samples
  the page through the panel's exported backdrop with proportional lens scaling
  (10dp/14dp × 49/56) and rest-state floors on rim/shadow/inner shadow.
- **Adaptive tone from real pixels**: a background sampler captures an 8dp band
  above the bar (32×8 bitmap via PixelCopy), converts sRGB → linear light →
  CIELAB L*, and drives a single tone scalar through a double-threshold
  hysteresis (50 ±5 L*), two-confirmation counting, 400ms cooldown and a
  critically damped spring. Three sampling intervals — 2000ms stable / 500ms
  near threshold / 200ms while confirming.
- Selected-tab color follows the sampled tone; the panel coat is a single
  0.52 concentration shared by all three layers.

### About page
- **Scroll-driven hero**: LazyColumn with parallax at 0.12x, the gradient
  background fading out over a 389dp scroll distance, and the logo fading
  (1→0 over the 0.25–0.60 hero range) while shrinking to 0.9.
- Background dissolve uses a vertical white gradient mask
  (opaque through 0–0.68, then transparent) with `BlendMode.DstIn`.
- Palette colors are saturation-strengthened (1.18x + 0.015 brightness lift)
  and the radial gradients use a 0.62 radius ratio on a 12-second
  four-segment interpolation cycle.

### Build
- versionCode 8, versionName 1.8.0.
- Added a Chinese README (`README_CN.md`); the English and Chinese READMEs
  link to each other.

## 1.7.0 — Glass UI overhaul

### Bottom bar
- **Full-width liquid-glass navigation bar**: the bar runs edge to edge behind the
  system navigation area and samples the page backdrop for a real frosted material.
  The selected tab sits inside a translucent accent capsule.

### Sliders
- **Fixed sliders that could not be dragged**: the thumb's drag detector now awaits
  pointer events directly and gates on horizontal motion, so a vertical page scroll
  never consumes the gesture and a horizontal drag never scrolls the page. Taps on
  the track jump straight to the tapped position through the damped spring.

### Settings persistence
- **Fixed settings not taking effect**: the primary config path is now the module's
  own data directory (always writable without root), with a dual write to the
  camera-readable shared path — plain write first, `su` escalation only when
  refused, throttled so a slider drag does not fork a root shell per frame.
- The write outcome is measured (including a read-back verification) and surfaced
  on the home page instead of being silently swallowed.

### Glass parameters page
- The live preview moved from the home page to the top of the parameters page,
  next to the sliders it reflects.

### About page
- **Redrew the header icon**: the animated gradient field itself is sampled as the
  icon's backdrop, and a short capsule of liquid glass is rendered centered on top
  of it — the same material the module applies in the camera.

### App icon
- Redrew the launcher icon: gradient field backdrop with a centered short glass
  capsule, rim light, specular band and refraction glow.

### Diagnostics
- Rewrote the copied diagnostic log: version, write status, preview outcome and the
  full parameter list — nothing else.

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