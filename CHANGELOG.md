# Changelog

## 2.0.0 — Source-level watermark interception, EXIF value-range scan, UI rebuild

### Source-level original watermark suppression
- Reverse-engineered the camera 6.4.000250.2 watermark pipeline at the smali level. Confirmed the white bar originates from `Fe/a.b()`, which calls `Canvas.drawColor()` using the color stored in `Fe/b.a` before blitting element bitmaps.
- New `hookBlitMethod()` hooks `Fe/a.b()` in `beforeHookedMethod`, reflectively locating the `Fe/b` data object (field `i` on 6.4.x) and zeroing its int color field (`Fe/b.a`). This prevents the solid fill from being drawn at the source, while element glyphs and logos still blit normally.
- Rewrote the `processCapsule` eradicate logic with a three-tier fallback:
  - **src-rebuild** (primary): when `srcPhoto` is available, rebuilds the entire bottom region row-by-row from the original photograph, scaled to match the output canvas. The most reliable repair — no pixel scanning or color detection dependency.
  - **strip-fill** (fallback 1): repaints the appended fill strip with the detected factory fill color when `srcPhoto` is unavailable.
  - **over-photo** (fallback 2): samples surrounding scene colors and fills the plate region when neither source nor strip is available.
- Removed the ineffective `plate-flat` (`eraseFlatRegion`) and `plate-patch` modes.

### EXIF extraction — value-range numeric field scan
- `scanNumericFields()`: a BFS traversal of the composite call's argument object graph (depth 4) that classifies `Number`-typed fields by EXIF plausible value ranges — ISO (integer 25–102400), aperture (double 0.95–22 or int×10), exposure (double 1e-6–30, with ns/µs conversion), focal length (double 0.5–1000). The first plausible match per category wins; redundant matches are suppressed.
- Integrated as the final fallback in `extractMeta()`, after `readExifHints()` (method-name matching) and `deepExifScan()` (depth-2 field-type matching). This addresses the 6.4.x full-obfuscation case where method names are single letters and completely uninformative.

### Full logging system
- `LFLogger.kt`: a new 116-line logging system with five levels (VERBOSE/DEBUG/INFO/WARN/ERROR), no rate limiting, a 2000-entry in-memory ring buffer, and a 512 KB file at `/data/local/tmp/lf_log.txt`.
- `WatermarkHooks.kt`: removed the old `LOG_RATE_LIMIT` constant and the 36-line rate-limited `log()` function; replaced with `LFLogger.d()` + `XposedBridge.log()` proxy. All hook pipeline events are now logged without suppression.
- `CapsuleRenderer.kt`: `scanNumericFields` reports matched values via `LFLogger.v/i/w`.

### UI rebuild — flat layout, live preview, log page
- Removed the Glass page tab switcher (`glassTab` state, `glassTabs` list, tab UI, `when(glassTab)` branches). The Material, Capsule, and Detail sections are now flat-laid in a single scrollable column.
- Capsule parameter `LaunchedEffect` now calls `schedulePreview()` so changes to logo, scale, toggles, and model text are reflected in the live preview immediately (previously skipped to save a render).
- New `LiquidPage.Log` page in the bottom navigation bar with a Description icon. Displays the last 200 log lines with ERROR/WARN/INFO color highlighting, plus copy-to-clipboard and clear-log actions.

## 1.9.23 — Adapt to camera 6.4.x composite signature

### Composite hook compatibility
- `DexScanner.hasComposite()` no longer requires `ColorSpace` in the parameter list or a `Bitmap` return type. On camera 6.4.000250.2 the composite method (`Fe/a->j`) returns a `WatermarkRemover$b` wrapper instead of a raw `Bitmap`, and drops the `ColorSpace` parameter entirely. The scanner now matches on: static + has `Bitmap` parameter + returns either `Bitmap` or a `WatermarkRemover`-typed wrapper.
- `hookCompositeFallback` similarly accepts methods whose return type is a `WatermarkRemover` wrapper, not just `Bitmap`.
- `hookComposite.afterHookedMethod` now calls `unwrapBitmap()` which handles both the legacy direct `Bitmap` result and the 6.4.x wrapper by reflectively scanning the wrapper's declared fields for the first `Bitmap`. This restores the entire post-composite pipeline (white-strip removal, liquid-glass capsule rendering, EXIF/GPS text overlay) on 6.4.x builds.

## 1.9.22 — Rebuilt the capsule around the supplied iOS reference

