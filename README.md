# Reader — Android 平板阅读器 MVP

本地优先的 Android 平板阅读器，支持书架、书签、TXT / PDF / DOCX，以及纯文本阅读设置。

## 功能

- **书架**：SAF 导入、网格展示、长按删除、阅读进度
- **格式**：TXT（编码启发式）、PDF（PdfRenderer）、DOCX（轻量 OOXML 解析）
- **目录**：TXT 章节启发式 / DOCX Heading / PDF Outline（失败则页码列表）
- **书签**：按位置收藏与跳转
- **文本设置**：背景主题、字号、行距、点按/滑动翻页
- **同步预留**：`SyncPort` + `remoteId` / `updatedAt` 字段

## 环境

- JDK 17+
- Android SDK（compileSdk 35，minSdk 26）
- Gradle 8.10+

## 构建

```bash
# Windows
set ANDROID_HOME=E:\Android\Sdk
gradle wrapper   # 可选
.\gradlew.bat assembleDebug

# 或直接使用本机 Gradle
gradle :app:assembleDebug
```

安装到设备：

```bash
gradle :app:installDebug
```

## 单元测试

```bash
gradle :app:testDebugUnitTest
```

## 模块结构

```
app/src/main/java/com/vibecoding/reader/
  ui/bookshelf|reader/…   # Compose UI
  data/db|repo|parser|import
  domain/model|sync
  di/
```

## 明确不做（MVP）

EPUB、在线书城、TTS、Word 复杂版式、真实云同步、加密 PDF。
