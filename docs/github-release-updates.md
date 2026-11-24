# DeepRead 通过 GitHub Releases 做应用内更新

当前版本仍是 **0.2.3 / versionCode 8**。这次提交只实现检查与安装能力，**不会自动打 tag、创建 Release 或上传 APK**。第一次对外更新时，请先把 `app/build.gradle.kts` 里的 versionCode / versionName 递增后再构建。

## 和商店渠道的区别

- **侧载 / 自建分发**（当前方案）：正式版应用查询本仓库已发布的 GitHub Release，下载 APK，校验后由系统安装界面确认覆盖安装。需要 `INTERNET` 与 `REQUEST_INSTALL_PACKAGES`，这两项只加在 `app/src/release` 的清单叠加层里，debug 包不会自动联网检查。
- **Google Play**：政策禁止使用 `REQUEST_INSTALL_PACKAGES` 做应用自更新（[敏感权限政策](https://support.google.com/googleplay/android-developer/answer/16558241)、[权限专项](https://support.google.com/googleplay/android-developer/answer/12085295)）。如果以后上架 Play，应改走 [Play In-App Updates](https://developer.android.com/guide/playcore/in-app-updates)，而不是本方案。
- 覆盖安装必须满足同一 `applicationId`、同一签名证书（或合法密钥轮转证明）、更高或相同的 versionCode。见 [How app updates work](https://developer.android.com/google/play/app-updates) 与 [Sign your app](https://developer.android.com/studio/publish/app-signing)。**换用另一套证书后，旧包无法直接覆盖升级**，用户只能先卸载（数据会丢）或作为新应用安装。

## 客户端行为

- 仅 release 构建启用自动检查：进入首页时，若距上次自动检查已超过 24 小时（含首次），在后台非阻塞查询一次。
- 首页右上角菜单提供「检查更新」。debug 构建会提示该功能仅正式版可用，不会请求 GitHub。
- 查询 `GET https://api.github.com/repos/Acidmoon/DeepRead/releases/latest`（公开接口，[官方文档](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)）：只接受已发布、非 draft、非 prerelease 的最新正式版。不向 APK 写入 GitHub 令牌。
- 未认证 API 的主限额为每 IP 每小时 60 次（[官方说明](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)）。404（尚无正式 Release）和 403/429 限流：自动检查保持安静，手动检查给出说明。离线或网络失败同样不打断阅读。
- 版本比较只用清单里的 **versionCode**，不用 tag 字符串。
- 发现更新后先展示说明，由用户确认再下载。下载支持进度、取消、超时和空间检查。随后校验 sha256、体积、包名、签名和 versionCode，再调用系统安装。**不会静默安装**。若尚未授权「安装未知应用」，会跳转系统设置，返回后可继续安装。
- 设备需要能访问 `api.github.com` 与 `github.com` / `release-assets.githubusercontent.com` 等 GitHub 资产域名，否则检查或下载会失败。

## 发布资产约定

每个正式 Release 必须恰好包含：

1. `update.json`（本清单）
2. **唯一**一个 `.apk` 文件，名称与清单中的 `apkAssetName` 一致

```json
{
  "schemaVersion": "1.0",
  "versionCode": 9,
  "versionName": "0.2.4",
  "minSupportedVersionCode": 8,
  "apkAssetName": "DeepRead-0.2.4.apk",
  "sha256": "64 位小写十六进制",
  "bytes": 12345678,
  "releaseNotes": ["更新说明"],
  "publishedAt": "2026-11-24T16:00:00+08:00",
  "mandatory": false
}
```

可选字段 `apkUrl` 若出现，必须是 HTTPS，且指向同一 GitHub 资产（可信域：本仓库 `github.com/.../releases/download/...`，以及重定向后的 `release-assets.githubusercontent.com`、`objects.githubusercontent.com`、`github-releases.githubusercontent.com`）。禁止 HTTP、内嵌凭据或任意外链。

GitHub 自 2025-06-03 起为资产提供 `digest` 字段，格式为 `sha256:<hex>`（[Changelog](https://github.blog/changelog/2025-06-03-releases-now-expose-digests-for-release-assets/)）。若该字段存在，必须与清单 sha256 一致；为 null 的旧资产则只信任清单哈希。不能只用 tag 或缺少 versionCode 的元数据来判断是否有更新。

生成清单：

```bash
python3 tools/update/generate_update_manifest.py \
  --apk DeepRead-0.2.4.apk \
  --version-code 9 \
  --version-name 0.2.4 \
  --min-supported-version-code 8 \
  --note "说明" \
  --published-at 2026-11-24T16:00:00+08:00 \
  --out update.json
```

若提供 `aapt dump badging` 的输出，可用 `--from-aapt-badging` 读取 APK 内的版本并交叉核对。

## 正式签名与草稿工作流

日常 CI 会编译 release 以确认能通过混淆，但**没有正式密钥时仍会用 debug 签名**，这个包不能当作更新资产。

`.github/workflows/draft-release.yml` 只能手动 `workflow_dispatch`，默认不会运行。它在缺少正式签名密钥时 **fail-closed**，并在打包后拒绝 `CN=Android Debug` 的证书，然后才允许把 APK 与 `update.json` 做成 **draft** Release。请先在仓库 Secrets 中自行配置（本仓库当前尚未配置，也请不要把密钥提交进 git）：

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

也可以继续使用本地 `key.properties` + 密钥库手动构建，再把产物上传为 Release 资产。发布前请把 draft 改为正式发布；draft / prerelease 不会被 `/releases/latest` 选中，因此安装在用户设备上的应用不会吃到未发布包。

## 尚未完成

- 尚未提供正式签名密钥，因此还不能生成可对外分发的更新包。
- 本次不会创建 tag、Release 或上传 APK。
- 真机上的下载、未知来源授权和系统安装界面尚未验收。
- 已用 debug 签名安装的包，换正式证书后不能覆盖升级。
