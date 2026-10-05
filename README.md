# HyperPlus — 按钮旋转版

本分支来自 [213DEE/HyperPlus](https://github.com/213DEE/HyperPlus)，只保留按钮旋转功能。

## 功能

- **半自动旋转**：转动手机时显示旋转按钮，点击后才改变屏幕方向。
- **跟随系统**：由系统处理旋转。
- **展开方向**：使用系统当前显示的内屏方向；接管时同步当前方向，避免旧的竖屏锁定值导致跳转。
- 外屏始终由系统处理，保持原有旋转锁定选择。
- 支持按钮显示时长、按钮预览、应用豁免名单与控制中心快捷开关。
- 已移除自适应旋转、人脸识别、相机采集与方向校准。旧的自适应模式配置升级为半自动模式。

## 安装

需要 root 与兼容 API 102 的 LSPosed。安装 APK 后启用模块，作用域选择 **系统界面（com.android.systemui）**，重启系统界面或设备，再打开 HyperPlus 选择半自动旋转。

[下载本分支安装包](https://github.com/ymy1990/HyperPlus/releases)

发布的 Debug APK 使用 Debug 签名；可覆盖安装本分支先前的 Debug 版本。若现有应用签名不同，需要先卸载，卸载会清除应用设置。

## 构建

使用 JDK 21 与 Android SDK 37，运行 `./gradlew assembleDebug`。

## License

沿用原项目 [AGPL-3.0](LICENSE)。