### Watermark geometry and layout
- Replaced the equal-ish three-column layout with reference-proportioned zones: left-aligned model/time, a monochrome Leica wordmark near 63.5% of the capsule width, a thin vertical separator near 68.5%, and right-aligned EXIF/GPS details.
- Calibrated the capsule to about 90% of the frame width and 12% of the short side in height, with a 50%-height corner radius, a ~1% left inset, and a ~9.6%-short-side bottom inset.
- Fitted the left and right text groups independently; long EXIF/GPS lines no longer shrink the device name.
- Added a fine, rounded separator and restrained text/wordmark shadows; labels remain white like the reference instead of flipping to dark text based on average scene brightness.
- Added a transparent, white Leica wordmark asset. The original red badge remains as a fallback for packaging or decoding failures.
- Migrated the legacy implicit `center` capsule position to `left` because the position was not exposed as a user setting.

### Liquid-glass defaults and preview
- Reduced the refraction band, displacement and blur to gentler values (`0.08`, `1.2×`, `0.14×`) that avoid the strong lens warp and oversized blur band of the former defaults.
- Aligned the optics fallback values with `GlassParams.Default`, avoiding a different material when a non-finite setting is encountered.
- Updated the in-app material preview to use the same pill proportions, edge alignment and bottom inset as the capture renderer.

## 1.9.21 — Recalibrated against precise iOS watermark geometry analysis

### CapsuleRenderer — geometry corrected to match the iOS reference math
- **Capsule width** restored to `0.92` (was wrongly reduced to 0.90 in 1.9.20): the reference watermark is 92% of the photo width.
- **Capsule height** `TARGET_HEIGHT_REF` 70 → 76: ≈ 7% of photo height (0.07 × 1080).
- **Corner radius** `CORNER_RADIUS_REF` 30 → 38: ≈ 0.5 × capsule height → pill shape.
- **Horizontal padding** changed from fixed ref units to fraction: `PAD_X_FRACTION = 0.06` (6% of capsule width). This scales correctly with any photo resolution.
- **Vertical padding** changed from fixed ref units to fraction: `PAD_Y_FRACTION = 0.10` (10% of capsule height per side).
- **Bottom inset** `BOX_INSET_REF` 30 → 52: ≈ 4.8% of photo height (0.048 × 1080), matching the golden-ratio placement.
- **Line gap** 7 → 6: restored to match the reference's tighter text spacing.
- **Logo height fraction** stays at 0.42: ~42% of interior height, matching the reference logo size.

### GlassParams — material parameters adjusted
- **`surfaceAlpha`** 0.04 → 0.15: the iOS reference's background transparency is ≈ 15%, not the previous 4%.
- **`rimAlpha`** 0.50 → 0.30: the iOS reference's edge highlight opacity is ≈ 30%, not the previous 50%.
- **`refractionAmountFraction`** stays at 1.5 (reduced from 3.0 in 1.9.20): the reference shows a gentle frosted bend, not a strong lens.

### WatermarkHooks — inset stays decoupled
- **`inset`** calculation keeps the fix from 1.9.20: `BOX_INSET_REF × unitPx` clamped to `[4, shortSide × 0.12]`, no longer inflated by `padPx`.

### ConfigActivity — preview parameters corrected
- `PREVIEW_PANEL_H_FRACTION` 0.065 → 0.07, `PREVIEW_SIDE_FRACTION` 0.05 → 0.04, `PREVIEW_BOTTOM_FRACTION` 0.028 → 0.048: all now match the real capsule's proportions exactly.

### CapsuleRenderer — design constants recalibrated
- **Capsule width** `TARGET_WIDTH_FRACTION` 0.92 → 0.90: the reference watermark is 90% of the photo width, not 92%.
- **Capsule height** `TARGET_HEIGHT_REF` 60 → 70: the reference capsule is taller (~65 dp at density 3), giving text more vertical breathing room.
- **Corner radius** `CORNER_RADIUS_REF` 18 → 30: the reference capsule has a pill-like radius (~43% of its height), not a subtle 18 ref units.
- **Model text size** `MODEL_SIZE_REF` 28 → 30: the device name is the largest text element and should dominate the left column.
- **Body text size** `BODY_SIZE_REF` 22 → 24: date/time, EXIF and GPS lines should be clearly readable, matching the reference proportions.
- **Horizontal padding** `PAD_X_REF` 24 → 26: slightly more inset from the capsule's left/right edges.
- **Vertical padding** `PAD_Y_REF` 8 → 10: slightly more inset from the capsule's top/bottom edges.
- **Line gap** `LINE_GAP_REF` 6 → 7: slightly more vertical spacing between stacked text lines.
- **Column gap** `COL_GAP_REF` 20 → 24: more separation between the text columns and the centre logo slot.
- **Logo height fraction** `LOGO_HEIGHT_FRACTION` 0.50 → 0.42: the reference logo occupies about 42% of the capsule's interior height, not the full half.
- **Bottom inset** `BOX_INSET_REF` 45 → 30: the reference capsule floats closer to the photo's bottom edge (~10 dp at density 3), not 15 dp.

