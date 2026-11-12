# LiquidFrame

[English](README.md) | [简体中文](README_CN.md)

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://www.android.com/)
[![LSPosed](https://img.shields.io/badge/LSPosed-Module-6F42C1)](https://github.com/LSPosed/LSPosed)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/mouya-q/LiquidFrame/build.yml?label=Build)](../../actions)

LSPosed 模块：将小米相机照片水印的背景替换为 Apple 风格**液态玻璃**材质，附带 HyperOS（miuix）应用用于设计与调校该材质

> 独立项目，与小米、徕卡、Apple 无任何关联、背书或支持

---

## 目录

- [功能](#功能)
- [截图](#截图)
- [仓库结构](#仓库结构)
- [环境要求](#环境要求)
- [安装](#安装)
- [配置](#配置)
- [材质](#材质)
  - [渲染方式](#渲染方式)
  - [为何需要伴生应用](#为何需要伴生应用)
- [Hook 原理](#hook-原理)
- [构建](#构建)
- [故障排查](#故障排查)
- [限制与已知问题](#限制与已知问题)
- [致谢与许可](#致谢与许可)

---

## 功能

小米相机会在照片输出链路中生成水印。LiquidFrame 不在预览层做“看起来替换了”的假处理：拍摄时先从 YUV→Bitmap 源帧留存干净像素，再记录小米水印工厂的**确切合成输出对象**；在该对象进入 Bitmap→帧封装前，只替换这张带原厂水印的合成图，恢复底图并绘制一次液态玻璃胶囊。相机同时保留的原图分支不会被改写。

这里的"液态玻璃"指不止于模糊的表面：

| 属性 | 贡献 |
| --- | --- |
| **折射（透镜）** | 背景采样偏移向轮廓方向递增，面板弯曲并放大其后场景，而非单纯雾化 |
| **色散** | 折射分裂为七采样光谱，沿对角线反对称——对边上呈棱镜效果，而非均匀色边 |
| **鲜活度** | 对捕获背景做饱和度提升。真实玻璃会聚拢色彩；缺失时材质在饱和内容旁读作灰色雾霭 |
| **镜面边缘** | 圆角边缘的半球法线，双向受光，产生随光线旋转的两个对向高光 |
| **内阴影** | 柔和的贴边阴影，使面板读作实体而非贴纸 |

当前验证重点为 Xiaomi 17 相机 `6.7.000480.0` 的拍摄链路，并保留 6.4 时代的结构/字面回退；未知版本采用 DEX 结构发现并在未验证时 fail-open。

## 截图

伴生应用将材质渲染在刻意严苛的背景上——饱和色块、硬边、细颗粒——使透镜、色散与边缘高光对着有结构可弯曲的内容受检。下图是材质算法的离设备示意渲染，用于比较面板基线、磨砂和折射效果；不是本次水印 Hook 的实机输出截图。

| 基线（面板原样） | 磨砂（模糊 + 着色，无透镜） | 液态玻璃（加入透镜） |
| --- | --- | --- |
| ![baseline](docs/01-watermark-only-crop.png) | ![frosted](docs/02-frosted-only-crop.png) | ![lens](docs/03-liquid-glass-crop.png) |

高色散特写——光谱分裂沿对角线反对称，对边上读作棱镜而非均匀色边：

![dispersion](docs/04-high-dispersion-crop.png)

> 这些是材质自身光学的渲染图，由 `docs/RenderSamples.kt` 在合成场景上离设备生成（不依赖 `./gradlew`，用独立 Kotlin 编译器运行——见 `docs/renders.txt`）。它们是实现的对照基准，非设备截图

## 仓库结构

```
app/                    LSPosed 模块（水印 Hook）
  src/main/java/com/mouya/LiquidFrame/
    WatermarkHooks.kt     Hook 安装与合成管线
    DexKitHelper.kt       DEX 结构发现、反射复核与晚到重试
    ConfigStore.kt        模块与相机进程共享的设置
    GlassConfig.kt        相机侧的设置视图
    ConfigActivity.kt     设置 UI：Compose + 玻璃质感，实时预览
    dex/DexScanner.kt     轻量 DEX 读取器：识别拍摄记录、接收方法与最终帧封装器
    glass/                光学引擎（无 Android 依赖）
      LiquidGlassOptics.kt  材质的 CPU 移植
      GlassParams.kt        材质中的每个量
      PanelScan.kt          从像素定位水印面板
gallery/                HyperOS（miuix）应用：实时组件形态的材质
  src/main/java/com/mouya/LiquidFrame/gallery/
    GlassNavigationBar / LiquidBackdrop / GalleryApp
    glass/                实时（GPU）材质
      Lens.kt               AGSL 圆角矩形折射透镜
      Glass.kt              GlassSpec 与 Modifier.liquidGlass
    screens/              材质、控件与浮层页面
docs/                   参考渲染图
```

## 环境要求

**模块（`app/`）**

- Android 8.0+（API 26）。材质在 CPU 上对整数像素缓冲运行，无 `RuntimeShader` 门槛；设置 UI 出于同样理由使用 Jetpack Compose 与手绘玻璃表面
- 小米相机 `com.android.camera` 或 `com.miui.camera`
- LSPosed（或其他 Xposed API 82+ 兼容框架）
- Root 与可写的 `/data/local/tmp`——设置与日志存于此

**伴生应用（`gallery/`）**

- Android 13+（API 33）。`miuix-blur` 硬性要求此门槛
- 无需 Root 与 LSPosed，普通应用

## 安装

1. 构建或下载 `app-debug.apk` 并安装
2. 在 LSPosed 中启用模块，**作用域设为设备实际安装的小米相机包名**（`com.android.camera` 或 `com.miui.camera`）
3. 强停小米相机后重开
4. 开启水印拍照

确认 Hook 已安装：查看 `capture context`、`YUV source adapter`、`watermark factory` 和 `final capture encoder` 日志。成功拍摄应出现 `final capture encoder: replaced composed stock layer with glass`。如果结构发现没有得到唯一且可复核的目标，模块保留小米原图，不对不确定的 Bitmap 做擦除。

## 配置

从桌面打开模块。设置先保存在应用私有目录，并镜像同步到相机进程可读取的 `/data/local/tmp/lf_config.txt`。共享文件写入可能需要 Root 授权；设置页会显示实际同步状态。成功同步后，下一次拍摄即可应用，无需重启相机。

设置页用与模块相同的光学渲染**实时预览**，无需拍照即可评判与调校材质。设置按「核心、显示与渲染、拍摄信息、品牌标识、胶囊布局、维护」折叠分组；Logo、Logo 位置与胶囊水平位置均改为下拉菜单，避免把选项常驻铺满页面。预览同步使用约 91% 照片宽度、约 13% 短边高度、约 14% 短边底部留白的胶囊比例，默认 Apple 标识与参考图相同。控件：

| 设置 | 效果 |
| --- | --- |
| 启用 | 总开关 |
| 折射带高度 | 沿轮廓的折射带厚度。过小读作发丝线；过大把整个面板扭曲成鱼眼 |
| 折射强度 | 轮廓处的峰值位移。有效范围约为折射带高度的 1–3 倍 |
| 背景模糊 | 捕获背景的磨砂。保持低值：重模糊会摧毁透镜本要弯曲的细节 |
| 内部提亮 | 面板全域的加性提亮。新胶囊文字的可读性旋钮 |
| 着色 | 覆盖在折射背景上的着色强度 |
| 边缘高光 | 镜面边缘强度 |
| 内阴影 | 贴边阴影 |
| 高光方向 | 边缘光方向，单位为度 |
| 立体感 | 将表面法线向中心倾斜，使玻璃读作穹顶 |
| 色散 | 光谱分裂强度；`0` 为关闭 |
| 自适应明暗 | 按场景平均亮度缩放着色、罩纱与边缘 |
| 拍摄元数据 | 从本次拍摄的强类型记录中重建胶囊；字段缺失时不猜测 |

"恢复默认参数"还原出厂值；"复制诊断日志"将最近运行诊断放入剪贴板

## 材质

### 渲染方式

光学是 miuix 生态参考渲染器（Apache-2.0，归属与上游链接记录于 `NOTICE` 文件）的移植。同一套数学存在于两个后端，因其必须运行的两处约束不同：

- **`app/glass/LiquidGlassOptics.kt`**——整数像素缓冲上的 CPU 实现。小米相机在产出 JPEG 的过程中合成水印：没有硬件加速画布，也没有可采样的实时视图层级，材质施加于成品位图。此文件**无任何 Android 依赖**，这正是光学可离设备测试的原因
- **`gallery/glass/Lens.kt`**——AGSL `RuntimeShader`，经 miuix 背景引擎作为实时 `RenderEffect` 链应用。伴生应用的实时路径

两者保留参考自身的数学而非近似：

- 符号距离场及其梯度为参考的 `sdRoundedRect` / `gradSdRoundedRect`
- 位移剖面为 `circleMap(1 - -sd / refractionHeight)`——单位圆的矢高，而非线性斜坡——乘以负值使背景被向内拉动，面板放大其后内容
- 表面法线为 `normalize(gradSdRoundedRect(c, halfSize, min(1.5r, minHalf)) + depth * normalize(c))`；`1.5 ×` 系数来自参考自身：引入它的上游 commit 唯一目的就是匹配 Apple 的渲染
- 色散为参考的七采样光谱与精确通道除数（`1/3.5` 主导，纯红蓝 `1/3.0`，串扰 `1/7.0`）
- 效果顺序按文档：**颜色滤镜 → 模糊 → 透镜**

参考中以*绘制操作*表达的两项被解析建模，因为着色器没有描边或模糊掩码可依赖：

- **边缘高光**是参考的模糊白描边，结合其 `Default` 高光着色器的方向加权
- **内阴影**是其记录图层——形状填充、偏移形状清除、再模糊

默认比例按参考项目自身的 Apple 对比图校准：其中 300 px 玻璃使用 20 px 折射带与 60 px 位移

### 为何需要伴生应用

材质的实时形态无法注入小米相机。本次检查的相机 DEX 是**经典 View 应用**：未发现 `androidx/compose` 运行时引用（存在 `Lmiux/...` View 类）。向相机进程托管 Compose UI 意味着向其塞入整个 Compose 运行时

因此项目拆分：模块在相机拍摄流水线的照片源 Bitmap 上用 CPU 施加材质，伴生应用在 miuix 组件上实时承载——包括一个边缘高光可见地弯曲其后飘过内容的悬浮底栏

伴生应用基于 [`miuix`](https://github.com/compose-miuix-ui/miuix)（小米开源设计系统，Apache-2.0）。`miuix-blur` 提供背景引擎——graphics-layer 捕获、模糊级联、`runtimeShaderEffect` 扩展点——以及 `buildBloomStrokeShader`，正是此材质需要的镜面边缘模型。miuix 刻意留给应用的是**透镜**，即 `gallery/glass/` 所提供者

## Hook 原理

当前 Hook 的主验证目标是 **Xiaomi 17 相机 `6.7.000480.0`**。ZIP 内提供的是该版本 APK 的反编译索引与 DEX 类/方法文本，而非可直接安装的 APK 二进制。实现同时保留 6.4 时代的旧签名回退；对其他版本则按字段、方法参数、返回包装类型和调用关系进行结构扫描，不仅依赖混淆类名。

### 真实拍摄参数链

```text
Xiaomi Camera 6.7 capture path
  W8/d.f(Ki/a, boolean, int) -> Ki/f
        │
        ├─ Ki/a typed capture record
        │    f:S  35mm-equivalent focal length
        │    g:F  aperture
        │    h:J  exposure time (ns)
        │    i:I  ISO
        │    l:J  capture timestamp (ms)
        │    m:Location  capture GPS
        │
        ├─ YuvUtils.I420ToBitmap(Bitmap, ...)
        │    └─ snapshot clean source pixels before scaling/recycling
        │
        ├─ com.xiaomi.cam.watermark.a.c(...)
        │    └─ com.xiaomi.cam.watermark.b.c(...)
        │         └─ returns Xiaomi's composed stock-watermark Bitmap
        │
        └─ Mi/b.c(I, Bitmap) / Mi/b.b(Bitmap) / Mi/b.a(Bitmap)
             └─ Bitmap -> Ki/f frame wrapper
                  └─ restore clean pixels + render glass exactly once
```

字段顺序由 `DexScan.recordPlan()` 从声明字段类型序列识别，而不是固定读取 obfuscation 字母。Xiaomi 的 `Ki/b.toString()` 明确将对应短整型字段命名为 `mFocalLength35mm`；因此水印中的 `100mm` 表示 35mm 等效焦距，不应误解为镜头的物理焦距。光圈来自 `F`，曝光时间以纳秒存储并转换为秒，ISO 来自 `I`，拍摄时间按 Unix 毫秒解释，GPS 只接受本次记录中的 `Location`。缺失或越界的字段留空，不使用系统当前时间、默认 ISO 或虚构参数补齐。

### 为什么预览正常、成片却被原水印盖住

相机的选择预览和静态照片封装不是同一个渲染阶段。旧实现把玻璃绘制到水印工厂的输入/中间 Bitmap，但小米随后还会执行 `a.c → b.c` 的原厂水印合成；新胶囊于是处在原厂水印下面，刚拍完的临时预览可能正常，最后保存的 JPEG 却重新出现原厂文字/Logo。

本次改造分成三处：

1. `YuvUtils.I420ToBitmap` 返回后、相机缩放或回收源 Bitmap 前，保存原图像素。普通尺寸保存完整副本；超大图只保存底部恢复带，并在 64 MiB 上限内自适应降采样。
2. 水印工厂 Hook 不再在拍摄态提前画玻璃，而是在 `afterHookedMethod` 中记录它返回的确切合成 Bitmap 对象。
3. DEX 扫描定位 `Mi/b` 中的 Bitmap→`Ki/f` 静态封装方法。编码前只接受与工厂返回对象**同一实例**的参数，恢复清洁图像像素后再画一次新胶囊。相机的原图/备用图分支因对象身份不同而保持不变。

因此这不是在最终照片上猜一个矩形盖住原水印，也不是对所有 Bitmap 盲目绘制。对于无法确认“源图—合成图—帧封装”关系的新版本，模块会保留原相机结果并输出诊断日志，避免把照片内容或原图副本破坏掉。

### 跨版本适配与边界

- 目标相机可为 `com.android.camera` 或 `com.miui.camera`；DEX 扫描会检查 base APK 和安装的 split APK。
- 拍摄记录按 `S,F,J,I` + 后续时间 `J` + `Location` 的类型组合识别；拍摄入口记录参数索引，允许未来版本调整参数位置。
- 最终封装器按包装对象的字段结构、构造器参数、多个静态 Bitmap 适配器，以及同一 owner 同时消费拍摄记录/小米 watermark 类型的关系进行匹配；反射阶段会再次复核签名。
- 首次扫描完成后会重试 Hook，避免 Application 初始化时 DEX 扫描尚未结束导致漏装。
- 6.7 主链路已依据本次反编译文本定位；6.4 旧路径有兼容回退。其他版本尚不能宣称全部兼容，未知结构会 fail-open。
- 当前处理范围是已识别的 Bitmap/JPEG 封装支路。HEIF/MIVI3、RAW、Live Photo 或直接写入字节流的其它分支仍需逐个相机版本验证。

## 构建

要求：JDK 21 与装有 platform `android-37` 的 Android SDK

```bash
./gradlew assembleDebug
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk          # LSPosed 模块
gallery/build/outputs/apk/debug/gallery-debug.apk  # 伴生应用
```

无外部密钥时，debug 使用本机 Android debug key；release 保持未签名。正式分发请使用自己的外部签名密钥，具体配置见 `SIGNING_SETUP.md`。

工具链：Android Gradle Plugin **9.4.1**、Kotlin **2.4.20**、Gradle **9.8.0**。`miuix 0.9.4 / Compose 1.12` 依赖链拒绝被更旧工具链消费（其 AAR 元数据直接拒绝），门槛因此在此。AGP 9 自带 Kotlin，故刻意不应用 `org.jetbrains.kotlin.android`；Compose 编译器经 `org.jetbrains.kotlin.plugin.compose` 单独应用

CI 会构建并校验 `:app` 与 `:gallery` 的 debug APK。Release 签名为外部密钥可选项，详见 `SIGNING_SETUP.md`。

## 故障排查

**原水印仍在成片上。** 检查 `YUV source adapter hooked`、`watermark factory hooked`、`final capture encoder hooked` 是否出现；拍摄时应再出现 `replaced composed stock layer with glass`。如果只显示 encoder 未命中，通常是当前模式走了另一条图片封装支路；模块会保留原相机图，不对错误 Bitmap 擦除。

**缺少 YUV adapter。** 某些版本可能更改了 `YuvUtils` 包名/转换方法。此时模块会尝试从水印工厂输入留存源图；若尺寸或身份不能核实，宁可不做擦除。请附相机版本与诊断日志。

**胶囊参数缺失。** 这是 fail-closed 设计：字段未从本次拍摄记录取得就不显示。请将胶囊与照片 EXIF 对比，并记录拍摄模式、格式（JPEG/HEIF）和相机版本。

**照片异常或拍摄变慢。** 关闭模块总开关以恢复原相机渲染路径，并提交原图、模块日志和相机版本；像素诊断请使用原始照片文件，不要用截图替代。

**伴生应用无玻璃。** RuntimeShader 需要 API 33+。低于此版本按设计绘制无材质版本而非崩溃。

## 限制与已知问题

- **主验证构建为 Xiaomi 17 Camera `6.7.000480.0`。** 同时保留 6.4 旧路径和跨版本结构扫描，但尚需更多真机/版本矩阵验证。
- **Bitmap 水印路径优先。** HEIF/MIVI3、RAW 或不经过当前 Bitmap→帧封装结构的处理路径尚未验证，不能保证这些格式已替换原水印。
- **尚未在当前运行环境完成 Android APK 构建和小米 17 真机验证。** 当前容器没有 Android SDK，Gradle Wrapper 下载亦因网络不可用失败。因此交付是已做静态检查的源码包，不是可直接安装的 APK。
- **整体替换原水印。** 原水印的文字和 Logo 不会保留；新胶囊显示本次拍摄记录中可用的字段。
- **部分拍摄信息可能缺失。** Camera2 设备能力、模式、缓存或相机内部适配器可能不给出某项 CaptureResult；程序会省略字段，不填充猜测值。
- **需要 Root 与 LSPosed。** 修改相机进程输出依赖 Xposed Hook。

## 致谢与许可

- 全部第三方归属、许可与上游链接统一收录于 `NOTICE` 文件
- 液态玻璃光学改编自一个 Apache-2.0 参考渲染器（见 `NOTICE`）。参考的着色器源码、常量与 `1.5 ×` 梯度系数按原样使用；CPU 移植与解析式边缘/内阴影模型属本项目
- 伴生应用基于 [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)（Apache-2.0），小米设计系统。其模糊引擎提供背景捕获与镜面边缘模型
- `gallery/glass/` 中的 `lens()` 与 `vibrancy()` 效果改编自 miuix 自带示例实现（`example/shared/.../component/liquid/`），同为 Apache-2.0

所有改编文件的头部注释统一指向 `NOTICE`，完整上游归属与许可均记录于该文件

本项目以 Apache License 2.0 许可——见 [LICENSE](LICENSE)