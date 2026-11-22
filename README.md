# Reader — Android 平板阅读器

本地优先的 Android 平板阅读器（也适配手机），支持书架、书签、全文搜索，以及 TXT / Markdown / EPUB / DOCX / PDF。当前版本 0.2.3。

## 功能

- **首页**：最近阅读与我的书架两个 Tab，支持左右滑动切换
- **书架**：SAF 导入（文件或多选）、从系统目录导入、网格展示、文件夹、长按重命名 / 移入移出 / 删除、阅读进度
- **电子书**（统一重排阅读）：TXT / Markdown / EPUB / DOCX
  - MD：标题目录 + 上下滚动富渲染
  - EPUB：spine 正文 + nav/ncx 目录，可选内嵌封面
  - 全文搜索并可跳转命中位置（仅电子书）
- **版式文档**：PDF（全屏按页渲染、独立设置、可选从右往左日漫翻页、双指缩放、加密文档密码解锁与记忆）
- **阅读**：手势翻页与菜单、自动翻页、应用内亮度 / 护眼叠层、可拖动进度条、阅读不息屏、底部状态浮层（章节名与进度，可隐藏）
- **目录 / 书签 / 封面**：导入预生成，旧书自动补齐
- **同步预留**：`SyncPort` + `remoteId` / `updatedAt` 字段（尚未实现同步）

## 环境

- JDK 17
- Android SDK（compileSdk / targetSdk 35，minSdk 26）
- Gradle 8.10.2
- Kotlin 2.0.21，Android Gradle Plugin 8.7.2
- Jetpack Compose，Room 2.6.1（数据库 `reader.db` version 8）
- 依赖注入为手写 `AppContainer`

## 构建

仓库暂无 `gradlew` 包装脚本（仅有 `gradle/wrapper/gradle-wrapper.properties`），需使用本机 Gradle 8.10.2，并设置 `ANDROID_HOME` 指向 Android SDK。

```bash
# Windows 示例
set ANDROID_HOME=E:\Android\Sdk
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

目前仅有 `app/src/test` 下的 JVM 单元测试，仓库中无仪器测试源码。

## 模块结构

```
app/src/main/java/com/vibecoding/reader/
  ui/home|bookshelf|reader|common|theme   # Compose UI
  data/db|repo|parser|import
  domain/model|sync|reader|search
  di/                    # 手写 AppContainer
```

## 已知限制

- 加密 PDF 的打开密码明文保存在本地 Room 数据库
- release 构建目前使用 debug 签名，便于本机安装；上架前需更换正式密钥
- Manifest 已声明 `ACTION_VIEW`，可接收其他应用打开支持的文档，但 `MainActivity` 尚未处理 `intent.data`，因此目前还不能从其他应用直接打开文件