### GlassParams — refraction amount reduced
- **`refractionAmountFraction`** default 3.0 → 1.5: the watermark capsule is much wider than tall, and at 3.0 the lens distortion at the short edges was excessive. The reference watermark shows only a gentle frosted bend, not a strong lens. This also dramatically reduces `requiredPanelPaddingPx`, which was the root cause of the inflated `padPx` and `inset` values.

### WatermarkHooks — inset decoupled from padPx
- **`inset` calculation** no longer uses `.coerceAtLeast(padPx + 6f)`: previously the optics padding (which could reach ~473px due to `amountFraction=3`) was used as a floor for the inset, pulling the capsule far from the photo's bottom. Now `inset` is simply `BOX_INSET_REF × unitPx` clamped to `[4, shortSide × 0.12]`, placing the capsule exactly where the reference shows it.

### ConfigActivity — preview parameters synced
- **`PREVIEW_PANEL_H_FRACTION`** 0.0986 → 0.065: the preview capsule height now matches the real capsule's proportions.
- **`PREVIEW_SIDE_FRACTION`** 0.004 → 0.05: the preview capsule horizontal inset now matches the real capsule's 5% margin.
- **`PREVIEW_BOTTOM_FRACTION`** 0.0128 → 0.028: the preview capsule bottom inset now matches the real capsule's closer-to-bottom placement.


## 1.9.19 — Root-cause fix: eradicate failure, capsule position, DexScan class-name format

### WatermarkHooks — over-photo erase path (root cause 1: `eradicate: mode=none`)
- **New "over-photo" erase path** added to `processCapsule`: when the camera paints the watermark plate directly on the photograph (not on a strip below it) and no source bitmap is available for reconstruction, the plate rectangle is inpainted with the averaged colour sampled from rows just above and below the plate. This path bypasses the strict variance gate of `eraseFlatRegion` because a flat-ish inpaint is always better than leaving the stock watermark glyphs standing.
- **Erase path priority** is now: strip-fill → plate-patch (srcPhoto available) → over-photo (no srcPhoto, over-photo layout) → plate-flat (last resort with variance gate).
- **`hookComposite` src extraction** improved: previously filtered `it !== out` before checking recyclability, which could miss the case where the composite mutates the input bitmap in place. Now all valid bitmaps are collected first, then filtered against `out`. Diagnostic log added: `composite: WxH mutable=... src=...`.
- **`placementCap` simplified**: the old `when` branch that pushed the capsule above the plate when erase failed (`eraseMode == "none" → plate.top - inset - 8f`) caused the capsule to render at y=80 instead of near the photo bottom. Now `placementCap` is always `anchorY` (photo bottom), and the inset cap ensures the capsule stays within the photo.

### WatermarkHooks — capsule position fix (root cause 2: capsule at y=80)
- **`padPx` capped** to `capsuleH × 0.5f`: previously `requiredPanelPaddingPx` could return extremely large values (displacement = band × amountFraction where amountFraction defaults to 3), pushing padPx to ~473px on a 720px short side, which in turn pushed `inset` to the same value and placed the capsule near the top of the photo.
- **`inset` capped** to `shortSide × 0.12f`: even if the optics padding calculation returns a large value, the inset from the photo's bottom edge is now bounded to 12% of the short side (~86px at 720px), keeping the capsule near the bottom where it belongs.

### DexKitHelper — class-name format fix (root cause 3: `ClassNotFoundException: LFe/a;`)
- **`load()` method** now converts DEX type descriptors (`Lpe/o;`) to Java binary names (`pe.o`) before calling `Class.forName`. DexScan returns class names in DEX descriptor format (with `L` prefix and `;` suffix), but `Class.forName` expects the Java format. This caused the runtime discovery upgrade path to fail silently — `Class.forName("LFe/a;")` always threw `ClassNotFoundException`, so even when DexScan found the correct candidates, `pick()` returned `null` and the module stayed on hardcoded fallback names forever.


## 1.9.18 — Fixed-dimension capsule layout and logo path resolution

