# 钓遍中国 / FishingChina

PICO Vibe Lab 投稿源码。Android Spatial 全沉浸 VR 应用，版本 0.1 / versionCode 1。

## 构建

本仓库根目录就是 Gradle 工程。准备 JDK 21 和 Android SDK 35，设置 JAVA_HOME 和 ANDROID_HOME，然后执行：

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleRelease
```

Windows 使用 `gradlew.bat`。没有外部签名配置时，生成 `app/build/outputs/apk/release/app-release-unsigned.apk`，由发布平台签名。
原开发者签名配置及私钥不在仓库中，也不属于构建的必需输入。

Gradle 8.13 使用华为云镜像，分发文件 SHA-256 固定为官方校验值。所有运行时素材已随源码提供，无需调用模型生成服务。

## 内容

杭州西湖、千岛湖、桂林漓江和查干湖冰钓四处主线钓场；奖杯解锁西湖月夜与查干湖头鱼挑战。
十种鱼、四类鱼饵，以及抛竿、观察浮漂、提竿、张力控制、收线和抄网流程。采用系统手势交互，无需手柄。

包名 `com.vrgames.fishing`。玩法代码、资源及模型来自本地正式 0.1 工程。

原工程在正式 APK 生成后加入过一处仅供 debug 构建使用的 AutoPilot 辅助改动。投稿副本已将该辅助逻辑对齐回正式 APK 对应状态，未改动玩家玩法；原工程另行保留。独立重建后，APK 内的 430 项运行文件（含 DEX、资源与优化配置，不含签名信息）全部与原正式包字节一致。
源码许可见 LICENSE，素材说明见 [素材来源](docs/素材来源.md)。
