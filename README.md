<img align="left" width="80" height="80"
src=".github/repo_icon.png" alt="App icon">

# Gemma FlorisBoard

An on-device AI-enhanced fork of [FlorisBoard](https://github.com/florisboard/florisboard), the free and open-source keyboard for Android 8.0+. This fork replaces cloud-based AI with fully offline, on-device inference using [llama.cpp](https://github.com/ggerganov/llama.cpp) and a Qwen2.5 1.5B GGUF model — no internet, no API keys, no data leaving your device.

## What Makes This Fork Different

| Feature | Upstream FlorisBoard | This Fork |
|---|---|---|
| **AI proofread & polish** | Not available | On-device via llama.cpp + Qwen2.5 1.5B |
| **Voice transcription** | Not available | On-device streaming with silence detection |
| **Glide typing engine** | Statistical only | Neural + statistical (auto-fallback) |
| **Model download** | N/A | Built-in GGUF model manager |
| **Internet requirement** | None | None (all AI runs locally) |
| **Privacy for AI features** | N/A | Full — zero data leaves the device |

## On-Device AI Features

### Text Polish & Proofread
Select any text you've typed and run it through the local AI. Grammar fixes, spelling corrections, and clarity improvements — all processed on-device via the `Smartbar` quick action. The AI runs a Qwen2.5 1.5B GGUF model quantized to fit within Android's memory constraints.

### Streaming Voice Transcription
Tap-to-talk voice input with automatic silence detection. The transcriber listens for speech, stops when you pause, and inserts the transcribed text directly into the input field. Configurable silence thresholds prevent premature cutoffs during natural speech pauses.

### Neural Glide Typing
A native glide typing classifier using a trained model runs alongside the standard statistical engine. The keyboard defaults to neural when available, with automatic fallback to statistical mode to prevent crash loops.

## Architecture

```
┌────────────────────────────────────────────┐
│ Android Keyboard (FlorisBoard fork)         │
│                                            │
│  ┌──────────────────────────────────────┐  │
│  │ Smartbar Quick Actions               │  │
│  │  ├─ AI Proofread (polish text)       │  │
│  │  └─ Voice Input (transcribe)         │  │
│  └──────────────┬───────────────────────┘  │
│                 │                           │
│  ┌──────────────▼───────────────────────┐  │
│  │ GemmaManager.kt                      │  │
│  │  ├─ localPolish() — llama.cpp JNI   │  │
│  │  └─ transcribe() — streaming audio  │  │
│  └──────────────┬───────────────────────┘  │
│                 │                           │
│  ┌──────────────▼───────────────────────┐  │
│  │ libnative (JNI bridge)               │  │
│  │  ├─ llama_jni.cpp — C++ inference   │  │
│  │  │   (llama.cpp submodule)          │  │
│  │  └─ LlamaInference.kt — Kotlin API  │  │
│  └──────────────┬───────────────────────┘  │
│                 │                           │
│  ┌──────────────▼───────────────────────┐  │
│  │ ModelManager.kt                      │  │
│  │  └─ GGUF download, cache, verify     │  │
│  └──────────────────────────────────────┘  │
│                                            │
│  ┌──────────────────────────────────────┐  │
│  │ Glide Typing                         │  │
│  │  ├─ NativeGlideTypingClassifier      │  │
│  │  └─ StatisticalGlideTypingClassifier │  │
│  └──────────────────────────────────────┘  │
└────────────────────────────────────────────┘
```

### Inference Engine

- **Backend**: [llama.cpp](https://github.com/ggerganov/llama.cpp) (git submodule)
- **Model**: Qwen2.5 1.5B GGUF (quantized, ~1 GB)
- **Bridge**: Custom JNI layer (`llama_jni.cpp`) with Kotlin wrapper (`LlamaInference.kt`)
- **Context**: 512-token window, KV cache cleared between calls
- **Prompt buffer**: Dynamically allocated (ChatML template-aware) to handle variable-length input

## Original FlorisBoard Features

All upstream features remain intact:

- Integrated clipboard manager / history
- Advanced theming support and customization
- Integrated extension support
- Emoji keyboard / history / suggestions
- Full privacy — no network calls, no analytics

## Installation

Build from source using Android Studio or Gradle:

```bash
# Clone with submodules
git clone --recurse-submodules https://github.com/arshad1416/gemma-florisboard.git
cd gemma-florisboard

# Build debug APK
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

> **Note**: First launch after install will download the ~1 GB GGUF model. This requires Wi-Fi and ~2 GB of free storage. Subsequent AI feature usage is fully offline.

## Repository Structure

```
gemma-florisboard/
├── app/src/main/java/dev/patrickgold/florisboard/
│   └── gemma/                          # AI features (fork-specific)
│       ├── GemmaManager.kt             # Core AI: polish, proofread, transcribe
│       ├── ModelManager.kt             # GGUF model download & cache
│       ├── StreamingTranscriber.kt     # Voice input with silence detection
│       ├── NeuralGemmaGlideClassifier.kt
│       ├── PersonalDictionary.kt
│       └── ContactNamesProvider.kt
├── lib/native/src/main/
│   ├── llama.cpp                       # llama.cpp submodule
│   └── rust/src/llama_jni.cpp          # JNI bridge to llama.cpp
├── lib/native/.../LlamaInference.kt    # Kotlin wrapper for JNI
├── app/.../text/gestures/
│   ├── NativeGlideTypingClassifier.kt  # Neural glide typing
│   └── StatisticalGlideTypingClassifier.kt
└── ...                                 # Upstream FlorisBoard files
```

## Bug Fixes Applied (vs Upstream)

Beyond feature additions, this fork includes fixes for on-device inference stability:

- **Dynamic prompt buffer**: Fixed 4096-byte buffer replaced with `llama_chat_apply_template()` dynamic sizing — prevents silent failures on text longer than ~2 sentences
- **KV cache clearing**: `llama_kv_cache_clear()` called before each inference to prevent context window exhaustion across calls
- **Error propagation**: AI failures now surface actual error messages instead of silently showing "No changes needed"
- **Voice timeout tuning**: Silence thresholds increased to 10s/8s with minimum listening duration to prevent premature auto-stop

## Upstream

This project is a fork of [FlorisBoard](https://github.com/florisboard/florisboard) by [Patrick Gold](https://github.com/patrickgold). All original FlorisBoard features, theming, and privacy-respecting design remain intact. See [upstream README](https://github.com/florisboard/florisboard) for the full feature set.

## License

Apache 2.0 — same as upstream FlorisBoard. See [LICENSE](LICENSE).