### CapsuleRenderer — fixed-dimension layout matching the iOS reference
- **`measureCapsule` rewritten** from content-driven to fixed-dimension: the capsule width is `TARGET_WIDTH_FRACTION × photoW` (92%) and height is `TARGET_HEIGHT_REF × unit` (~56 dp at density 3), so every shot has the same footprint regardless of text length — matching the iOS liquid-glass watermark.
- **Four-pass text shrink**: text is iteratively scaled down (up to 4 rounds, floor at 30%) to fit inside the fixed box, with a secondary height-based shrink when the text block exceeds the interior.
- **Logo geometry** derived from fixed capsule height (`LOGO_HEIGHT_FRACTION × interiorH`) instead of text height, so the logo size is consistent across shots.
- **Logo centring** computed from overall available width `(capsuleW - 2×padX - logoW) × 0.5`, placing the logo in the true centre of the bar.
- **Text vertical centring** based on `interiorH` rather than accumulated text height, keeping content vertically stable.
- **New design constants**: `TARGET_WIDTH_FRACTION=0.92f`, `TARGET_HEIGHT_REF=60f`, `CORNER_RADIUS_REF=18f` (now public, exported for use by the hook layer). `MODEL_SIZE_REF` 42→28, `BODY_SIZE_REF` 31→22, `PAD_X_REF` 34→24, `PAD_Y_REF` 22→8, `LINE_GAP_REF` 15→6, `COL_GAP_REF` 28→20, `LOGO_HEIGHT_FRACTION` 0.48→0.50, `BOX_INSET_REF` 42→45.

### WatermarkHooks — logo loading robustness
- **`loadLogo()` rewritten** with `resolveModuleApkPath()` multi-strategy path resolution, replacing the sole `XposedBridge.getModulePath()` reflection that returned null in LSPosed environments. Strategies: (1) XposedBridge reflection, (2) class-loader resource URL, (2b) protection-domain code source, (3) thread context class-loader resource, (4) Magisk/LSPosed module directory scan. Diagnostic logging at every failure point.
- **`extractApkFromUrl()`** helper parses `jar:file:...!/entry` and `file:...` URL formats to extract the on-disk APK path.

### WatermarkHooks — capsule corner radius
- **Corner radius** changed from `capsuleH × 0.5f` (full pill shape) to `CORNER_RADIUS_REF × unitPx` (≈18 dp), coerced to `[1, capsuleH × 0.5]`. Applied to both the proto-capsule (padding calculation) and the final capsule PanelRect. Matches the iOS reference's rounded-rectangle (not pill) shape.

## 1.9.17 — Three-column capsule with centre logo

### CapsuleRenderer — three-column layout
- **`measureCapsule` rewritten** from single-column centred to three-column: left block (model + time, left-aligned), centre logo slot, right block (exif + GPS, right-aligned). Falls back to single-column centred when no logo and only one column has content.
- **`Line.column` field** (0 = left, 1 = right) drives column assignment; `buildLines` already marks EXIF/GPS as column 1.
- **`PlacedLine.align`** (0 = left at `leftX`, 1 = right at `leftX`, 2 = centred at `centerX`) replaces the old always-centre layout; `leftX` carries the anchor edge for left/right alignment.
- **Width measurement** computes `leftW + colGap + logoW + colGap + rightW` (omitting empty columns/logo), with the same two-pass shrink (94% photo width, then 50% short-side height) as before.
- **Vertical centring**: each block is vertically centred within the capsule interior; logo is centred in the interior height at `LOGO_HEIGHT_FRACTION × interiorHeight`.
- **`LogoPlacement`** class and `Metrics.logo`/`Metrics.logoBitmap` carry logo geometry and bitmap to the raster stage.

### CapsuleRenderer — text raster with aligned drawing and logo
- **`drawTextLayer` rewritten**: per-line `Paint.Align` (LEFT/RIGHT/CENTER) set from `PlacedLine.align`; anchor x computed from `leftX` or `centerX` accordingly.
- **Logo bitmap** drawn into the same ARGB text layer via `Canvas.drawBitmap` with `FILTER_BITMAP_FLAG`, so it passes through `blendTextOver` as surface ink (straight-alpha over, alpha ≥ 8 threshold).

### WatermarkHooks — logo loading and injection
- **`loadLogo()`**: reads `assets/leica_logo.png` from the module APK at runtime (ZipFile + BitmapFactory.decodeStream), cached in a `@Volatile` field with double-checked locking. Returns null on any failure, degrading gracefully to no-logo layout.
- **`Options` construction** in `processCapsule` now passes `showLogo` and `logo` from `ConfigStore.capsuleShowLogo()` and `loadLogo()`.
- **`measureCapsule` call** passes `opts` for logo-aware layout.

