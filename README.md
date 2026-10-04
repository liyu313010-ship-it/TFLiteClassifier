# 智能视觉识别 — Android 混合视觉应用

一款同时支持端侧快速识别与通义千问视觉大模型的 Android 应用。默认使用 EfficientDet-Lite2 检测常见物体，检测不到时自动切换到 EfficientNet-Lite2；用户也可以开启联网识别，获取千问生成的中文物体与场景描述。

## 已实现功能

- 双模型识别：COCO 80 类多目标检测 + ImageNet 1000 类回退分类。
- 千问视觉：可选 `qwen3-vl-plus` 云端识别，补充本地固定类别之外的开放场景理解。
- 中英文结果：每条结果同时展示中文名称、英文原名与置信度。
- 语音播报：本地相册结果和千问结果立即播报；本地实时结果稳定 3 帧后播报，相同内容不重复。
- TTS 容错：等待系统引擎真正就绪后才播报；中文不可用时回退英文；缺少引擎或语音包时引导到系统设置。
- 实时识别：后置摄像头预览，每约 250 ms 分析一帧，并绘制品牌色检测框。
- 相机控制：右上角按钮切换前后摄像头；支持 `− / +` 倍率控制和双指捏合缩放，并自动适配设备的真实倍率范围。
- 相册识别：通过系统 Photo Picker 选择图片，不申请存储权限。
- 模式透明：联网识别默认关闭；只有打开“联网识别（通义千问）”时才上传压缩后的当前画面。
- 大图保护：相册图片采样到最长边不超过 2048 像素，并处理全部 EXIF 方向。
- 商业级界面：Material 深色视觉、清晰的信息层级、横竖屏布局、系统栏适配与无障碍描述。

## 识别流程

```text
CameraX / Photo Picker
        ↓
安全解码、旋转、请求隔离
        ↓
EfficientDet-Lite2（COCO 80 类，阈值 0.35）
        ├─ 检出 → 多目标框 + Top-3
        └─ 未检出 → EfficientNet-Lite2（ImageNet 1000 类，阈值 0.12）
                         ↓
              中英文结果 + 置信度 + 可选 TTS

或开启“联网识别（通义千问）”
        ↓
qwen3-vl-plus → 中文物体/场景描述 → TTS 立即播报
```

回退分类针对的是整张画面的主要内容，所以它没有检测框。模型来源、SHA-256 和限制见 [MODEL_CARD.md](MODEL_CARD.md)。

## 工程结构

```text
app/src/main/
├─ assets/
│  ├─ efficientdet_lite2.tflite
│  ├─ efficientnet_lite2.tflite
│  └─ labels_zh.txt
├─ java/com/example/tfliteclassifier/
│  ├─ MainActivity.java        # 生命周期、相机/相册、TTS 与请求编排
│  ├─ classifier/              # 双模型推理和中英文标签
│  ├─ speech/                  # 稳定帧与防重复播报策略
│  └─ ui/                      # 检测框与结果列表
└─ res/
   ├─ layout/                  # 竖屏界面
   ├─ layout-land/             # 横屏双栏界面
   ├─ drawable/                # 品牌图形和状态视觉
   └─ values/                  # 主题、颜色与中文文案
```

## 构建与安装

要求 JDK 17、Android SDK Platform 37。项目使用 Gradle Wrapper；建议把 Gradle 缓存留在非系统盘：

```powershell
$env:GRADLE_USER_HOME = 'E:\Cache\Gradle'
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug bundleRelease
```

安装 Debug APK：

```powershell
adb install -r "D:\TFLiteClassifier\app\build\outputs\apk\debug\app-debug.apk"
```

如果小米/MIUI 返回 `INSTALL_FAILED_USER_RESTRICTED`，请保持手机解锁，在开发者选项中开启“USB 调试（安全设置）”和“USB 安装”，并在手机弹窗中确认安装；这属于设备侧限制，不是 APK 编译失败。

构建产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/bundle/release/app-release.aab`

Release AAB 当前仅用于构建验证；正式上架前应配置自己的发布签名。

## 语音播报排查

打开“语音播报”后，应用会提示中文语音是否就绪。若没有声音：

1. 提高手机的媒体音量，并关闭静音/勿扰限制。
2. 在系统“文字转语音输出”中启用一个 TTS 引擎并下载中文语音包。
3. 返回应用；应用会自动重新连接语音引擎。

Android 11 及以上所需的 TTS 服务可见性已经在 Manifest 中声明。本地实时结果需要连续稳定 3 帧；相册结果和千问返回结果会立即播报。

## 权限与隐私

应用唯一面向用户的运行时权限是 `android.permission.CAMERA`。Photo Picker 会临时授权所选图片 URI，因此不需要读取媒体或旧版存储权限。应用声明了 `INTERNET` 以支持可选的千问模式，并已禁用应用数据备份。

本地模式不会上传图片。开启千问模式后，画面会缩放到最长边不超过 1024 像素并压缩为 JPEG，再发送到阿里云百炼接口。正式商用时不要把百炼 API Key 固定在 APK 中，应由自有后端代理请求并实施用户鉴权、限流、密钥轮换和隐私告知。

模型仍只能识别其训练类别，不代表可以理解任意物体，也不应用于医疗、安全、身份识别等高风险判断。要继续提升特定物品精度，应采集目标场景数据并微调检测模型，再用真实设备数据评测准确率、延迟和功耗。
