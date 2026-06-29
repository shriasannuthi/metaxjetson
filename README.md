# Local Meta Glasses Assistant

An Android application for Meta glasses whose runtime AI processing stays on a local Windows
laptop. The phone and laptop communicate over a USB cable using ADB reverse forwarding, so Wi-Fi,
Ethernet, cellular data, hotspots, and USB tethering are unnecessary during operation.

## Current Architecture

```text
Meta glasses -> Bluetooth -> Android app
                              |
                              | http://127.0.0.1:8000 over USB / ADB reverse
                              v
                         FastAPI gateway on laptop
                              |
                              | /chat and /ground
                              v
                    Ollama -> Qwen3-VL 8B -> RTX 4060
```

- `/ground` mildly enhances the captured document image and sends it to Qwen3-VL, retrying once
  when the first transcription is weak.
- `/chat` handles customer Q&A, the displayed document analysis, and document follow-up Q&A.
- The document Q&A prompt contains the transcription, prior session turns, and current question.
  The displayed summary is not used as evidence.
- Android speech recognition and face matching remain on the phone.
- The gateway and Ollama are loopback-only, authenticated, and have no cloud fallback.
- No document or language model performs inference on the CPU. Python still uses a small amount of
  CPU for the gateway, image validation, and USB request handling.

## Documentation

Read these in order:

1. [Windows setup](docs/WINDOWS_LOCAL_SETUP.md): fresh-system project download, Android Studio and
   SDK installation, GitHub Packages authentication, Ollama/Python/Qwen3-VL setup, Android
   installation, and first offline test.
2. [Daily start and stop](docs/DAILY_START_STOP.md): exact startup and complete shutdown after setup.
3. [Architecture overview](docs/ARCHITECTURE_OVERVIEW.md): where each component runs and how data
   moves through the system.

## Starting After Setup

Connect and authorize the phone over USB, start Ollama, and run from the repository:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\inference_server\start_local_ai.ps1 -UsbOnly
```

Keep that PowerShell window and the USB cable connected. On the phone, open
`http://127.0.0.1:8000/health`, confirm every component is ready, close Chrome, and open the app.

## Local Services

| Service | Address | Main hardware | Purpose |
| --- | --- | --- | --- |
| FastAPI gateway | `127.0.0.1:8000` | Lightweight CPU | Authenticates and routes local requests |
| Ollama/Qwen3-VL | `127.0.0.1:11434` | RTX 4060 | Text, vision transcription, analysis, and Q&A |
| ADB reverse | USB port mapping | Phone and laptop | Maps phone localhost to laptop localhost |

## Repository Layout

- `app/`: Android application.
- `inference_server/`: FastAPI gateway, Ollama runtime, scripts, and tests.
- `docs/`: setup, daily operation, and architecture documentation.
- `local.properties`: ignored Android SDK, GitHub package token, local gateway URL, and token.
- `inference_server/.env`: ignored gateway token and Ollama configuration.

## Building and Testing

The Meta Maven dependency may require internet during a build if it is not cached. The installed
app requires no internet at runtime.

```powershell
.\inference_server\.venv\Scripts\python.exe -m pip install -r inference_server\requirements-test.txt
.\inference_server\.venv\Scripts\python.exe -m pytest inference_server\tests -q

$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

## License

This source code is licensed under the license found in `LICENSE`.
