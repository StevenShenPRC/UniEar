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
3. 在目标提交上推送与 `versionName` 匹配的标签，例如 `v0.3.2`。发布工作流完成测试、构建、签名和校验后，创建带 APK、SHA-256 文件和自动生成发布说明的 GitHub Release。

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
