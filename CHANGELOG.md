# Changelog

## 1.9.2 — Photo-anchored watermark, dock spring and identity polish

### Watermark rendering
- **Photo-anchored glass (根治镜像/白边/尺寸三病灶)**: the composite's first plate draw now captures the real photograph *before* the plate is painted over it. The optics receive that pre-plate snapshot as backdrop, so the glass refracts the actual scene under the panel instead of the camera's baked plate — no mirroring, no white corners, and the watermark no longer changes the photo's scale.
- Capture is cropped to the first panel rectangle plus padding (≈ hundreds of KB per shot instead of a full 48 MB frame), with a silent fallback to the existing mirror path if the capture fails.
- Reordered the backdrop build: mirror fills the whole plate rectangle first (clearing white corners), then the snapshot overwrites the panel interior with true photo content.

### Bottom bar
- Softer, more liquid springs: press scale `spring(0.62, 420)` and selection travel `spring(0.62, 380)` — lower damping adds a slight overshoot as the capsule settles.
- Press feedback is now scale + sink: the indicator dips ~1.5dp via `translationY = (1 - selectionScale) * 22dp` while pressed, then springs back.

### Identity & controls
- App name changed to `liquidFrame` (launcher, activity title, home header, toggle text and About page hero).
- The enabled switch track is now a light white frost (`0xFFE8E8ED` dark / `White` light) instead of iOS green, matching the reference material.
- New restart-camera button at the top-right of the home page: force-stops `com.android.camera` / `com.miui.camera` so the watermark changes apply on next launch.
- Version: `1.9.2`, `versionCode 11`.

---

### Dock and controls
- Replaced the full-width white-tinted bar with a floating capsule, a correctly aligned moving glass indicator and a single base material layer.
- Added real backdrop color variation so the dock lens has something to refract; reduced resting white veils on the dock, slider knob and toggle knob.
- Fixed slider state observation, added tap-to-seek and horizontal drag across the 36dp track hit area, and kept vertical page scrolling unclaimed.
- Made the liquid switch respond to direct taps and reconcile its animated fraction with the externally controlled state.
- Added a guarded non-AGSL fallback for pre-Android 13 devices.

### Watermark material
- Localized the text-preservation mask to the watermark crop instead of allocating a full-photo float buffer.
- Sized sampling padding from refraction displacement and blur radius; clamped invalid material values.
- Kept the blur continuous through the glass interior and feathered label edges to reduce visible aliasing.
- Refreshed camera-side config before the disabled early-return and fixed config-sync status reporting.

### Identity
- Replaced the old launcher PNG with a transparent XML glass-tile mark, with adaptive icon and Android 13 monochrome variants. Removed the stale Gallery PNG icon.
- Version: `1.9.1`, `versionCode 10`.

Validation note: pure Kotlin renderer smoke-tested; Android Gradle build remains unverified in the offline environment because `services.gradle.org` could not be resolved.

---

## 1.9.0 — Render quality and interaction overhaul

### Watermark rendering
- **Panel union selection**: the camera draws a watermark group as several
  rectangles (the capsule plus glow strips above and below). Picking only the
  widest single rectangle left the strips as raw white plates. Eligible
  elements are now merged into their union bounding box before ranking, with
  a size guard that declines the group when the union swallows the frame.
- **Blur covers the whole glass**: blur used to stop at the refraction band's
  inner boundary, leaving a sharp ring inside the lens. Blur strength now
  ramps linearly across the band, so the frosted interior blends continuously
  into the lens edge.
- **Dispersion taps clamped to the silhouette**: the seven spectrum taps could
  fan out past the panel edge into the camera's baked plate, reading as a
  white fringe (the rainbow edge). Taps that would land outside the silhouette
  are pulled back along the fan toward the base point.
- **Soft content mask**: the camera's labels were kept through a hard
  threshold, so their antialiased edge pixels were covered by the material and
  glyphs grew jaggies. The mask is now feathered - fully content beyond the
  luma threshold, fully background within half of it, a linear mix between -
  and the material composites through the label's own share of each edge pixel.

### Config UI
- **Sliders finally drag**: the thumb's detector now races the page scroll at
  the touch-slop stage and claims the gesture only when horizontal motion
  wins; a vertical flick scrolls the page, a horizontal one drags the thumb,
  and the claimed stream is fully consumed so it cannot be stolen mid-drag.
  The accumulated slop distance feeds the first drag delta, so the thumb
  tracks the finger from the first frame.
- **White mask removed from controls**: the slider thumb and toggle painted a
  fully opaque white veil at rest; only a faint press-thinned frost remains.
- **Press refraction activated on the bottom bar**: tab press progress now
  drives the capture layer's lens and the selection droplet's fold - the
  three-layer architecture's refraction channel had been hard-wired to zero.
- **About page name capsule**: enlarged to a 190×58 hero-scale capsule with a
  stronger lens, wider rim light and deeper shadow; the wordmark is 21sp with
  letter spacing.

### App icon
- Redrew the launcher icon as pure XML: dark field with flowing light, a
  full-width glass capsule with rim light, specular band, clipped refraction
  bands and edge dispersion fringes. The stale bitmap icon was removed.

### Build
- Fixed keystore committed to the repository; debug and release builds share
  it, so every CI artifact installs over any previous build without an
  uninstall.
- versionCode 9, versionName 1.9.0.

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