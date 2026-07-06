# Jetson Orin Nano setup

## Required hardware and manual preparation

- NVIDIA Jetson Orin Nano 8GB developer kit with at least 128GB NVMe and 30GB free.
- Supported power supply, active fan, and a data-capable phone-to-Jetson USB cable.
- Flash JetPack 6.2 using the `jetson-orin-nano-devkit-super` configuration. A package-only upgrade does not guarantee the 25W modes are installed.
- Boot from NVMe, select the 25W power mode, verify the fan spins, and copy this repository to the Jetson.
- Internet is needed only while installing packages and pulling the model.

The setup script rejects the wrong architecture/SKU, non-8GB memory, missing JetPack 6.2/CUDA, non-NVMe root, low disk space, unavailable 25W mode, or missing fan controls.

## Provision the Jetson

From the repository root:

```bash
bash inference_server/setup_jetson.sh
```

The script is idempotent. It installs ADB, Python dependencies and ARM64 Ollama; creates `inference_server/.env` with mode `0600`; pulls `gemma3:4b-it-q4_K_M` unless `OLLAMA_MODEL` is explicitly set; installs systemd units; and performs text, image, GPU-placement, memory, and health probes.

If `.env` does not exist, enter a strong token at the hidden prompt or leave it blank to generate one. Store a secure copy for workstation APK configuration. Do not print or commit it.

Successful validation writes non-secret evidence under the ignored `inference_server/jetson-validation/` directory. Setup fails unless `ollama ps` reports `100% GPU`, swap use remains at or below 512MB, and observed temperatures remain below 85°C; CPU fallback is never accepted.

## Phone and APK

On the phone:

1. Enable Developer options and USB debugging.
2. Connect directly to a Jetson USB host port with a data-capable cable.
3. Unlock the phone and accept its RSA authorization prompt for the Jetson. Select persistent authorization only if local policy allows it.
4. Leave USB tethering off; the transport is ADB reverse, not IP tethering.

On the workstation, securely copy the Jetson `.env` outside the repository, then run:

```powershell
.\inference_server\configure_android.ps1 -BaseUrl "http://127.0.0.1:8000" -EnvFile "C:\secure\jetson.env"
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

The ADB manager accepts exactly one authorized physical phone and continually restores `adb reverse tcp:8000 tcp:8000` after cable reconnection.

## Acceptance before deployment

Use representative clear, dense, rotated, weak, and unreadable banking documents. Record single-pass and forced-retry latency, output quality, peak RAM/swap, temperature, throttling, and `ollama ps` placement. Acceptance requires:

- p95 single-pass `/ground` below 50 seconds;
- two-attempt completion below 110 seconds;
- no OOM, unsafe swap pressure, thermal throttling, or CPU placement;
- no material transcription or analysis regression against the laptop baseline;
- complete document and follow-up prompts fit the measured 4096-token context without truncation;
- correct 422 behavior for unreadable documents and 503 behavior for model/timeouts.

If any criterion fails, stop deployment. Do not silently change models, extend Android timeouts, enable CPU fallback, or add cloud/OCR services.
