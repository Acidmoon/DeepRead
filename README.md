# Reader — Android 平板阅读器 MVP

本地优先的 Android 平板阅读器，支持书架、书签、TXT / PDF / DOCX，以及纯文本阅读设置。

## 功能

- **书架**：SAF 导入、网格展示、长按删除、阅读进度
- **电子书**（统一重排阅读）：TXT / Markdown / EPUB / DOCX  
  - MD：标题目录 + 上下滚动富渲染  
  - EPUB：spine 正文 + nav/ncx 目录，可选内嵌封面  
- **版式文档**：PDF（全屏渲染、独立设置）
- **目录 / 书签 / 封面**：导入预生成，旧书自动补齐
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
