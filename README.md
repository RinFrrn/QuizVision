# 题眼识

题眼识是一个开源、本地优先的 OCR 搜题与背题 App，可从 Excel 和 Word 文件导入题库，并使用本机 OCR、屏幕识别或无障碍通道获取文字，将相机或屏幕内容匹配到本地题库。

题库、识别和练习流程都围绕本地使用设计：导入后的题目保存在设备数据库中，相机和屏幕截图 OCR 使用设备端 ML Kit 文字识别处理，无障碍搜题读取 Android 无障碍系统暴露的文字，项目本身不包含自建后端或题库上传逻辑。

## 主要功能

- 从系统分享进入应用的 Excel 和 Word 文件导入题库。
- 使用 Room 在本地存储题库、题目、收藏、背题进度和考试历史。
- 浏览、搜索、重命名、合并、删除、导出和分享题库。
- 使用 CameraX 与 ML Kit 中文文字识别，将拍摄到的文字匹配到本地题库。
- 支持顺序背题、随机背题和考试模式，并记录本地答题历史。
- 支持相机搜题，并提供实验性的屏幕搜题和无障碍搜题流程。

## 隐私与数据处理

- 题库数据存储在应用本地数据库中。
- 相机识别和屏幕截图识别使用设备端 ML Kit 文字识别 API 处理 OCR。
- 无障碍搜题读取 Android 无障碍系统暴露的屏幕文字，不经过 OCR。
- 本项目不包含自建后端，也没有把题库数据上传到项目维护服务器的逻辑。
- 导入文件通过 Android content URI 或系统分享流程读取。
- 导出文件通过 Android Storage Access Framework、基于缓存的 FileProvider 分享，或用户选择的目标位置写入。
- 屏幕搜题功能会在用户授权屏幕录制和悬浮窗权限后读取可见屏幕内容。
- 无障碍搜题会在用户手动开启无障碍服务后读取 Android 无障碍系统暴露的屏幕文字，并通过悬浮窗展示匹配结果。
- 屏幕搜题和无障碍搜题仍属于实验性质，请只在你愿意在本机处理的内容上使用。

## 权限说明

- `CAMERA`：用于相机识别流程。
- `INTERNET`：为 ML Kit 和依赖兼容性保留。本项目自身没有定义自建上传接口。
- `SYSTEM_ALERT_WINDOW`：用于实验性屏幕搜题功能中的悬浮窗。
- 无障碍服务：用于无障碍搜题，需要用户在系统设置中手动开启。
- 文件访问通过 Android 分享、content URI、FileProvider 和 Storage Access Framework 完成。

## 公开仓库说明

- 仓库不包含任天堂或其它第三方受版权保护的内容。
- 默认答题反馈音效是原创合成资源。
- 代码库仍保留部分 ML Kit quickstart sample 的结构和 Apache 许可下的示例代码。
- Android package 与 namespace 为 `com.virin.visionquiz`。

## 项目结构

- `quizentry`：应用入口和导入路由。
- `dao`：Room 实体、DAO、数据库和导入解析。
- `quizlibrarylist`：题库列表和仓库层。
- `quizlibraryfeatures`：题库详情、操作入口和学习入口。
- `quizlist`：单个题库的题目浏览、搜索和导出操作。
- `quizstudy`：背题、考试、历史、收藏和答题反馈。
- `quizdetector`：基于相机的 OCR 识别流程。
- `screendetector`：实验性屏幕搜题、无障碍搜题和悬浮窗展示流程。
- `vision/questiondetector`：OCR 处理器和题目匹配逻辑。

## 构建

环境要求：

- Android Studio 或 JDK 17
- 与模块配置匹配的 Android SDK

命令：

```bash
./gradlew assembleDebug
```

## 许可

本项目基于 Apache License 2.0 开源，详见 [LICENSE](LICENSE)。

本仓库源自 Google ML Kit vision quickstart sample，并仍包含由该示例派生的代码。相关归属声明见 [NOTICE](NOTICE)。第三方依赖在 `app/build.gradle` 中声明，并遵循各自许可证。
