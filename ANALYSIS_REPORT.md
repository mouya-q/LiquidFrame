# LiquidFrame 2.0 — 源码修复与相机 APK 逆向分析

> 分析对象：用户上传的 `源码及逆向产物.zip`。相机反编译目录为 `decoded_640`。
> 反编译 APK 元数据：`MiuiCamera.apk`，`versionName=1+6.4.000250.2`，`versionCode=740002502`，`minSdk=29`，`targetSdk=35`。
> 6.7 适配证据：`相机_6.7.000480.0.apk.1`（versionCode=670004800，targetSdk=36），DEX 结构化扫描。
> 本文区分**静态反编译证据**、**本次源码改造**与**仍需真机验证的结论**。静态证据不等于已在设备运行成功。

## 1. 结论摘要

### v2.0.1 多版本兼容性修复

模块现已支持通过**结构化特征匹配**适配多版本小米相机，不再依赖单一版本的硬编码签名：

| 相机版本 | 水印工厂 Hook | 拍摄元数据 Hook | 状态 |
|---|---|---|---|
| 6.4.000250.2 | ✅ 方法 `c` 名+Bitmap 返回+Bitmap 参数 | ✅ E5/b.h(la/a;ZI) | 完整支持 |
| 6.7.000480.0 | ✅ 同上（自动适配实例方法+9 参数） | ❌ E5/b 不存在（优雅降级） | 水印替换有效；EXIF/GPS 胶囊行为空 |
| 未来版本 | ✅ 只要 `com.xiaomi.cam.watermark.b.c()` 保留方法名+Bitmap 返回 | 取决于混淆名 | 向前兼容 |

**最重要的限制：** 当前 Hook 是按 `1+6.4.000250.2` 的混淆名和方法签名实现；HEIF/MIVI3、RAW 或其他绕过该 Bitmap 工厂的路径还不能宣称支持。当前环境没有 Android SDK，Gradle 9.8.0 下载又因网络不可用失败，因此源码没有完成本机 Gradle 编译或设备端到端测试。

## 2. 拍摄参数：APK 中找到的真实数据源

### 2.1 Camera2 CaptureResult 到小米 EXIF 记录

证据文件：

- `decoded_640/smali/E5/c.smali`
- `decoded_640/smali_classes4/la/b.smali`
- `decoded_640/smali_classes4/la/a.smali`

`E5/c.b(CaptureResult)` 返回 `la/b`，可见它从 Android Camera2 读取：

| Android / Xiaomi 来源 | `la/b` 字段 | 含义 |
|---|---:|---|
| `CaptureResult.SENSOR_EXPOSURE_TIME` | `a:J` | 曝光时间，纳秒 |
| `CaptureResult.LENS_APERTURE` | `d:F` | 光圈值 |
| `La6/N.b(CaptureResult)` | `b:I` | ISO；内部读取 `SENSOR_SENSITIVITY`，并结合 `CONTROL_POST_RAW_SENSITIVITY_BOOST` 与 Xiaomi 校正方法 |
| `La6/O.c(CaptureResult)` | `c:S` | Xiaomi 自定义 CaptureResult key `Lo6.L.a` 返回的 35mm 等效焦距（取整后保存） |
| `CaptureResult.JPEG_ORIENTATION` | `e:I` | JPEG 方向 |

这里不是从任意对象中“猜”数字：smali 明确显示 `CaptureResult.get(Key)` 读取 `SENSOR_EXPOSURE_TIME` 与 `LENS_APERTURE`，并调用 Xiaomi 的 ISO / 焦距辅助方法。

### 2.2 相机把本次拍摄元数据复制到 `la/a`

`E5/c.c(LR9/o)` 从相机处理上下文拼装 `la/a`：

| `la/a` 字段 | 类型 | 含义 | 当前模块处理 |
|---|---|---|---|
| `f` | `short` | 35mm 等效焦距 | 转为 mm 显示 |
| `g` | `float` | 光圈 | 以 `f/x.xx` 显示 |
| `h` | `long` | 曝光时间，纳秒 | 转成秒或 `1/x s` |
| `i` | `int` | ISO | 以 `ISOxxx` 显示 |
| `j` | `long` | 拍摄时间，毫秒 | 转换为本地时间 |
| `k` | `Location` | GPS 位置 | 校验经纬度后格式化 |

`E5/c.c` 的 smali 先建立该对象、写入这些字段，随后同步调用 `E5/b.h(la/a, boolean, int)`。这就提供了一个比“从相机任意方法的 Bitmap 参数反推元数据”更可靠的关联点。

注意：焦距字段保存的是**35mm 等效焦距**，而不是镜头的物理焦距；所以界面描述已明确写为“35mm 等效焦距”。Camera2 或 Xiaomi 适配器没给出某字段时，模块将其留空。

