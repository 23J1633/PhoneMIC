# 开发与构建

返回 [项目首页](../README.md)。PhoneMIC 的发布平台为 Android ARM64 和 Windows x64；`ios/` 保留 React Native 工程骨架，未实现本项目的原生语音与传输能力。

## 环境

| 工具 | 要求 |
| --- | --- |
| Node.js | 22.11.0 或更高版本 |
| JDK | 17 |
| Android SDK | API 36、Build Tools 36.0.0 |
| Android NDK / CMake | 28.2.13676358 / 3.22.1 |
| Go | 1.25 或更高版本 |

项目使用 React Native 0.85.3、React 19.2.3、TypeScript 5.8.3、Kotlin 2.1.20 和 sherpa-onnx 2.0.1，具体版本以锁文件和 Gradle 配置为准。

## 获取源码并检查

```bash
git clone https://github.com/23J1633/PhoneMIC.git
cd PhoneMIC
npm ci
npm run typecheck
npm run lint -- --quiet
npm test -- --runInBand
```

Windows 接收端的协议与连接测试可在 `companion/` 中运行：

```bash
cd companion
go test -race ./...
```

## 模型和第三方运行库

```bash
node scripts/fetch-speech.mjs
```

此脚本下载 Qwen3-ASR、Silero VAD、CAM++ 和 Android AAR，并逐文件验证固定的 SHA-256。下载较大，完成后可复用缓存。模型位于 `android/app/src/main/assets/model-*/`，AAR 位于 `android/app/libs/`，下载清单位于 `artifacts/models.json`。这些生成文件均不进入 Git；正式 APK 已内置这些文件，普通用户无需下载模型。

模型及运行库遵循各自许可证，见 [第三方说明](../THIRD_PARTY_NOTICES.md) 和 [随 APK 提供的许可证](../android/app/src/main/assets/licenses/)。测试所需的额外自然语音可通过 `node scripts/fetch-speech.mjs --fixtures` 下载；部分设备测试还需要自行准备测试音频及专用接收端。

## Android 构建

准备好 Android SDK、JDK 和模型后，可启动调试版：

```bash
npm start
# Run in a separate terminal.
npm run android
```

正式版需要自行生成签名，签名别名为 `phonemic`。在项目根目录执行：

```bash
mkdir -p .signing
keytool -genkeypair -keystore .signing/release.keystore -alias phonemic -keyalg RSA -keysize 3072 -validity 10000
```

在 `.signing/key.properties` 写入对应密码：

```properties
storePassword=YOUR_STORE_PASSWORD
keyPassword=YOUR_KEY_PASSWORD
```

然后执行：

```bash
cd android
./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a
./gradlew :app:testReleaseUnitTest
```

Windows 使用 `gradlew.bat`。构建前配置 `ANDROID_HOME`，或在 `android/local.properties` 写入 `sdk.dir`。APK 位于 `android/app/build/outputs/apk/release/app-release.apk`。自行签名的 APK 无法直接覆盖安装官方发布版。

`.signing/` 已被 Git 忽略，请自行备份签名与密码，用于后续覆盖更新。

## Windows 接收端

在 Linux / macOS 交叉编译：

```bash
mkdir -p artifacts
cd companion
go mod download
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags='-s -w -H=windowsgui' -o ../artifacts/PhoneMIC-Desktop.exe .
```

在 Windows PowerShell 编译：

```powershell
New-Item -ItemType Directory -Force ..\artifacts | Out-Null
$env:GOOS = 'windows'
$env:GOARCH = 'amd64'
$env:CGO_ENABLED = '0'
go build -trimpath -ldflags='-s -w -H=windowsgui' -o ..\artifacts\PhoneMIC-Desktop.exe .
```

PowerShell 命令在 `companion/` 目录执行。接收端首次启动会配置随 Windows 启动，后续运行在系统托盘中。

## 原有远端构建脚本

`scripts/build-remote.ps1`、`scripts/bootstrap-ovita.sh` 和 `scripts/build-ovita.sh` 保留了项目原有的 OVITA 构建流程，包含特定的 SSH 别名、远端路径、JDK 和 SDK 路径。复用前按自己的环境调整；这些脚本并非通用环境安装器。

在已经配置对应 SSH 主机和环境的 PowerShell 中：

```powershell
.\scripts\build-remote.ps1
```

脚本上传源码、执行检查与编译、取回安装包和校验文件，并备份签名。生成文件保存在 `artifacts/` 和 `.signing/`。

## 真机与输入验证

```bash
adb install -r artifacts/PhoneMIC-1.0.0-arm64.apk
adb shell am start -n com.phonemic/.MainActivity
```

连接多台设备时，给 `adb` 添加 `-s YOUR_DEVICE_SERIAL`。Android 原生测试位于 `android/app/src/test/`，集成测试位于 `android/app/src/androidTest/`；后者需要真机权限、模型、对应测试音频和已配置的接收端。

Windows 接收端可使用 `--self-test <结果文件>` 创建独立测试输入框，验证中文、英文、换行和文字修订。`--test-window <结果文件>` 与 `--test-control-file <临时文件>` 用于真机集成测试；前者将输入限制在测试窗口。测试完成后删除临时控制文件，并以普通模式重新启动接收端。

## 目录

| 路径 | 内容 |
| --- | --- |
| `src/` | 界面、语音会话、设置、声纹与快捷键逻辑 |
| `android/` | Kotlin 采音、离线识别、声纹、TLS 和蓝牙 HID |
| `companion/` | Go Windows 托盘接收端、输入及协议测试 |
| `scripts/` | 模型下载、远端构建和设备调试工具 |
| `__tests__/` | React Native 与 TypeScript 测试 |
| `assets/branding/` | 应用图标及来源信息 |
| `docs/screenshots/` | 已有真机截图和完整图集 |
| `artifacts/` | 本地生成的安装包、日志与校验记录，不进入 Git |
