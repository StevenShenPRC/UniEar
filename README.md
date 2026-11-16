# UniEar

[中文使用指南](docs/zh/guide.md) · [English guide](docs/guide.md) · [Releases](https://github.com/StevenShenPRC/UniEar/releases)

UniEar 是一个普通应用，用于读取蓝牙耳机的电量和降噪状态，并切换当前型号支持的降噪、通透、关闭或抗风噪模式。先在系统设置中配对耳机，然后在 UniEar 中扫描、选择并连接。

| 平台 | 当前传输实现 |
| --- | --- |
| Android 8.0+ | BLE GATT、按服务 UUID 连接的 RFCOMM |
| Windows | BLE GATT、按服务 UUID 或固定通道号连接的 RFCOMM |
| Linux | BLE GATT、RFCOMM、经典蓝牙 L2CAP。需启用 BlueZ，RFCOMM 服务查找使用 `sdptool` |
| macOS | BLE GATT。需允许启动应用的终端或 Python 进程访问蓝牙 |

漫步者、水月雨、弱水时砂等品牌的适配来自 HyperEars。实际功能取决于上游对具体型号和固件的支持，以及平台能否打开适配器声明的控制通道。只提供固定 RFCOMM 通道号的型号，需系统允许应用使用该通道。

支持的型号还可显示降噪强度、低延迟模式、音效预设或 Galaxy Buds 的均衡器、通透音量和触控设置。功能项由耳机上报状态和适配器声明的能力决定。

专有通道不可用时，应用可显示系统蓝牙连接，并在 Android 或 Linux BlueZ 提供电量时显示整机电量。左右耳及充电盒电量等专有数据由支持该功能的适配器读取。Galaxy Buds2 Pro 的双耳长按动作和降噪切换范围可一次提交。

## Android 快捷控制

在设备页连接耳机，再进入设置按需开启下列入口。每个入口有自己的开关。

先在 Android 系统设置中连接蓝牙耳机，再点击 UniEar 的「查找已连接耳机」。列表根据系统公开的音频连接状态与耳机类型生成。可在「设置 → 外观」切换深色模式。

| 入口 | 用法 |
| --- | --- |
| 通知中心菜单 | 折叠状态提供降噪、通透、关闭和抗风噪按钮；设备不支持的按钮不可点击。展开可查看型号、电量、当前模式与控制通道 |
| 控制中心磁贴 | 添加“耳机模式”，短按轮换耳机支持的模式，长按打开 UniEar |
| 桌面小组件 | 添加 UniEar 小组件，查看电量并点按对应模式 |
| UniEar 悬浮胶囊 | 授予显示在其他应用上层的权限，点按胶囊展开模式列表 |
| 系统实况窗 | 在「设置 → 耳机连接」启用。系统蓝牙连接耳机时提示约 30 秒。兼容型号可点按连接或忽略。Android 16 支持状态栏实况胶囊 |

快捷入口使用同一条控制会话，断开后停止发送控制命令。系统实况窗使用 Android Live Updates，显示效果由系统和用户的通知设置决定。自动显示需要允许附近设备和通知。系统蓝牙事件的提示会自动消失，手动控制连接结束后回到常规通知。支持 Android Live Updates 的 ColorOS 16 固件可走同一接口。华为、vivo 和小米各自的专有实况窗接口需要厂商接入和真机验证。

「自动建立控制连接」默认关闭。开启后，UniEar 在系统音频连接建立时确认型号有控制适配器，再尝试建立控制会话。一次蓝牙连接最多自动尝试三次。通知用「蓝牙已连接」和「已取得控制权」区分系统连接与协议握手成功。系统限制后台启动时可点通知里的「连接」，或在设置里通过 Android 伴侣设备界面关联耳机，授权后台连接。

在「设置 → 网络自动化」为移动网络和不同 Wi-Fi 名称配置降噪模式。规则只在 UniEar 已连接耳机时运行，网络稳定后按当前网络执行一次。Wi-Fi 规则需要系统提供 SSID，需要精确位置权限和定位开关。没有对应规则、读不到 Wi-Fi 名称或耳机不支持目标模式时，保持耳机现状。

## 构建和运行

准备 Git、JDK 17 和 Python 3.10 或更新版本。将 `JAVA_HOME` 指向 JDK 17，然后从项目根目录运行：

```powershell
python scripts/hyperears.py checkout
python -m pip install -r desktop/requirements.txt
.\gradlew.bat -PdesktopOnly :core:test :desktop:run
```

在 Linux 或 macOS 上将 `python` 替换为 `python3`，将 `.\gradlew.bat` 替换为 `sh ./gradlew`。用 `:desktop:installDist` 代替 `:desktop:run` 可生成 `desktop/build/install/desktop/`，启动脚本在该目录的 `bin/` 下。桌面版从 PATH 查找 Python，使用虚拟环境时可通过 `UNIEAR_PYTHON` 指定解释器。

Android 构建还需要 Android SDK Platform 36 和 Build Tools 36.0.0。配置 `ANDROID_HOME` 或 `local.properties` 中的 `sdk.dir` 后运行：

```powershell
.\gradlew.bat :android:assembleDebug
```

APK 输出路径为 `android/build/outputs/apk/debug/android-debug.apk`。

GitHub Actions 使用固定签名发布 APK 时，需要在仓库的 Actions Secrets 中设置 `UNIEAR_KEYSTORE_BASE64` 和 `UNIEAR_KEYSTORE_PASSWORD`。密钥库使用 PKCS#12 格式，密钥别名为 `uniear`。工作流会对发布 APK 签名并校验签名证书的 SHA-256 指纹。两个 Secrets 都未设置时，产物是临时签名的 `UniEar-android-debug`。固定签名的产物名为 `UniEar-android-release`。

## 更新品牌适配

`upstream/hyperears.rev` 固定 HyperEars 的 Git 提交。初始化脚本将源码稀疏检出到 `upstream/HyperEars/`。Gradle 直接编译上游 `protocol` 和 `integration` 的 Kotlin 源码，并运行这两个目录中的单元测试。Android 与桌面端共用适配器和会话逻辑，各自实现蓝牙传输。

```powershell
python scripts/hyperears.py update
python scripts/hyperears.py update --android
```

两条命令都从上游 `main` 获取候选版本。前者执行协议测试并编译桌面应用，后者同时构建 Android APK。验证通过后脚本才写入新的提交 SHA，验证失败会恢复原检出。更新后提交 `upstream/hyperears.rev` 即可让其他开发者复现该版本。若上游改动了适配器接口或增加了新的传输类型，先调整 UniEar 的会话或传输层，再运行更新。

上游源码及其第三方许可见 [HyperEars](https://github.com/silverpoetry/HyperEars)。构建会将上游 `LICENSE` 和 `THIRD_PARTY_NOTICES.md` 一并打包到核心模块。
