# 钓遍中国

PICO 版本 0.2.2（versionCode 4），Android 包名 `com.vrgames.fishing`。

[版本说明](更新说明.md) · [项目原说明](docs/提交包原说明.md) · [素材来源](docs/素材来源.md)

## 编译

本仓库根目录即 Gradle 工程，游戏代码、运行素材和测试在 `app/src/`。使用 JDK 21 和 Android SDK 35。在本目录运行 `./gradlew :app:testDebugUnitTest :app:assembleRelease`，Windows 可用 `gradlew.bat`。正式签名由工程外的配置提供。
