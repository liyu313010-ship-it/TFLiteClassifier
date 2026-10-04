# 双模型识别说明

## 1. EfficientDet-Lite2 目标检测

- 项目文件：`app/src/main/assets/efficientdet_lite2.tflite`
- 来源：Google MediaPipe 官方模型存储
- 下载地址：`https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/latest/efficientdet_lite2.tflite`
- 文件大小：7,515,971 字节
- SHA-256：`B3F50554CB0EA559E90328845F7D9BA4D13C8BFF372914D24E06BC8BB72FA896`
- 数据集/输出：COCO 80 个常见目标类别，返回类别、置信度和检测框。
- 当前策略：置信度阈值 0.35，最多展示 3 个结果。

它覆盖人、车辆、猫狗、桌椅、手机、杯子、餐具、食物和常见家电等日常类别。项目内置经过人工整理的 80 类中英文映射。

## 2. EfficientNet-Lite2 回退分类

- 项目文件：`app/src/main/assets/efficientnet_lite2.tflite`
- 来源：Google MediaPipe 官方模型存储；模型系列由 TensorFlow EfficientNet-Lite 提供
- 下载地址：`https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite2/int8/latest/efficientnet_lite2.tflite`
- 文件大小：7,165,461 字节
- SHA-256：`7FC3A7DBC1CD2833B162A54577AFB47AB531D9C4B799EF11D90A4C56FE4ED5AD`
- 数据集/输出：ImageNet 1000 类整图分类，模型元数据内含英文标签。
- 当前策略：仅在检测器没有结果时运行；置信度阈值 0.12，最多展示 3 个结果。

中文标签文件：

- 项目文件：`app/src/main/assets/labels_zh.txt`
- 上游来源：OFA-Sys/Chinese-CLIP 的 ImageNet-1K 中文标签
- 来源地址：`https://github.com/OFA-Sys/Chinese-CLIP/blob/master/datasets/ImageNet-1K/label_cn.txt`
- 行数：1001（第 0 行为 `background`）
- SHA-256：`31359D1BB499B57D281790482113165C72672E65DC47B029F2D190DF8A1AE887`

## 3. 选择依据

单纯使用 ImageNet 分类器只能判断整张图的主要内容，无法定位多个目标；单纯使用 COCO 检测器又只认识固定 80 类。当前串联策略先检测、再回退，在移动端延迟、包体大小和类别覆盖面之间取得平衡。

没有采用 EfficientDet-Lite4 作为实时检测器：官方移动端参考数据显示，Lite4 的精度更高但延迟明显高于 Lite2。实际性能仍受手机芯片、温度、相机分辨率和画面内容影响，必须以目标设备实测为准。

## 4. 已知限制

- COCO 检测器只会输出其 80 个训练类别；相近物体可能被归到同一上位类别。
- ImageNet 标签包含很多细粒度物种和专业名词，对遮挡、暗光、多主体或背景复杂的图片可能产生不符合直觉的结果。
- 回退分类以整张画面为输入，不提供物体位置，也不等于真正的开放词汇识别。
- 中英文标签用于界面展示，不改变模型本身的预测能力。
- 置信度不是概率保证；本应用不适用于医疗、安防、身份识别或其他高风险决策。

## 5. 运行时与复现

- Android 推理运行时：`com.google.mediapipe:tasks-vision:1.0.0`
- 推理模式：`RunningMode.IMAGE`，通过单线程 Executor 串行访问两个模型。
- 两个 TFLite 模型均随 APK/AAB 打包，本地识别运行时不下载模型，也不需要联网。
- 可使用 PowerShell `Get-FileHash -Algorithm SHA256` 对照上述哈希验证资产完整性。

以上“不需要网络”仅指两个端侧模型。用户主动开启“联网识别（通义千问）”后，应用会改用阿里云百炼 `qwen3-vl-plus`；该模式不是本模型卡所列 TFLite 模型的一部分，需要网络，并会把压缩后的当前画面发送给云端服务。
