# Meta glasses banking assistant — Jetson local inference

All AI inference runs locally on an NVIDIA Jetson Orin Nano 8GB. The Windows workstation is used only to build/install the Android APK and, if needed, transfer this repository.

```text
Meta glasses -> Android phone -> USB/ADB reverse -> Jetson 127.0.0.1:8000
                                                   FastAPI + Pillow
                                                        |
                                              Ollama 127.0.0.1:11434
                                              gemma3:4b-it-q4_K_M
```

The Android contracts remain `POST /ground` and `POST /chat` with `X-Local-Token`. Neither service listens on the LAN, and there is no cloud fallback or OCR pipeline.

## Start here

1. Complete the physical and flashing prerequisites in [docs/JETSON_LOCAL_SETUP.md](docs/JETSON_LOCAL_SETUP.md).
2. On the Jetson, run `bash inference_server/setup_jetson.sh`.
3. Configure and build the APK on the workstation:

   ```powershell
   .\inference_server\configure_android.ps1 -BaseUrl "http://127.0.0.1:8000" -EnvFile "C:\secure\jetson.env"
   .\gradlew.bat :app:assembleDebug
   ```

4. Follow [docs/DAILY_START_STOP.md](docs/DAILY_START_STOP.md) for normal use and recovery.

The token file is ignored by Git. Never paste `LOCAL_AI_TOKEN` into logs, documentation, tests, or commits. `OLLAMA_MODEL` remains configurable, but the supported and tested default is `gemma3:4b-it-q4_K_M`.

## Development checks

```bash
python -m pytest inference_server/tests -q
```

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Meta Device Access Toolkit remains pinned to 0.7.0. Face recognition, speech recognition, TTS, customer data, document prompts, and glasses streaming/capture are outside this migration.
