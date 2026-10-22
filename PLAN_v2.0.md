# LiquidFrame v2.0 总体规划

## 目标
一次性解决所有遗留问题：原水印残留、EXIF 缺失、UI 不合理、日志不全、预览不更新

## Phase 1: 全量逆向 MiuiCamera 6.4.000250.2 ✅ 已完成
- apktool decode 完成，8 个 smali 目录
- 逆向结果（源码级确认）：

### 水印管线完整调用链
```
E5/b.smali → new WatermarkRemover() → getWatermarkData(Bitmap, Fe/a, Cc/a, String, I, I, [B) → WatermarkRemover$b
```

### 关键类
| 混淆名 | 真实名 | 位置 | 作用 |
|--------|--------|------|------|
| `Fe/a` | 水印元素树节点 | smali_classes5/Fe/a.smali | 水印渲染上下文，含 id/paint/matrix/rectF/child tree |
| `Fe/a.j()` | composite 方法 | Fe/a.smali L152 | 静态方法，创建输出 Bitmap 并绘制元素树 |
| `Fe/a.b()` | blit 方法 | Fe/a.smali L334 | 将照片 blit 到输出 bitmap |
| `Fe/a.e()` | 获取 Rect | Fe/a.smali L494 | 返回元素在画布上的绝对 Rect |
| `Fe/a.c()` | 子元素查找 | Fe/a.smali L459 | 按 id 查找子元素（如 "watermark_container", "src_image_view", "move_group_1/2"） |
| `Cc/a` | 水印配置 | smali_classes4/Cc/a.smali | 含 watermarkConfig、layout、fonts、路径 |
| `WatermarkRemover` | 水印移除器 | smali_classes4/.../WatermarkRemover.smali | 从照片提取水印区域数据 |
| `WatermarkRemover$b` | 移除结果 | WatermarkRemover$b.smali | 含 4 组 Rect+byte[]（水印区域数据） |
| `WatermarkRemover.getWatermarkData()` | 主入口 | WatermarkRemover.smali L76 | 调用 processInternal/processExternal |
| `E5/b.smali` | 照片保存管线 | E5/b.smali L965 | 调用 getWatermarkData，是照片保存流程中的水印入口 |
| `W0/f.smali` | 水印绘制 | W0/f.smali L1521 | drawWaterMark，处理 waterMarkPngData |
| `t9/f.smali` | 数据处理 | t9/f.smali L128 | 接收 WatermarkRemover$b 处理水印区域 |

### WatermarkRemover$b 数据结构
- `a: Rect` — 第1组区域
- `b: byte[]` — 第1组数据
- `c: Rect` — 第2组区域
- `d: byte[]` — 第2组数据
- `e: Rect` — 第3组区域
- `f: byte[]` — 第3组数据
- `g: Rect` — 第4组区域
- 构造器 `<init>(int, Rect)` — 简单模式（外部水印）
- 构造器 `<init>(Rect, byte[], Rect, byte[], Rect, byte[], Rect)` — 完整模式（内部水印）

### 原水印残留根因（源码级确认）
1. **当前 Hook 点正确**：`Fe/a.j()` 是 composite 方法，`hookCompositeFallback(loader, "Fe.a", "j")` 正确
2. **问题不在 Hook 点，而在 afterHookedMethod 处理方式**：当前代码在 composite **之后** 通过像素扫描尝试擦除白条
3. **Fe/a.b() 是 blit 方法**：将照片 blit 到输出 bitmap，然后后续元素绘制白条背景和水印文字
4. **根本解决方案**：在 `Fe/a.j()` 的 **beforeHookedMethod** 中，拦截水印元素树中绘制白条背景的子元素（如 "watermark_container"），使其直接 return 跳过绘制

### EXIF 提取修复方向
1. `WatermarkRemover.getWatermarkData` 的参数 `Cc/a watermarkConfig` 包含水印配置
2. `Fe/a` 的 `j: String` 字段可能是 layerGroup 标识
3. `E5/b.smali` 调用 getWatermarkData 时传入的参数包含 EXIF 信息
4. **添加 ExifInterface 回退**：从输出 JPEG 文件直接读取 EXIF
5. **Hook E5/b 的水印保存方法**：在调用 getWatermarkData 前拦截参数对象，提取 EXIF

## Phase 2: 全套日志系统 ✅ 已完成
- 新建 LFLogger：分级日志（VERBOSE/DEBUG/INFO/WARN/ERROR）
- 记录 hook 管线每一步：install → composite hook → panel detect → erase → capsule render → text layer
- 记录 composite 全部参数的类型、类名、字段、值
- 记录 EXIF 扫描过程：扫描了哪些类、找到哪些方法、值是多少
- 日志写入 /data/local/tmp/lf_log.txt + 内存镜像
- UI 中添加日志查看页（可复制、可清除）
- 日志文件大小限制提升到 512KB
- 移除速率限制（调试阶段需要全部日志）

## Phase 3: 原水印彻底消除 [首要] ✅ 已完成
- 基于 Phase 1 逆向结果，找到精确 hook 点
- 方案 A+C 组合实现：hook `Fe/a.b()` 在 beforeHookedMethod 中反射清零 `Fe/b.a` 颜色字段，阻止 `Canvas.drawColor()` 调用
- processCapsule eradicate 重写为三级降级：src-rebuild（主路径，逐行从原照片重建）→ strip-fill → over-photo
- 移除无效的 plate-flat / plate-patch 模式
- 在源码级确认白条不再出现

## Phase 4: EXIF/拍摄参数提取修复 [首要] ✅ 已完成
- 基于 Phase 1 逆向结果，定位携带 EXIF 的参数对象类
- 扩展 extractMeta：类型驱动扫描（不只靠方法名）
- 新增 scanNumericFields：基于值域的 BFS 数值字段扫描，不依赖方法名
  - ISO（整数 25-102400）、Aperture（double 0.95-22 或 int*10）、Exposure（1e-6~30 含 ns/µs 转换）、Focal（0.5-1000）
- 集成为 readExifHints → deepExifScan → scanNumericFields 三级回退链

## Phase 5: UI 重构 ✅ 已完成
- 移除 Tab 切换，所有控件直接展示（参考 VitreMark 布局）
- Logo 选择器始终可见，不用点击展开
- 预览在胶囊参数变化时也更新（LaunchedEffect 添加 schedulePreview 调用）
- Glass 页面布局：预览 → 材质参数 → 胶囊参数 → 细节参数，全部平铺
- Home 页面保留核心开关 + 维护
- 新增日志页面（底栏第四个入口，Description 图标）

## Phase 6: 设计/性能/动效优化 ⏳ 待后续迭代
- 预览渲染优化：复用 bitmap，避免每次 copy
- 滑块拖动优化：节流策略
- 页面切换动画
- About 页面动效保留并优化
- 整体视觉一致性

## 执行顺序
1. Phase 1（全量逆向）✅ → 2. Phase 2（日志系统）✅ → 3. Phase 3 + Phase 4（原水印 + EXIF）✅ → 4. Phase 5（UI）✅ → 5. Phase 6（优化）⏳

## 版本目标
v2.0.0（versionCode 35）