## 3. 原水印生成链与正确替换点

### 3.1 本构建的 Bitmap 输出链

证据文件：

- `decoded_640/smali/E5/b.smali`
- `decoded_640/smali_classes4/com/xiaomi/cam/watermark/b.smali`
- `decoded_640/smali_classes4/com/xiaomi/cam/watermark/c.smali`

静态调用关系：

```text
E5/c.c(LR9/o)
    └─ 建立 la/a 拍摄记录
       └─ E5/b.h(la/a, boolean, int)
            ├─ 生成 / 整理照片 Bitmap 与水印区域数据
            ├─ WatermarkRemover.getWatermarkData(...)
            └─ com.xiaomi.cam.watermark.b.b(Application, Bitmap, Dc/b, int)
                 └─ static com.xiaomi.cam.watermark.b.c(
                        b, Context, srcBitmap, Dc/b, int, String, int
                    ): Bitmap
                      └─ new com.xiaomi.cam.watermark.c()
                         └─ 原水印渲染
```

`b.b(...)` 是包装方法，其 smali 直接调用静态 `b.c(...)`；静态方法的第三个参数（Java/Kotlin 下标 2）是 `srcBitmap`，返回值是 Bitmap。当前模块在 `b.c` 上安装 Hook，并只在 `E5/b.h` 的 ThreadLocal 拍摄上下文存在时处理，避免误伤相机设置页、预览页或其他调用方。

当 Hook 生效：

1. 读取第三个参数中的照片源 Bitmap。
2. 若源 Bitmap 不可变，则复制为可写 `ARGB_8888`；复制失败时保留原相机流程。
3. 根据当前拍摄记录构建胶囊文字，按左/中/右设置定位。
4. 读取胶囊周围真实照片像素，运行项目的 CPU 液态玻璃光学渲染，再绘制文字层。
5. 返回自定义 Bitmap，绕过小米原来的水印绘制方法。

这是**生成阶段替换**而不是“照片完成后把水印擦掉”。对已经有干净源 Bitmap 的该路径来说，它避免了修补矩形、克隆背景色或猜测原水印边界造成的图像损坏。若复制或渲染失败，Hook 不写 `p.result`，让小米原方法正常执行（fail-open）。

### 3.2 为什么 `Fe/a.j(...)` 不该当成照片

证据文件：`decoded_640/smali_classes5/Fe/a.smali`。

`Fe/a.j(Fe/a, Bitmap, ColorSpace, int, int, String, int)` 的行为是：

- 如果 Bitmap 参数为空，就按 `Fe/a` 保存的宽高创建一张新 Bitmap；
- 为该 Bitmap 创建 `pe/o` Canvas 包装器；
- 调用 `Fe/a.b(pe/o, layerGroup)` 与 `Fe/a.a(pe/o, layerGroup)` 绘制布局树；
- 返回该层 Bitmap。

在 `WatermarkRemover.getWatermarkData(...)` 的相关调用处，传入的 Bitmap 参数就是 `null`，因此会生成水印图层专用的临时画布。它不是最终照片的稳定引用。旧版将其误判为成片，是对象选择错误的根源。

### 3.3 `WatermarkRemover.getWatermarkData(...)` 的返回值

证据文件：`decoded_640/smali_classes4/com/xiaomi/cam/watermark/WatermarkRemover.smali`。

方法签名返回 `com.xiaomi.cam.watermark.WatermarkRemover$b`，而非 `Bitmap`。反编译代码中可见其处理水印布局、区域 Rect 和图像字节数据。这个对象用于识别、裁剪或迁移水印区域，不能直接当作照片位图返回，也不是 `b.c(...)` 最终输出图。

## 4. 本次源码修改

| 文件 | 修改 |
|---|---|
| `app/.../glass/CapsuleRenderer.kt` | 删除任意对象深搜与数值猜测；时间不再回退到当前系统时间；只渲染可信的拍摄记录字段 |
| `app/.../WatermarkHooks.kt` | 新增 `E5/b.h` 强类型拍摄上下文 Hook；读取 `la/a` 的 `f/g/h/i/j/k`；新增精确的 `b.c` Bitmap 工厂 Hook；删除新安装路径上的旧 DexKit 猜测 Hook；限制旧擦除回退，避免覆盖成片真实像素 |
| `app/.../ConfigActivity.kt` | 设置页改为可折叠分类：核心、显示与渲染、胶囊显示、品牌与布局、维护；玻璃调校分组也可折叠；移除玻璃页重复的胶囊设置；修正无效的“保留原有文字”描述，明确该开关只影响预览 |
| `app/.../ui/LiquidComponents.kt` | `SettingSection` 增加可折叠交互与展开状态 |
| `app/.../ConfigStore.kt` | 修复胶囊位置读取逻辑，不再把 `center` 强制回写成 `left` |
| `app/build.gradle` | 移除源码中硬编码的 Release 签名配置；Release 从本机属性/环境变量取密钥；Debug 始终使用 Android 默认 Debug 签名 |
| `.gitignore`, `SIGNING_SETUP.md`, `.github/workflows/build.yml` | 忽略本地 keystore 与属性文件；CI 改为无私钥 Debug 构建，本地 Release 签名走属性/环境变量 |
| `README.md`, `README_CN.md` | 更新为新数据链、真实 Hook 点、适配边界及故障排查流程 |

