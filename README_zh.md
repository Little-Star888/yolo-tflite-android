[English](README.md) | **中文**

<div align="center">

# YOLO-TFLite-Android

**Android 端 YOLO 目标检测推理框架**

基于 [LiteRT 2.1.4](https://ai.google.dev/edge/litert)（原 TensorFlow Lite）构建的高性能 Android 推理应用，支持 **YOLO 全数字系列**（YOLOv5 / v8 / v10 / v11 / v12 / YOLO26），支持 NPU / GPU / CPU 多加速器，5 种视觉任务，4 种检测模式。

[![Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/Android%20API-31%2B-brightgreen.svg)](https://developer.android.com/about/versions/12)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Compose-2026.04.01-blue.svg)](https://developer.android.com/compose)
[![LiteRT](https://img.shields.io/badge/LiteRT-2.1.4-green.svg)](https://ai.google.dev/edge/litert)
[![arm64](https://img.shields.io/badge/ABI-arm64--v8a-orange.svg)](https://developer.android.com/ndk/guides/abis)

> **本项目仅供学习交流使用**，旨在探索 Android 端侧 AI 推理的技术方案和最佳实践。欢迎通过 [Issues](../../issues) 或 [Discussions](../../discussions) 交流。

</div>

---

## 目录

- [功能概览](#功能概览)
- [界面预览](#界面预览)
- [快速开始](#快速开始)
- [支持的视觉任务与检测模式](#支持的视觉任务与检测模式)
- [加速器与管线](#加速器与管线)
- [模型管理](#模型管理)
- [TFLite 模型转换](#tflite-模型转换)
- [AOT 预编译](#aot-预编译)
- [推理配置](#推理配置)
- [编译环境](#编译环境)
- [项目结构](#项目结构)
- [已知限制](#已知限制)
- [许可证](#许可证)
- [致谢](#致谢)

---

## 功能概览

| 特性 | 说明 |
|------|------|
| **多任务支持** | 目标检测、关键点检测、实例分割、图像分类、旋转框检测 |
| **多加速器** | NPU（高通 Hexagon / MediaTek）、GPU（OpenCL / OpenGL）、CPU（XNNPack） |
| **零拷贝管线** | GL Compute Shader + BLOB AHardwareBuffer 直通 NPU（仅 MTK/Tensor） |
| **GPU 预处理** | OpenGL ES 3.0 letterbox + 归一化，释放 CPU 负载 |
| **AOT 预编译** | NPU 提前编译，跳过 JIT 延迟，首次推理即达最优性能 |
| **双推理后端** | Native C++ JNI（推荐）和 Java/Kotlin，灵活切换 |
| **多模型精度** | INT8 / FLOAT16 / FLOAT32 |
| **灵活模型来源** | assets 内置 / 本地导入（目录或 ZIP）/ 远程下载，互不冲突 |
| **Material 3 UI** | Jetpack Compose 构建 |

> **⚠️ 重要**：仅 **YOLO26 系列**支持端到端（End-to-End）推理模式，且端到端模式**仅支持 CPU**，不支持 GPU / NPU 加速。其他 YOLO 系列仅支持传统模式。

---

## 界面预览
<div align="center">
  <img src="docs/demo.gif" width="320" alt="App Demo"/>
</div>
---

## 快速开始

### 方式一：Android Studio（推荐）

1. 安装 [Android Studio 最新版本](https://developer.android.com/studio)，打开本项目
2. 在 `local.properties` 中配置路径与目标 SoC：

```properties
# Android Studio 通常会自动生成此行
sdk.dir=/path/to/android-sdk
# 可选，未配置时使用 Android Studio 内置 JDK
org.gradle.java.home=/path/to/jdk-21
# 可选：sm8450 / sm8550 / sm8650 / sm8750 / sm8850 / qualcomm（所有高通，默认）/ mediatek
targetSoc=sm8850
```

3. **（仅使用 高通NPU 时需要）** 下载 QNN 运行时库（约 1-2 GB）：

```bash
cd litert_npu_runtime_libraries
# Windows
.\fetch_qualcomm_library.ps1
# Linux / macOS
./fetch_qualcomm_library.sh
```

> 仅使用 GPU / CPU 推理时可跳过此步骤。如只需特定 SoC，可编辑脚本中的 `QnnVersions` 数组。

4. 点击 Run，AS 会自动安装 base APK + 对应的 QNN split APK。

---

### 方式二：命令行

项目提供隔离环境脚本，所有依赖下载至 `.deps/` 目录，不影响宿主机环境：

```bash
# Windows（二选一）
.\setup-isolated-env-aria2.ps1    # aria2 多线程加速，推荐
.\setup-isolated-env-surge.ps1    # 使用surge下载工具

# Linux / macOS
chmod +x setup-isolated-env.sh && ./setup-isolated-env.sh
```

完成后下载 Qualcomm QNN 运行时（步骤同上），然后构建安装：

```bash
# Windows
.\gradlew.bat installLocalDebug

# Linux / macOS
./gradlew installLocalDebug
```

> **Tips**：手机需通过 USB 连接电脑并开启 **USB 调试** 或 **无线调试**，`adb devices` 能看到设备。`installLocalDebug` 会自动调用 `adb install-multiple` 安装 base APK + 运行时 split APK。

> 首次构建会自动下载 Gradle、LiteRT C++ SDK 等依赖。如遇 CMake / C++ 编译错误，尝试删除 `detector/.cxx/` 后重新构建。
>
> 如果本地开启了代理，需在 `gradle.properties` 中取消代理配置的注释并填入实际端口：
> ```properties
> systemProp.http.proxyHost=127.0.0.1
> systemProp.http.proxyPort=你的代理端口
> systemProp.https.proxyHost=127.0.0.1
> systemProp.https.proxyPort=你的代理端口
> ```

---

## 支持的视觉任务与检测模式

### 视觉任务

| 任务类型 | 默认输入尺寸 |
|----------|-------------|
| 目标检测（Object Detection） | 640×640 |
| 关键点检测（Keypoint Detection） | 640×640 |
| 实例分割（Instance Segmentation） | 640×640 |
| 图像分类（Image Classification） | 224×224 |
| 旋转框检测（Oriented BBox Detection） | 1024×1024 |

> 自定义训练模型**必须按对应任务的默认尺寸**训练和导出，否则无法正常使用。

### 检测模式

| 模式 | 说明 |
|------|------|
| 相机拍摄识别 | 实时检测 + 拍照检测 |
| 单张图片识别 | 从相册选择单张图片 |
| 图片目录识别 | 批量检测目录下所有图片 |
| 本地视频识别 | 逐帧检测本地视频 |

---

## 加速器与管线

### 加速器兼容性

| 模型精度 | CPU | GPU | NPU |
|----------|:---:|:---:|:---:|
| INT8 | ✅ | ✅ | ❌ |
| FLOAT16 | ✅ | ✅ | ✅ |
| FLOAT32 | ✅ | ✅ | ✅（内部转为 FP16） |
| 端到端（仅 YOLO26） | ✅ | ❌ | ❌ |

> NPU 推荐使用 FLOAT16 模型。FLOAT32 在 NPU 上会被内部转为 FLOAT16，效果和体积等同，建议直接导出 FLOAT16 以减少文件体积。

### 支持的 NPU 设备

| 平台 | SoC | 芯片 | 最低系统 |
|------|-----|------|---------|
| **Qualcomm** | SM8450 | Snapdragon 8 Gen 1 | Android 12+ |
| | SM8550 | Snapdragon 8 Gen 2 | Android 12+ |
| | SM8650 | Snapdragon 8 Gen 3 | Android 12+ |
| | SM8750 | Snapdragon 8 Elite | Android 12+ |
| | SM8850 | Snapdragon 8 Elite Gen 5 | Android 12+ |
| **MediaTek** | MT6878 | Dimensity 7300 | **仅 Android 15** |
| | MT6897 | Dimensity 8300 | **仅 Android 15** |
| | MT6983 | Dimensity 9000 | **仅 Android 15** |
| | MT6985 | Dimensity 9200 | **仅 Android 15** |
| | MT6989 | Dimensity 9300 | **仅 Android 15** |
| | MT6991 | Dimensity 9400 | **仅 Android 15** |
| **Google** | Tensor | Pixel 6/7 | ⚠️ 代码已实现，未经真机测试 |

### 管线策略

应用根据硬件能力自动选择，也可手动切换：

| 策略 | 显示名 | 说明 | 适用平台 |
|------|--------|------|---------|
| GL_ZEROCOPY | 极速 | GPU → NPU 零拷贝，延迟最低 | MTK / Tensor NPU |
| GL_TRANSIT | 加速 | GPU 预处理 + letterbox 归一化 | 所有平台 |
| CPU_PIPELINE | 兼容 | 纯 CPU 预处理，最稳定 | 所有平台 |

> 高通 NPU 使用 FastRPC buffer，不支持 GL 零拷贝路径，最高可用 GL_TRANSIT。

---

## 模型管理

### 获取模型

本项目**不内置模型文件**，支持以下三种来源（互不冲突）：

| 来源 | 说明 |
|------|------|
| **内置（Built-in）** | 将 `.tflite` 模型按目录规范放入 `app/src/main/assets/`，随 APK 打包 |
| **本地导入（Local）** | 通过应用内 SAF 选择模型目录或 ZIP 压缩包 |
| **远程下载（Remote）** | 应用内从 HTTP 服务器下载，支持断点续传，自动解压 |

### 目录结构规范

```
{taskType}/tflite/{packageName}/
├── label.txt               # 标签文件（必须）
└── models/
    └── {size}/             # n / s / m / l / x
        ├── model.tflite
        └── aot/            # AOT 预编译模型（可选）
            ├── model_Qualcomm_SM8650.tflite
            └── ...
```

- `taskType`：`detection` / `keypoint` / `segmentation` / `classification` / `oriented_bbox`
- `packageName`：格式 `{base}[-{task}]`，如 `yolo26n`、`yolo26-pose`
- `size`：`n`=Nano、`s`=Small、`m`=Medium、`l`=Large、`x`=XLarge

相同 `packageName` 的内置 / 本地 / 远程模型通过复合 Key 隔离，互不冲突。

---

## TFLite 模型转换

使用 [Ultralytics](https://github.com/ultralytics/ultralytics) 工具导出，核心参数：

| 参数 | 说明 |
|------|------|
| `format="tflite"` | 导出格式 |
| `imgsz` | 输入尺寸，须与任务默认尺寸一致 |
| `half=True` | 导出 FLOAT16 |
| `int8=True` | 导出 INT8（需 `data` 指定校准集） |
| `end2end=False` | **YOLO26 必须设置**，否则导出端到端模式，只能 CPU 推理 |

```python
from ultralytics import YOLO

model = YOLO("yolo26n.pt")

# FLOAT16，支持 GPU / NPU（推荐）
model.export(format="tflite", imgsz=640, end2end=False, half=True)

# INT8 量化
model.export(format="tflite", imgsz=640, end2end=False, int8=True, data="coco8.yaml")

# 端到端模式（仅 CPU）
model.export(format="tflite", imgsz=640)
```

**不同任务类型：**

```python
model = YOLO("yolo26n-pose.pt")   # 关键点，imgsz=640
model = YOLO("yolo26n-seg.pt")    # 分割，imgsz=640
model = YOLO("yolo26n-cls.pt")    # 分类，imgsz=224，无需 end2end
model = YOLO("yolo26n-obb.pt")    # 旋转框，imgsz=1024
```

**输出 Shape 说明：**

| 模式 | 输出 Shape | 示例 |
|------|-----------|------|
| 端到端（含 NMS） | `(1, max_det, features)`，features < max_det | `(1, 300, 6)` |
| 传统模式（需后处理） | `(1, channels, candidates)`，candidates > channels | `(1, 84, 8400)` |

应用通过输出 Shape 自动检测推理模式，无需手动配置。

---

## AOT 预编译

AOT 可提前编译 NPU 模型，消除首次推理的 JIT 延迟。

### 编译脚本

```bash
pip install "ai-edge-litert>=2.1.4" "ai-edge-litert-sdk-qualcomm>=0.2.0"

# 编译所有内置模型
python aot_compile.py

# 指定 SoC
python aot_compile.py --soc SM8650

# 指定模型文件
python aot_compile.py --model path/to/model.tflite --soc SM8650
```

### 命名规则

```
{原模型名}_Qualcomm_{SoC型号}.tflite
# 示例：yolo26s_float16-no-nms_Qualcomm_SM8650.tflite
```

AOT 模型放置于对应 `aot/` 子目录，应用启动时自动根据设备 SoC 匹配并优先加载，无匹配时回退 JIT。

---

## 推理配置

### 加速器选择建议

| 场景 | 推荐配置 |
|------|---------|
| 最佳性能 | Native + NPU + FLOAT16 传统模式 |
| GPU 加速 | Native + GPU + INT8 传统模式 |
| 最大兼容 | Native + CPU + 任意模型 |
| 最高精度 | Native + CPU + FLOAT32 |

### 推理后端

| 后端 | 推荐 | 说明 |
|------|:----:|------|
| LiteRT Native（C++ JNI） | ✅ | 支持 JIT 缓存 + AOT + 零拷贝 |
| LiteRT Java（Kotlin） | — | 备选，仅支持 AOT |

---

## 编译环境

### 环境要求

| 工具 | 版本            |
|------|---------------|
| Android Studio | 最新版本          |
| JDK | 21            |
| Android SDK | API 36        |
| Android NDK | 28.2.13676358 |
| CMake | 3.22.1+       |
| Kotlin | 2.3.21        |
| Gradle | 9.5.0         |
| AGP | 9.2.1         |

### LiteRT C++ SDK

首次 Gradle sync 时自动从 [GitHub Releases](https://github.com/google-ai-edge/LiteRT/releases) 下载并配置，无需手动操作。若网络下载失败，可手动下载 `litert_cc_sdk.zip` 并解压到 `detector/src/main/cpp/litert_cc_sdk/`，然后删除 `detector/.cxx/` 缓存后重新 sync。

### NPU 运行时（targetSoc 配置）

`targetSoc` 控制打包的 NPU 运行时模块，精确配置可减小 APK 体积（每个运行时约 10-20 MB）：

| 配置值 | 打包内容 |
|--------|---------|
| `qualcomm`（默认） | 全部高通运行时（v69-v81） |
| `sm8450` / `sm8550` / `sm8650` / `sm8750` / `sm8850` | 对应单个 SoC 运行时 |
| `mediatek` | MediaTek 运行时（需 Android 15） |

---

## 项目结构

```
yolo-tflite-android/
├── app/                          # 主应用模块
│   ├── src/main/java/            # UI / ViewModel / Pipeline / 模型管理
│   └── src/main/assets/          # 内置模型（默认为空，.gitignore 已排除）
├── detector/                     # 检测器模块
│   ├── src/main/java/            # IDetector 接口、Native/Java 后端、后处理器
│   └── src/main/cpp/             # C++ JNI、LiteRT 推理、5 种任务后处理器
├── litert_npu_runtime_libraries/ # NPU 运行时库（高通 / MTK / Tensor）
│   ├── fetch_qualcomm_library.sh # 高通运行时下载脚本
│   └── fetch_qualcomm_library.ps1
├── aot_compile.py                # AOT 预编译脚本
└── setup-isolated-env*           # 隔离环境配置脚本
```

---

## 已知限制

| 限制 | 说明                                         |
|------|--------------------------------------------|
| 端到端模式仅 CPU | NMS 算子不被 GPU / NPU 支持，仅 YOLO26 系列有此模式      |
| 零拷贝限 MTK / Tensor | 高通 NPU 使用 FastRPC buffer，无法实现 GL → NPU 零拷贝 |
| MTK NPU 仅 Android 15 | 官方示例中声明只支持Android 15，实测Android 16不支持       |
| Google Tensor NPU 未测试 | 运行时模块已预留，未经真机验证                            |
| 仅 arm64-v8a | 不提供 32 位架构支持                               |
| minSdk 31 | 不支持 Android 11 及以下                         |
| 远程下载为开发环境 | 默认服务器地址为局域网，需自行配置                          |
| 其他 YOLO 系列未充分测试 | YOLOv5/v8/v10/v11 理论兼容，可能需要调整后处理参数         |

---

## 许可证

本项目以 [Apache License 2.0](LICENSE) 发布。

**第三方依赖：**

| 依赖 | 许可证 | 说明 |
|------|--------|------|
| LiteRT（含 NPU 插件） | Apache 2.0 | 已包含 |
| Qualcomm QNN SDK | Qualcomm 专有 | 需通过脚本手动下载 |
| Ultralytics YOLO 预训练权重 | AGPL-3.0 | 不包含，需自行下载转换；商业使用请联系 [Ultralytics](https://ultralytics.com/license) |

---

## 致谢

- [LiteRT](https://ai.google.dev/edge/litert) — Google 端侧 AI 推理框架
- [Ultralytics YOLO](https://github.com/ultralytics/ultralytics) — YOLO 目标检测框架
- [LiteRT Samples](https://github.com/google-ai-edge/litert-samples) — Native 推理层的重要参考
- [Qualcomm AI Engine Direct](https://developer.qualcomm.com/software/qualcomm-ai-engine-direct-sdk) — 高通 NPU 推理运行时
- [Jetpack Compose](https://developer.android.com/compose) — Android UI 框架
- [CameraX](https://developer.android.com/training/camerax) — Android 相机开发库