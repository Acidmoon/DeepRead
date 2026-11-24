# Reader — Android 平板阅读器

本地优先的 Android 平板阅读器（也适配手机），支持书架、书签、全文搜索，以及 TXT / Markdown / EPUB / DOCX / PDF。当前版本 0.2.3。

## 功能

- **首页**：最近阅读与我的书架两个 Tab，支持左右滑动切换
- **书架**：SAF 导入（文件或多选）、从系统目录导入、网格展示、文件夹、长按重命名 / 移入移出 / 删除、阅读进度、按书名或作者搜索
- **电子书**（统一重排阅读）：TXT / Markdown / EPUB / DOCX
  - MD：标题目录 + 上下滚动富渲染；导入时会把安全范围内的本地相对图片复制进书籍目录
  - EPUB：spine 正文 + nav/ncx 目录，可选内嵌封面
  - 全文搜索并可跳转命中位置
- **版式文档**：PDF（全屏按页渲染、独立设置、可选从右往左日漫翻页、双指缩放、加密文档密码解锁与记忆）
  - 支持基于文字层的全文搜索（不做 OCR）；加密文档需先解锁
- **阅读**：手势翻页与菜单、自动翻页、应用内亮度 / 护眼叠层、可拖动进度条、阅读不息屏、底部状态浮层（章节名与进度，可隐藏）
- **从其他应用打开**：处理 `ACTION_VIEW` / `onNewIntent`，已在书架的同一来源会直接打开，否则导入后再打开
- **目录 / 书签 / 封面**：导入预生成，旧书自动补齐；加密 PDF 在解锁后补封面和目录
- **同步预留**：`SyncPort` + `remoteId` / `updatedAt` 字段（尚未实现同步）

## 环境

- JDK 17
- Android SDK（compileSdk / targetSdk 35，minSdk 26）
- Gradle 8.10.2（仓库已包含 `gradlew` 包装脚本）
- Kotlin 2.0.21，Android Gradle Plugin 8.7.2
- Jetpack Compose，Room 2.6.1（数据库 `reader.db` version 8，schema 导出在 `app/schemas`）
- 依赖注入为手写 `AppContainer`

## 构建

设置 `ANDROID_HOME` 指向 Android SDK 后：

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

release 包默认开启混淆。若未提供正式签名密钥，会使用 debug 签名以便本机安装，**这不是正式发布签名**。如需正式签名，在仓库根目录或 `app/` 下放置已被 git 忽略的 `key.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`），不要把密钥提交进仓库。已经用 debug 签名安装过的包，换成新密钥后将无法覆盖升级。

## 单元测试

目前以 `app/src/test` 下的 JVM 单元测试为主，多数覆盖纯逻辑 helper（导入预检、路径安全、进度防抖、位图预算计算、密码策略等），仓库中无仪器测试源码。阅读页与 Compose 位图生命周期、Keystore 真机路径、混淆后的 PdfBox 运行，都还没有集成或真机测试。本环境没有模拟器或已连接设备时，无法做这类验证。

GitHub Actions 工作流 `.github/workflows/android.yml` 会在推送到 `master` 或向 `master` 开 PR 时运行 `assembleDebug`、单元测试和 lint。

## 模块结构

```
app/src/main/java/com/vibecoding/reader/
  ui/home|bookshelf|reader|common|theme   # Compose UI
  data/db|repo|parser|import|secure
  domain/model|sync|reader|search|bookshelf
  di/                    # 手写 AppContainer
```

## 已知限制

- 加密 PDF 的打开密码经 Android Keystore 加密后存放在应用的 `no_backup` 目录，不随系统备份迁移；升级时会把库里旧明文迁入保险箱并做 WAL checkpoint / VACUUM，但恢复备份或换机后 Keystore 密钥不可用，可能需要重新输入密码
- PDF 文字层索引写在 `no_backup`（不会把加密文档解锁后的正文明文送进系统备份）；打开时会删除早期误放在书籍目录里的 `text-index`
- 未配置正式签名密钥时，release 构建仍使用 debug 签名，仅便于本机安装，不能当作上架或对外分发的正式签名
- PDF 全文搜索只覆盖文字层，扫描件或纯图片页无法命中（不做 OCR）
- 从单个 content URI 导入 Markdown 时，若提供方不给出父目录，无法复制旁边的本地图片
- 同步尚未实现