### ConfigStore — new key
- **`KEY_CAPSULE_LOGO = "capsule_logo"`**, `capsuleShowLogo()` (default true), included in `snapshot()`.

### ConfigActivity — UI
- **"徕卡标" toggle** in the 水印胶囊 section, wired to `capsuleShowLogo` state, `LaunchedEffect` save key, `resetDefaults`, and diagnostic log export.

### Assets
- **`leica_logo.png`** (960×960 RGBA transparent) added to `app/src/main/assets/`.

### Version
- `1.9.17`, `versionCode 26`.

## 1.9.16 — Industrial-grade audit: wide-pixel batching, allocation and paint hygiene, capsule settings surface

### WatermarkHooks — capture-thread performance (JNI crossings crushed)
- **`detectPhotoBottom` now reads one full row per scanned line with a single bulk `getPixels`** instead of issuing a per-column `getPixel` (about 64 JNI crossings per row; tens of thousands on a no-fill worst case). The column stride is applied Java-side over a reusable row buffer, and the boxed per-frame `(0 until w step …).toList()` column list is gone. Selection semantics — luma mean/variance, thresholds, scan direction — are unchanged.
- **`scanPanel` (the pixel fallback) batches the same way**: each sampled row of the coarse grid is one bulk read decimated into the grid buffer, replacing a per-pixel `getPixel` loop whose in-source justification ("a few thousand reads, nothing next to a 12 MP copy") understated the JNI per-call cost. Grid content, work resolution and the scale-back/label-confirmation steps are identical.

### CapsuleRenderer — time formatting, paint and allocation hygiene
- **`SimpleDateFormat` per capture is dead.** `formatTime` now formats with one file-level immutable `DateTimeFormatter` (`java.time`, minSdk 26), thread-safe across concurrent composites and free of repeated pattern parsing. Output format is byte-identical (`yyyy.MM.dd HH:mm:ss`, `Locale.US`, system zone).
- **`measureCapsule` collapses two paints into one stack-local paint** re-keyed per line weight through a `preparePaint` helper — Paint carries heavyweight native resources and the object's contract forbids shared mutable state, so the paint lives on the stack, not in fields. The height pass caches each line's ascent/descent, so the baseline pass re-queries font metrics only in the rare half-height shrink path. Geometry output is unchanged.
- **`drawTextLayer` likewise uses a single paint** (typeface and letter-spacing assigned per placed line) instead of constructing two identical-purpose paints per capture.
- **Two loop-body `intArrayOf` allocations hoisted** out of the sampling loops in `eraseFlatRegion` and `fillColorFromStripTop`.

### LogHelper — concurrent timestamp safety
- **The shared `SimpleDateFormat` formatted log lines outside any lock** while camera threads and the settings screen log concurrently — a thread-unsafe formatter under real contention, with corrupted timestamps the visible symptom. Replaced with an immutable `java.time` `DateTimeFormatter`; the `HH:mm:ss.SSS` output shape is unchanged.

### ConfigActivity — the seven capsule keys finally have a UI
- **New "水印胶囊" section on the home page**: the master rebuild toggle, a three-way placement selector (居中/靠左/靠右, `center`/`left`/`right`), a single-line model-override field (32-char cap, empty = auto-detect), a 0.6×–2.0× size slider, and per-line toggles for the datetime, camera-parameter and GPS rows.
- **Deliberate preview split**: capsule edits save through the same store but do not re-run the preview render — the preview pipeline shows the legacy watermark panel, so a capsule-driven preview would burn a render for an identical image. Material edits keep their existing preview behavior.
- **"恢复默认参数" and the copied diagnostic log now cover all seven capsule keys** (previously the reset silently kept stale capsule values and the log hid the capsule state entirely).

### Repository hygiene
- **`.backup/` (8.4 MB of host-application session JSON unrelated to the project) deleted**, the repo's only junk residue.
- **`OPTIMIZATION_NOTES_CN.md` removed**: zero references anywhere in the repo, contents fully superseded by the changelog, carrying seven forbidden upstream-name links and claims ("Gradle cannot compile here") that CI has long falsified.
- **Upstream references swept out of source comments and READMEs**: `GlassParams.kt`, `LiquidGlassOptics.kt`, `LiquidControls.kt`, `gallery/glass/Lens.kt`, `README.md` ×2, `README_CN.md` ×2. All wording now points at the `NOTICE` file, which remains the single legal Apache-2.0 §4(c) attribution block (the one sanctioned exception).
- **CI builds and uploads the release artifact only** (debug cost CI minutes and was never the installed variant; the gallery path never produced output). The upload path is narrowed to the release output and a missing artifact now fails the run — a green run with an empty artifact must never happen again.

