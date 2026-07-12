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

The script is idempotent. It installs ADB, Python dependencies and ARM64 Ollama; creates `inference_server/.env` with mode `0600`; pulls `gemma3:4b-it-q4_K_M` unless `OLLAMA_MODEL` is explicitly set; installs loopback-only systemd units; and performs a text, GPU-placement, memory, and health validation.

If `.env` does not exist, enter a strong token at the hidden prompt or leave it blank to generate one. Store a secure copy for workstation APK configuration. Do not print or commit it.

Successful validation writes non-secret evidence under the ignored `inference_server/jetson-validation/` directory. Setup fails unless `ollama ps` reports `100% GPU`, swap use remains at or below 512MB, and observed temperatures remain below 85°C; CPU fallback is never accepted.

## Required Ollama memory settings

The Jetson 8GB deployment must keep these Ollama service settings:

```ini
Environment="OLLAMA_FLASH_ATTENTION=1"
Environment="OLLAMA_KV_CACHE_TYPE=q8_0"
Environment="OLLAMA_CONTEXT_LENGTH=4096"
```

Check the active service environment with:

```bash
sudo systemctl show ollama --property=Environment --no-pager
```

## Reliable model warmup

After setup, reboot, or service changes, use this startup sequence:

```bash
sudo systemctl restart ollama
sleep 5

sudo sync
sudo sh -c 'echo 3 > /proc/sys/vm/drop_caches'
sudo sh -c 'echo 1 > /proc/sys/vm/compact_memory'

curl --fail --show-error --silent http://127.0.0.1:11434/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"model":"gemma3:4b-it-q4_K_M","messages":[{"role":"user","content":"Reply with only: ready"}],"stream":false,"keep_alive":-1,"options":{"num_ctx":4096,"num_predict":8}}'

ollama ps
sudo systemctl restart metax-gateway metax-adb-reverse
```

Expected `ollama ps`:

```text
gemma3:4b-it-q4_K_M ... 100% GPU ... 4096 ... Forever
```

If the first warmup fails with `cudaMalloc` or `out of memory`, repeat the exact block once. The Jetson can fail the first CUDA allocation even when q8 KV is configured correctly.

Only if it fails after two clean tries, use q4 KV as a temporary reliability fallback:

```bash
sudo tee /etc/systemd/system/ollama.service.d/90-metax-memory.conf >/dev/null <<'EOF'
[Service]
Environment="OLLAMA_FLASH_ATTENTION=1"
Environment="OLLAMA_KV_CACHE_TYPE=q4_0"
Environment="OLLAMA_CONTEXT_LENGTH=4096"
EOF
sudo systemctl daemon-reload
```

Then rerun the warmup block. Do not accept CPU fallback. To return to q8, remove `90-metax-memory.conf`; the installed `metax-local.conf` already sets q8.

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

Use representative clear, dense, rotated, weak, and unreadable banking documents. Record OCR latency, analysis latency, follow-up Q&A latency, output quality, peak RAM/swap, temperature, throttling, and `ollama ps` placement. Acceptance requires:

- document OCR plus analysis usually completes within the 3-5 second target for typical clear documents;
- valid document-analysis JSON for representative documents;
- no material analysis or Q&A regression against the previous baseline;
- no OOM, unsafe swap pressure, thermal throttling, or CPU placement;
- complete document and follow-up prompts fit the measured 4096-token context without truncation;
- unreadable/blank OCR fails locally in Android before Jetson inference.

If any criterion fails, stop deployment. Do not silently change models, enable CPU fallback, expose services, or add cloud AI.
