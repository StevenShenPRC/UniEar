# 开发与发布

安装 Git、JDK 17 和 Python 3.10 或更新版本。Android 构建还需 SDK Platform 36 和 Build Tools 36.0.0。

```sh
python scripts/hyperears.py checkout
sh ./gradlew :core:test :desktop:compileKotlin :android:assembleDebug
```

Windows 使用 `gradlew.bat`。只构建桌面端时，可加 `-PdesktopOnly`。协议适配器使用 `upstream/hyperears.rev` 锁定的 HyperEars 提交。

## 发布 Android 版本

1. 更新 `android/build.gradle.kts` 中的 `versionName`，递增 `versionCode`。
2. 确认 `UNIEAR_KEYSTORE_BASE64` 和 `UNIEAR_KEYSTORE_PASSWORD` 保存的是现有 PKCS#12 密钥库和密码，别名为 `uniear`。
3. 使用下文的提交前缀或推送匹配标签触发发布。

## 构建本文档

```sh
cd docs
npm ci
npm run dev
npm run build
```

静态站点输出到 `.vitepress/dist`。部署到子路径时，在构建前配置 `DOCS_BASE`。英文页放在文档根目录，中文页用相同文件名放在 `zh/`。

## 新增语言

在 `core/src/main/resources/uniear/core/` 中复制 `messages.properties` 为 `messages_<语言代码>.properties`。翻译值并保留键名和编号占位符，再在 `Translations.supportedLanguages` 中登记语言。Android 需添加 `values-<语言代码>/strings.xml` 并更新 `res/xml/locales_config.xml`。文档页沿用同名文件，放入 `docs/<语言代码>/`，然后在 `docs/.vitepress/config.mts` 中登记语言。需要从右向左阅读时，将方向设为 `rtl`。未支持的语言和缺失的文案条目使用英文。

## 提交前缀触发发布

将发布提交推送到 `main`。工作流读取本次推送最终提交的**标题第一行**。

| 提交标题示例 | 自动创建的标签 | 发布类型 |
| --- | --- | --- |
| `release-v1.2.3: 更新说明` | `v1.2.3` | 正式版 |
| `beta-v1.2.3: 测试版说明` | `beta-v1.2.3` | Beta 预发布 |

版本必须为三个数字段，前缀后接行末、空白或冒号。提交前将 Android 的 `versionName` 设为相同数字版本，并递增 `versionCode`。工作流构建、签名、验签并进行启动检查，随后将 APK 和 SHA-256 校验文件附在 GitHub Release 中。Beta 标为 **Prerelease**，不会成为 **Latest**。

一次推送只读取最终提交。使用 squash 合并时，将前缀写入最终 squash 提交标题；使用 merge commit 时，写入合并提交标题。正文中或较早提交中的前缀不触发发布。

仍支持推送 `vX.Y.Z` 和 `beta-vX.Y.Z` 标签。每次新的 Beta 构建都使用新版本号。重复运行已发布版本会保留原附件，标签已指向其他提交时会报错。手动运行可校验并构建匹配的发布标题或标签，不会发布版本。