### Version
- `1.9.16`, `versionCode 25`.

---

## 1.9.15 — Rebuilt capsule: eradicate the stock plate, stamp a fresh glass capsule

### WatermarkHooks — the rebuilt-capsule pipeline (major rewrite)
- **The stock watermark is no longer patched on top of — it is eradicated first.** Three-path strategy: **strip-fill** (the plate hangs in the bottom fill strip → sample the strip's own color and tile the whole band), **plate-patch** (the plate sits on the photograph → rebuild the covered region from the original source bitmap at a single scale factor; rows the source cannot serve are left untouched, never stretched), and a **plate-flat** fallback that paints only the plate's own band and refuses automatically on textured backdrops. Every capture logs `eradicate: mode=…` so failures are diagnosable from logcat alone.
- **Strip vs on-photo layout detection now requires a real gap**: `plate.top >= photoBottom + 12 px` (`STRIP_PLATE_GAP_MIN`). Previously a naive `photoBottom >= 0` check let the strip path smear flat color over real scene pixels whenever the plate rode on the photograph — a destructive misclassification, not a cosmetic one.
- **`detectPhotoBottom` fallback from the source bitmap**: when the scan returns −1 and `srcPhoto` is available, the photograph's projected bottom edge derives from `srcH * (w / srcW)` instead of degrading to the frame's bottom.
- **`processCapsule` signature** now carries `thisObj` / `args` end-to-end, so model, date and GPS text come from the actual capture context.
- **Optics contract flipped for the capsule**: `renderPanel` is called with `panelIsOpaquePlate = false` + `params.copy(preserveContent = false)`, structurally closing the mirrored-interior and preserve-content branches — the glass now always bends the real scene behind it (the root fix for "mirror of the upper photo" under the glass).
- **Optical inset calibration**: the capsule's bottom inset is `max(BOX_INSET_REF * unitPx, opticsPad + 6)`, the pad precomputed from a prototype capsule (`requiredPanelPaddingPx` depends only on the box's short side and the params, so a prototype is exact). The crop's bottom edge no longer amputates the lens's lower sample window, which previously stretched the bottom refraction band and let fill-band white bleed in.
- **Placement guards**: if the eradicate refused (`eraseMode == "none"`) the surviving stock plate stays bare — the capsule re-anchors above it, optics pad and all; a crop that cannot both stay inside the photograph and fully cover the capsule aborts loudly instead of silently smearing.

### Version
- `1.9.15`, `versionCode 24`.

---

## 1.9.14 — Frosted card material, deduplicated bottom bar, ambient backdrop field

### GlassSystem — frosted card material (new API)
- **`Modifier.liquidGlassCard(shape, cornerRadius, glass, border)`** added. Effect chain: `vibrancy → 18dp blur → lens(8f, 18f, depthEffect, no chromatic aberration) → default highlight → 22dp/0.42 shadow`, topped with a 0.72-alpha glass fill drawn in `onDrawSurface`. Falls back to `clip + background + 0.5dp border` when no backdrop is available (API < 33 or no hardware acceleration).
- **`glassContainer()`** added as the standard card fill: `White/0.72` in light mode, `#1C1C1E/0.72` in dark mode (dashboard palette of the companion haptic engine app).
- **`LiquidBackdropProvider`** now records a real ambient field into the backdrop layer — an opaque vertical base gradient (`#0B0B0D→#0E0E12` dark, `#F2F2F7→#ECECF1` light) plus two large soft color pools (`#D8E7FF` top-left / `#FFF1D6` bottom-right in light; `#3B2E7A` / `#2A3B5E` in dark) — instead of a flat solid sheet, so blur and lens refraction have actual color variation to work on. Also removed an ineffective full-screen `lerp(base, Transparent, 0f)` overlay that occluded the base gradient, plus two dead `lerp` imports.

### LiquidComponents — containers on the new material
- `GlassCard` (22dp corner) and `SettingSection` (16dp corner) switched from `liquidGlassSurface` to `liquidGlassCard` with `glassContainer()` fill. The old path stacked a 0.60/0.46-alpha `surfaceColor` veil over the blurred backdrop, which visually flattened the frosted blur; the new fill matches the dashboard recipe at 0.72 alpha.

