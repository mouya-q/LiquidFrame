# Changelog

## 1.2.0 — UI / Runtime polish

This pass focuses on turning LiquidFrame from a tuning prototype into a small, system-like product.

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
