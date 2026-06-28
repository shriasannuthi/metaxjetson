# Local Meta Glasses Assistant

An Android application that connects to Meta AI glasses while keeping runtime AI processing on a
local Windows laptop. Customer data, document images, prompts, and model responses do not need an
internet connection.

## Current Architecture

```text
Meta glasses -> Bluetooth -> Android app
                              |
                              | http://127.0.0.1:8000 over USB / adb reverse
                              v
                         FastAPI gateway on laptop
                              |-- /chat   -> Ollama -> Gemma 3 4B Q4 -> RTX 4060
                              `-- /ground -> PP-StructureV3 + PP-OCRv5 -> Ryzen CPU
```

- The phone remains connected with a data-capable USB cable during AI operations.
- Wi-Fi, Ethernet, cellular data, mobile hotspots, and USB tethering are not used at runtime.
- Bluetooth remains enabled on the phone because the glasses require it.
- Android speech recognition and face matching run on the phone.
- Ollama is loopback-only on port `11434`; the FastAPI gateway is loopback-only on port `8000`.
- The gateway requires a private token and has no cloud fallback.

## Features

- Pair and stream from Meta glasses offline.
- Capture photos and recognize enrolled faces locally.
- Use offline Android speech recognition for assistant questions and the "Hey Meta scan" trigger.
- Answer customer and document questions with local Gemma 3 through Ollama.
- Extract document text and structure with local PP-StructureV3 and PP-OCRv5.
- Remove Paddle-generated image references and fall back to recognized OCR lines so Gemma never
  receives an empty internal image filename as document evidence.
- Disable the affected oneDNN CPU path to avoid PaddlePaddle 3.3.0's Windows inference regression.

## Documentation

Read these documents in this order:

1. [Windows setup](docs/WINDOWS_LOCAL_SETUP.md): one-time installation and first complete test.
2. [Daily start and stop](docs/DAILY_START_STOP.md): click-by-click operation after setup.
3. [Architecture overview](docs/ARCHITECTURE_OVERVIEW.md): where every component runs and how data
   moves through the system.

## One-Time Setup

The complete procedure is in [docs/WINDOWS_LOCAL_SETUP.md](docs/WINDOWS_LOCAL_SETUP.md). In short,
it installs Ollama and Python 3.11, downloads the local models, configures Android for
`127.0.0.1:8000`, installs the app, creates the USB tunnel, and verifies the system with all
external networks disabled.

## Starting After Setup

Connect and authorize the phone over USB, start Ollama, then run from the repository:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\inference_server\start_local_ai.ps1 -UsbOnly
```

Keep that PowerShell window and the USB cable connected. On the phone, check
`http://127.0.0.1:8000/health`, then open the Android app. Use the full checklist in
[docs/DAILY_START_STOP.md](docs/DAILY_START_STOP.md).

## Main Local Services

| Service | Address | Hardware | Purpose |
| --- | --- | --- | --- |
| FastAPI gateway | `127.0.0.1:8000` | CPU | Authenticates and routes `/chat`, `/ground`, and `/health` |
| Ollama | `127.0.0.1:11434` | RTX 4060 | Hosts `gemma3:4b-it-q4_K_M` |
| PaddleOCR | Inside the gateway process | Ryzen CPU | Reads document photos and returns grounded text |
| ADB reverse | USB port mapping | Phone and laptop | Carries phone localhost traffic to laptop localhost |

## Repository Layout

- `app/`: Android application.
- `inference_server/`: FastAPI gateway, model runtime, setup/start scripts, and server tests.
- `docs/`: setup, operations, and architecture documentation.
- `local.properties`: ignored Android SDK, GitHub package token, local gateway URL, and gateway
  token configuration.
- `inference_server/.env`: ignored gateway token and local model settings.

## Building Android

1. Install Android Studio and Android SDK Platform-Tools.
2. Add the GitHub package token required for Meta's Maven package to ignored `local.properties`.
3. Run `configure_android.ps1` as documented in the Windows setup guide.
4. Select **File > Sync Project with Gradle Files** in Android Studio.
5. Select the authorized physical phone and choose **Run > Run 'app'**.

The Meta Maven dependency can require internet while building if it is not cached. The installed
app does not require internet at runtime.

## Verification

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Gateway tests are in `inference_server/tests`. The Windows setup script creates the Python
environment needed to run them.

## License

This source code is licensed under the license found in the `LICENSE` file in the repository root.