### GlassBottomBar — single content declaration (470 → 416 lines)
- Tab content previously declared twice (~92 duplicated lines: neutral ink in Layer 1, full-accent in Layer 2) is now one shared `tabContent` lambda consumed by both layers.
- Layer 2 now tints via an offscreen compositing layer plus a `drawWithContent` `SrcIn` fill — the internal semantics of `ColorFilter.tint(accentColor)` — before recording into `tabsBackdrop`, so the active capsule — sampling `rememberCombinedBackdrop(backdrop, tabsBackdrop)` — reveals tinted tab content, exactly the original three-layer mechanism. (`ColorFilter.tint` is not a `graphicsLayer` parameter on Compose 1.7; the direct port failed CI with `NO_VALUE_FOR_PARAMETER`.)
- Unused import churn cleaned up during the rewrite; the tint fix re-adds `drawWithContent`/`BlendMode` (both now load-bearing in the Offscreen+SrcIn path).

### Version
- `1.9.14`, `versionCode 23`.

---

## 1.9.13 — Code quality audit: ANR guard, concurrency, error visibility

### WatermarkHooks — ANR time budget (audit item A)
- **`RENDER_TIME_BUDGET_MS = 1200L`** constant added. The `process()` entry now records `SystemClock.elapsedRealtime()` and checks the elapsed time before the `renderPanel` pass; if the pre-render work (scan + crop + photo read) exceeds 1200 ms, the render is skipped and logged, rather than risking an ANR on the camera's capture thread.
- Per-composite log line now includes `${elapsed}ms` for regression monitoring.

### WatermarkHooks — Log rate limiting (audit item F)
- **`LOG_RATE_LIMIT = 15`** lines per second. The `log()` method now uses a 1-second sliding window; lines beyond the cap are counted and summarised in the next window as `[N log lines dropped in last window]`. This bounds I/O on continuous shooting (burst mode, video frame callbacks) without losing first-occurrence diagnostics.

### ConfigActivity — Bitmap concurrency safety (audit item B)
- `schedulePreview` now checks `source == null || source.isRecycled` before calling `source.copy()`, and validates `copy.width > 0 && copy.height > 0` before passing to the executor. The `catch` block now logs the failure type and message via `Log.w`.
- `loadBackdrop` catch block now logs decode failures.
- `centreCrop` catch blocks now log scale/crop failures.
- `loadAssetBackdrop` catch block now logs asset read failures.
- `sendBroadcast` / `forceStopPackage` catch blocks now log failure type.
- `TAG` companion constant and `android.util.Log` import added.

### ConfigStore — runSu timeout & bounded output (audit item C)
- `runSu` now reads at most 4 KB of process output (was `readBytes()` — unbounded), and uses `process.waitFor(5, TimeUnit.SECONDS)` with `destroyForcibly()` on timeout (was indefinite `waitFor()`). Failure paths now log via `LogHelper.log`.
- `load()`, `writeText()` su-throw, and read-back verify catch blocks now log failure type and message (audit item D).

### LogHelper — runSu timeout & bounded output (audit item C)
- `LogHelper.runSu` receives the same 4 KB cap and 5-second timeout treatment as `ConfigStore.runSu`. The `catch` block now logs via `log("LogHelper", ...)` instead of being empty.

### DexKitHelper — Empty catch logging (audit item D)
- `currentApplication()`, `load()`, and `apkPaths()` split-source catch blocks now log failure type and message via the existing `log()` method.
- `InterruptedException` catch blocks in `awaitApplication` / `awaitDiscovery` are retained as-is (interrupt = bail, no logging needed).

### DexScanner — Empty catch logging (audit item D)
- `scan()` catch blocks for per-dex read failures and per-archive failures now log via `android.util.Log.w` with the entry/archive name and exception type.

### LiquidFrameHook — Empty catch logging (audit item D)
- `currentApplication()` and `getSendingUid()` reflection catch blocks now log via `XposedBridge.log`.

### Audit items not implemented
- **E (DexScanner OOM)**: assessed but not changed — the scanner already reads one dex at a time and releases it; peak memory is one dex, not the full APK. No further mitigation needed.
- **G (Split large files)**: not changed — file sizes are within maintainable range; splitting would introduce import churn without proportional benefit.

### Version
- `1.9.13`, `versionCode 22`.

---

## 1.9.12 — Bottom bar 1:1 alignment with kyant0 LiquidBottomTabs

