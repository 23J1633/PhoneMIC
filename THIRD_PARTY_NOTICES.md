# 第三方组件

- React Native 0.85.3、React：MIT；[React Native 源码](https://github.com/facebook/react-native)。
- sherpa-onnx 2.0.1：Apache-2.0；[源码与许可](https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1)。Android AAR 来源与 SHA-256 保存在 `artifacts/models.json`。
- Qwen3-ASR-0.6B：Apache-2.0；[Qwen 官方源码](https://github.com/QwenLM/Qwen3-ASR)、[sherpa-onnx 部署说明](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/index.html)、[维护者发布的 INT8 权重](https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25)。下载器逐文件验证 SHA-256，模型和分词器内置 APK，在手机本地运行。
- ONNX Runtime：MIT；[源码与许可](https://github.com/microsoft/onnxruntime)，随 sherpa-onnx Android AAR 提供。
- Silero VAD：MIT；[源码与许可](https://github.com/snakers4/silero-vad)、[sherpa-onnx 导出模型](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html)。约 644 KB 权重内置 APK，用于人声检测。
- 3D-Speaker CAM++ 中英文 advanced：Apache-2.0；[源码与许可](https://github.com/modelscope/3D-Speaker)、[模型发布](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models)。约 28.3 MB 权重内置 APK，用于本地声纹录入和匹配。声纹测试使用同一模型发布页的 fangjun／leijun 示例录音，仅包含在测试包中。
- react-native-svg、react-native-safe-area-context：MIT。
- Gorilla WebSocket 1.5.3：BSD-2-Clause；[源码与许可](https://github.com/gorilla/websocket)。
- grandcat/zeroconf 1.0.0：MIT；[源码与许可](https://github.com/grandcat/zeroconf)，用于 Windows 局域网 DNS-SD 服务发现。
- uniseg 0.4.7：MIT；[源码与许可](https://github.com/rivo/uniseg)。

完整依赖版本与校验信息见 `package-lock.json` 和 `companion/go.sum`。Android 集成测试中的独立中英文 PCM 使用 Windows 系统语音合成生成；自然混合语音来自 [Zipformer Apache-2.0 模型](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20) 的 `test_wavs/1.wav`（SHA-256：`8bfb42c963e623ebab31b81ff4404867d07d3102507c87ac14577c4c61663b8c`）。测试语音均不打包进正式 APK。
