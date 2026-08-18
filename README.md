# AI Pocket Chat

[中文](#中文) | [English](#english)

---

## 中文

**AI Pocket Chat** 是一个完全在本地运行的原生安卓 AI 虚拟陪伴应用（Kotlin / Jetpack Compose）。AI 角色有自己的日程、情绪、记忆，会随相处慢慢成长；除了聊天，还有互动故事、朋友圈、日记、宠物、礼物、红包、语音通话、虚拟货币和一个可漫游的小世界。

### 特点

- **完全本地**：没有自建服务器，所有数据只存在你的设备上；通知是设备本地通知，不依赖任何推送服务。
- **自带 API Key**：接入任意 OpenAI 兼容的大模型服务（在应用内「设置」里填 API 地址与 Key），应用本身不内置、不代理任何模型服务。
- **无 GMS 依赖**：不使用 Firebase / Google Play 服务，国行安卓手机（HyperOS / MIUI 等）开箱即用。
- **端侧 AI 能力**：向量记忆检索（ONNX Runtime + bge-small-zh，离线）与语音识别（sherpa-onnx，离线），模型已随仓库附带，克隆即用。
- **AI 角色活人感**：角色有每日日程、情绪波动、长期/近期记忆、关系成长阶梯；会主动发朋友圈、写日记、送礼物、发起语音通话。
- **互动故事**：无限连载、章末选择、弧线大纲、导演台（亲笔指定走向）、世界书设定注入，写作风格与规则全部可自定义。

### 构建与运行

1. 安装最新稳定版 [Android Studio](https://developer.android.com/studio)（自带 JDK，无需单独安装）。
2. 克隆本仓库，用 Android Studio 打开项目根目录（首次打开会自动生成 `local.properties` 指向你的 SDK）。
3. 连接设备或启动模拟器，点 **Run ▶** 即可。

命令行构建（可选）：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS 示例
./gradlew :app:assembleDebug
```

- 要求：`minSdk 29`（Android 10+）、`compileSdk 37`。
- 首次启动后在「设置」中填入你的大模型 API 地址与 Key 即可开始聊天。
- 兑换码功能默认停用（需要自行配置签名密钥，普通使用不受影响）。
- 国产 ROM 上若需要可靠的定时通知，请按应用内引导把应用加入自启动白名单并关闭电池优化。

### 许可

本项目采用 **GNU GPL-3.0** 许可（见 [LICENSE](LICENSE)）：你可以自由使用、修改、分发，但分发修改版必须同样开源。

### 第三方组件致谢

| 组件 | 用途 | 许可 |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | 离线语音识别 | Apache-2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | 端侧模型推理 | MIT |
| [bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5) | 中文向量记忆 | MIT |
| [silero-vad](https://github.com/snakers4/silero-vad) | 语音活动检测 | MIT |
| [霞鹜文楷 Lite](https://github.com/lxgw/LxgwWenKai-Lite) | 楷体界面字体 | SIL OFL 1.1 |

### 声明

这是一个个人项目，按「原样」提供，不附带任何保证。应用内 AI 生成的内容由你自行接入的模型服务产生，请遵守你所在地区的法律法规与所用模型服务的使用条款。

---

## English

**AI Pocket Chat** is a fully local, native Android AI companion app (Kotlin / Jetpack Compose). AI characters have their own daily schedules, moods, and memories, and grow as you spend time with them. Beyond chat, there are interactive stories, a social feed, diaries, pets, gifts, red packets, voice calls, an in-app currency, and a small explorable world.

### Highlights

- **Fully local**: no backend server — all data lives on your device; notifications are local device notifications with no push service involved.
- **Bring your own API key**: connect any OpenAI-compatible LLM service (set the API base URL and key in Settings). The app ships with no built-in model service and proxies nothing.
- **No GMS required**: no Firebase / Google Play Services; works out of the box on Chinese-market Android phones (HyperOS / MIUI, etc.).
- **On-device AI**: offline vector memory retrieval (ONNX Runtime + bge-small-zh) and offline speech recognition (sherpa-onnx). Models are bundled in the repo — clone and run.
- **Lifelike characters**: daily schedules, mood swings, long/short-term memory, and a relationship growth ladder; characters proactively post to the feed, write diaries, send gifts, and start voice calls.
- **Interactive stories**: endless serials, end-of-chapter choices, arc outlines, a director panel for steering the plot in your own words, worldbook lore injection, and fully customizable writing styles and rules.

### Build & Run

1. Install the latest stable [Android Studio](https://developer.android.com/studio) (bundled JDK — nothing else to install).
2. Clone this repo and open the project root in Android Studio (a `local.properties` pointing to your SDK is generated on first open).
3. Connect a device or start an emulator and hit **Run ▶**.

Command-line build (optional):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS example
./gradlew :app:assembleDebug
```

- Requirements: `minSdk 29` (Android 10+), `compileSdk 37`.
- After first launch, enter your LLM API base URL and key in Settings to start chatting.
- The redeem-code feature is disabled by default (it needs a self-configured signing secret; normal usage is unaffected).
- On Chinese ROMs, follow the in-app guide to whitelist auto-start and disable battery optimization if you need reliable scheduled notifications.

### License

Licensed under **GNU GPL-3.0** (see [LICENSE](LICENSE)): you are free to use, modify, and redistribute, but distributed modified versions must remain open source under the same license.

### Third-party credits

| Component | Purpose | License |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Offline speech recognition | Apache-2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | On-device inference | MIT |
| [bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5) | Chinese vector memory | MIT |
| [silero-vad](https://github.com/snakers4/silero-vad) | Voice activity detection | MIT |
| [LXGW WenKai Lite](https://github.com/lxgw/LxgwWenKai-Lite) | Kai-style UI font | SIL OFL 1.1 |

### Disclaimer

This is a personal project provided “as is”, without warranty of any kind. AI-generated content in the app comes from the model service you connect; please comply with the laws of your region and the terms of the model service you use.