### Bottom bar (GlassBottomBar)
- **Complete rewrite** to achieve 1:1 code-level alignment with kyant0's `LiquidBottomTabs.kt`, `LiquidBottomTab.kt`, and `InteractiveHighlight.kt`.
- **Three-layer architecture restored**: Layer 1 (outer container glass, 64dp, vibrancy+blur 8dp+lens 24dp, press-scale), Layer 2 (hidden accent layer, 56dp, alpha=0, ColorFilter.tint(accentColor), lens scales with pressProgress), Layer 3 (active tab indicator, 56dp, combined backdrop, lens 10×14dp with chromaticAberration, Shadow+InnerShadow, velocity warp in layerBlock).
- **`DampedDragAnimation`** wired with `pressedScale = 78f/56f`, `onDragStopped` snaps to nearest tab index and animates `offsetAnimation` back to zero.
- **`InteractiveHighlight`** with RuntimeShader radial highlight, `inspectDragGestures` for press/drag tracking, position follows indicator center.
- **`panelOffset`** computed via `derivedStateOf` using `offsetAnimation.value / constraints.maxWidth` (not hardcoded 360f).
- **`LiquidBottomTab`** uses `clickable` (not `selectable`), `clip(Capsule())`, `Role.Tab`, `fillMaxHeight().weight(1f)`, scale from `LocalLiquidBottomTabScale`.
- **`DragGestureInspector.kt`** added — provides `inspectDragGestures` suspend function (identical to kyant0 catalog utils).
- Backdrop null-safety: when `liquidGlassBackdrop()` returns null (API < 33 or no HW accel), each layer falls back to `Modifier.background(color, Capsule())`.
- Tab icons use system drawables (`ic_menu_camera`, `ic_menu_preferences`, `ic_menu_info_details`) with accent/neutral tint.
- Version: `1.9.12`, `versionCode 21`.

---

## 1.9.4 — Coordinate-system fix for white-edge elimination

### Watermark rendering
- **Root cause of remaining white edges**: the photo block's origin was tagged with output-bitmap absolute coordinates (e.g. `photoLeft=200`) while `renderPanel`'s intersection math used crop-local coordinates starting at 0. The `maxOf(cropLeft, photoLeft)` therefore compared ~200 against ~5, producing an empty intersection — the photograph was never written into the backdrop, leaving the camera's baked plate visible at every corner outside the glass silhouette.
- **Fix**: `photoLeft`/`photoTop` now use crop-local coordinates (`0, 0`), and the intersection math in `renderPanel` operates entirely in crop-local space. The photograph now overwrites the complete backdrop, so every corner outside the rounded glass silhouette carries real scene content instead of the camera's white plate.
- Corner-clearing (`erasedCorners`) is no longer gated by `tone.uniformity >= FLAT_PANEL_UNIFORMITY` when a photograph is present (`hasPhoto` bypasses the uniformity check), so white wedges are cleared unconditionally when the optics have the real scene.
- Version: `1.9.4`, `versionCode 13`.

---

## 1.9.3 — Source-bitmap photo anchor (mirror/white-edge root fix)

### Watermark rendering
- **Root cause found and fixed**: the v1.9.2 drawRect pre-plate capture never fired on the real camera pipeline (the `pe.o` wrapper or its drawRect signature changed), so the optics always fell back to `mirrorInteriorFromAbove` — the V-symmetry wood grain in the user's test photo was the mirror's telltale.
- **New approach — source bitmap from composite arguments**: the composite method (`Fe/a->j` or its DexKit-discovered equivalent) receives the original photograph as one of its own arguments. The after-hook now extracts the largest non-output Bitmap argument and passes it to `process()` as `srcPhoto`. This is exact, timing-independent, and does not rely on the drawRect hook at all.
- `process()` reads the panel crop region directly from `srcPhoto` (with proportional mapping and nearest-neighbour resampling when the source resolution differs from the output). The optics receive the true scene as `photoBackdrop` — no mirroring, no white corners, no scale change.
- Removed the duplicate `mirrorInteriorFromAbove` call from the `if (!hasPhoto)` branch; the unconditional call at the top of the backdrop build already handles both paths.
- Diagnostic log now reports `photo=yes/no` so the user can confirm the source-bitmap path is live.
- Version: `1.9.3`, `versionCode 12`.

---

## 1.9.2 — Photo-anchored watermark, dock spring and identity polish

### Watermark rendering
- **Photo-anchored glass **: the composite's first plate draw now captures the real photograph *before* the plate is painted over it. The optics receive that pre-plate snapshot as backdrop, so the glass refracts the actual scene under the panel instead of the camera's baked plate — no mirroring, no white corners, and the watermark no longer changes the photo's scale.
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