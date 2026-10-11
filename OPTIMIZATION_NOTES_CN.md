# LiquidFrame 1.9.5 — Optimization notes

## Watermark rendering — mirror-only backdrop, plate-overshoot growth

- 用户实拍确认：水印板位于原图下方，板下方没有真实照片像素（只有相机的白色填充）。v1.9.3/v1.9.4 的"composite 参数直取源照片"路线在板区域采到的正是白色填充，不是场景——该路线已移除。背景重建回归 `mirrorInteriorFromAbove`：从板上方的真实照片行向下镜像（板附近唯一可用的真实场景来源）。
- **板溢出生长（白线/白底根治）**：相机烘焙的 plate 比检测到的胶囊更大——顶边发丝线在玻璃上方呈白线，底边软边在下方呈白色涂抹。新增 `growPlateToOvershoot`：从检测矩形向外逐行/列扫描，亮度落在 plate 主色调 ±`CONTENT_LUMA_DELTA` 内且占比 ≥60% 的行/列判为 plate 并吞入；首个照片行/列停止生长。镜像与 plate 清理均作用于生长后的矩形，溢出的白线/白底被场景内容覆盖，玻璃胶囊轮廓仍保持在检测矩形上。
- 生长上限：垂直不超过短边一半（且向上至少保留 1 行照片供镜像采样），水平不超过短边 1.2 倍，并受 crop 边界约束。
- 诊断日志新增 `grow=tN bN lN rN`，报告四个方向的生长量，便于实机核查。
- 移除废弃采集管线：`Frame.photoPixels` 等字段、drawRect 前置捕获、composite 参数位图提取、`renderPanel` 的 `photoBackdrop`/`photo*` 参数。调用点不变。
- Version: `1.9.5`, `versionCode 14`.

---

# LiquidFrame 1.9.2 — Optimization notes

This patch is based on the supplied project archive and source-level comparisons with:

- MeloX-Android slider wrapper and its AndroidLiquidGlass port: <https://github.com/lladlam/MeloX-Android>
- HyperIsland floating navigation bar and background capture: <https://github.com/1812z/HyperIsland>
- AndroidLiquidGlass reference components: <https://github.com/Kyant0/AndroidLiquidGlass>

## Watermark rendering — photo-anchored glass

- Root cause of the three reported defects (white line above the glass, white spot behind it, and the watermark visibly changing photo scale): the optics were refracting the camera's own baked plate, not the photograph. `mirrorInteriorFromAbove` existed only because the plate hid the scene underneath.
- **Fix**: the composite's *first* `drawRect` before-hook now captures the output bitmap region behind the first plate rectangle (plus padding) — at that moment the photo is already blitted but the plate is not, so the crop holds the true scene. The optics receive this pre-plate snapshot as `photoBackdrop` and overwrite the panel interior with it, then refract the real photograph directly.
- Capture is cropped (`PANEL_CROP_PAD_FRACTION = 0.55`, min 32px) so one shot costs a few hundred KB instead of a full 48 MB frame; `frame.bitmap` is stored by the canvas-wrapper after-hook and `absoluteRect` maps the element-local rectangle through the canvas matrix.
- Backdrop build order is now: copy composite → `mirrorInteriorFromAbove` fills the whole plate rectangle (white corners gone) → snapshot overwrites the panel interior with true photo content (no mirroring inside the glass). `hasPhoto` fallback keeps the old mirror path alive when capture fails or is absent.
- The mirror call is now exactly one; the previous `if (!hasPhoto)` duplicate branch was removed.

## Dock spring and identity

- Press scale `spring(0.62, 420)` and selection travel `spring(0.62, 380)`: lower damping than 1.9.1 gives a slight overshoot on settle — closer to AndroidLiquidGlass's liquid feel.
- Indicator press feedback is now scale + sink: `translationY = (1 - selectionScale) * 22dp` dips the capsule ~1.5dp while pressed.
- Switch enabled track is now a white frost (`0xFFE8E8ED` dark / `White` light) instead of iOS green, matching the AndroidLiquidGlass reference.
- App identity renamed to `liquidFrame` (launcher label, activity title, home header, toggle copy, About hero/module copy).
- New home-page restart-camera button: force-stops `com.android.camera` / `com.miui.camera` so the new watermark path applies immediately.
- Version: `1.9.2`, `versionCode 11`.

---

# LiquidFrame 1.9.1 — Optimization notes

This patch is based on the supplied project archive and source-level comparisons with:

- MeloX-Android slider wrapper and its AndroidLiquidGlass port: <https://github.com/lladlam/MeloX-Android>
- HyperIsland floating navigation bar and background capture: <https://github.com/1812z/HyperIsland>
- AndroidLiquidGlass reference components: <https://github.com/Kyant0/AndroidLiquidGlass>

## Interaction and glass UI

- Replaced the full-width bottom slab with a narrow floating capsule dock, one glass base and one moving selection lens. The indicator geometry now follows the actual padded tab widths; navigation-bar insets are respected.
- Removed the permanently thick white veil from the dock, slider thumb and switch thumb. Frost is low-opacity and the glass remains translucent at rest.
- Gave sliders a 36dp hit target, tap-to-seek and horizontal-drag handling across the track. Horizontal movement is consumed only after horizontal touch slop; a vertical gesture is left available to the page scroller.
- Fixed the slider's stale-state observation: the animation now observes the latest Compose value rather than the first `Float` captured by its initial effect.
- Added a separate click target to the liquid switch, while keeping horizontal drag-to-set and external state reconciliation.
- The screen backdrop now records a subtle, static multi-tone field rather than a single flat color. This gives the lens a real gradient to refract without continuous background animation.
- Gated AGSL-based glass rendering on Android 13+ and kept the regular Compose fallback for older systems or unavailable hardware acceleration.

## Watermark rendering and reliability

- Crop-localized the label protection mask. This avoids a full-frame `FloatArray` for a small watermark panel.
- Expanded the crop based on refraction displacement and the blur kernel, preventing high refraction values from sampling a crop boundary.
- Kept the blurred material continuous across the panel interior, feathering only the antialiased outer silhouette instead of introducing a sharp band inside the glass.
- Clamped non-finite and out-of-range height, refraction and blur values loaded from configuration.
- Kept anti-aliased text protection fractional rather than applying a hard glyph/background threshold.
- Refreshes the camera-side configuration before the early disabled return, so toggling the module on does not require restarting a camera process that was started while it was disabled.
- Config sync status now reports camera-side sync failure honestly instead of inferring success from the app's private config file alone.
- Removed a full-size bitmap pixel allocation from the unused scene-luminance helper.

## Identity and project hygiene

- Replaced the previous bitmap launcher art with a transparent VectorDrawable: only the floating glass panes are drawn; no baked square, no white outer border, and no baked corner mask.
- Added adaptive icon resources and a separate API 33+ monochrome layer for themed icons.
- Removed the stale Gallery PNG launcher icon and unified the app/Gallery icon reference.
- Bumped the app version to `1.9.1 (10)`.

## Validation

- `LiquidGlassOptics.kt`, `GlassParams.kt`, and `PanelRect.kt` compile with the local Kotlin compiler, and a synthetic watermark-panel render smoke test passes.
- XML resources and manifests are checked for parse errors; icon references are checked for stale bitmap paths.
- Full Android Gradle compilation could not be completed in the current environment because it cannot resolve `services.gradle.org` to download the configured Gradle distribution. This is not a successful APK build claim; please run both app and Gallery build tasks in a networked Android/Gradle environment.

## Local verification

```bash
./gradlew :app:assembleDebug :gallery:assembleDebug --stacktrace --no-daemon
```