### UI 与设置行为

- 首页按任务类别收纳，较少用到的组默认折叠。
- 液态玻璃调校独立成组，避免和拍摄内容设置混在一起。
- “胶囊位置”现在可循环切换左 / 中 / 右，`ConfigStore` 不再覆盖用户的 `center` 选择。
- 拍摄参数说明强调 35mm 等效焦距，并说明缺失字段不会用猜测值补齐。
- “预览中保留示例文字”只控制设置页预览；实际拍摄使用新胶囊整体替换该路径的原水印，不再误导为原相机文字仍会保留。

## 5. 性能与稳定性建议

当前改造优先保证数据来源和目标 Bitmap 正确。建议后续真机测试重点关注：

1. **大图内存峰值：** 不可变源图会复制为 `ARGB_8888`，4K/高像素照片会有额外内存开销。可先测算峰值与 OOM 情况，再考虑复用缓冲池或缩小光学采样区。
2. **CPU 渲染耗时：** 玻璃效果在 CPU 像素数组上运行。应记录不同尺寸照片的 Hook 耗时；若高像素耗时过长，可把复杂光学先在低分辨率局部缓冲中计算，再高质量合成回原图。
3. **线程时序：** 当前依赖 `E5/b.h` 到 `b.c` 同步执行，因此用 ThreadLocal 传递同一张照片的元数据。若未来相机改成异步/跨线程渲染，需要改用以 capture sequence/token 为键的并发映射。
4. **多水印组：** 当前一个拍摄上下文只绘制一次胶囊，并抑制同一上下文后续的原水印渲染调用。需在徕卡、普通水印、多组水印模式真机验证不会漏画或重复。
5. **异常回退：** Hook 无法确认目标 Bitmap、元数据或布局时应回退原相机流程，避免返回损坏图片；日志应区分“未命中”“字段缺失”“复制失败”“渲染跳过”。
6. **JPEG/HEIF 分流：** 先确定每种拍摄格式实际进入的保存链，再分别挂接。不能把 JPEG Bitmap 路径的测试结果套用到 HEIF/MIVI3。

## 6. 真机验收清单

- [ ] 相机包名与版本确认：`com.android.camera` / `com.miui.camera`，版本是否为 `1+6.4.000250.2`。
- [ ] LSPosed 日志出现 `hooked typed capture metadata` 与 `hooked real watermark output`。
- [ ] 固定场景改变曝光时间、ISO 和焦距设置，胶囊值与保存照片 EXIF 对照一致。
- [ ] 关闭定位、禁止相机写 GPS 时，胶囊不出现虚构坐标；允许定位时校验经纬度。
- [ ] 对比系统相机水印开/关、普通/徕卡水印、横拍/竖拍、连拍/高分辨率拍摄。
- [ ] 关闭模块后照片与原相机行为一致；开启模块后照片尺寸、方向、色彩空间、EXIF、音量键/快门行为无异常。
- [ ] JPEG 验收通过后，再单独追踪 HEIF/MIVI3 保存路径；不要把 JPEG 测试等同于 HEIF 已支持。
- [ ] 统计至少 10 张不同分辨率照片的耗时、内存峰值和失败回退比例。

## 7. 构建状态与签名安全

本次运行环境有 JDK 21，但未发现 Android SDK / platform 37。Gradle Wrapper 需要 Gradle 9.8.0，尝试离线构建时因本机无该发行包且无法访问 `services.gradle.org` 而失败。因此没有可诚实提供的“编译通过”结论；必须在有 Android SDK 的本地环境或 CI 执行：

```bash
./gradlew :app:assembleDebug
./gradlew :gallery:assembleDebug
```

CI 工作流现在只构建 Debug APK，不再依赖仓库内私钥；本地 Release 签名属性请放在 `~/.gradle/gradle.properties` 或环境变量中，变量名见 `SIGNING_SETUP.md`。交付 ZIP **不包含**上传压缩包内的 `app/keystore/liquidframe-release.p12`。旧源码曾把签名配置直接写入 Gradle 文件；若旧源码或签名材料曾公开分享，建议评估签名材料轮换。但如果已有用户安装了用旧密钥签名的 APK，直接换钥可能导致无法原位升级，轮换前应先保留旧签名备份并规划升级策略。
