# Jetson daily operation

## Start

1. Power the actively cooled Jetson and wait for boot.
2. If working headlessly, connect the Windows laptop to the Jetson over the direct Ethernet/ICS link and SSH into the Jetson.
3. Connect the unlocked, USB-debug-enabled phone directly to a Jetson USB host port with a data-capable cable.
4. Accept the Jetson RSA prompt if Android asks.
5. Verify readiness:

   ```bash
   systemctl is-active ollama metax-gateway metax-adb-reverse
   curl http://127.0.0.1:8000/health
   adb reverse --list
   ollama ps
   ```

`/health` must report `ready` and the configured model. `ollama ps` must report `100% GPU`. The reverse list must contain `tcp:8000 tcp:8000`. Open `http://127.0.0.1:8000/health` on the phone, then use the Android app.

The Windows laptop is only needed for headless administration, APK work, or provisioning. It is not in the runtime inference path. Do not use USB tethering or expose the loopback services on the LAN.

## Required Ollama memory settings

The Jetson 8 GB deployment keeps Ollama Flash Attention and q8 KV cache enabled for the Gemma 3 text-only pipeline. This was the reliable setting for loading Gemma at 4096 context on the device. Confirm the drop-in exists after rebuilds:

```bash
cat /etc/systemd/system/ollama.service.d/90-metax-memory.conf
```

Expected:

```ini
[Service]
Environment="OLLAMA_FLASH_ATTENTION=1"
Environment="OLLAMA_KV_CACHE_TYPE=q8_0"
```

If `ollama ps` is empty and Ollama logs show `cudaMalloc failed` while allocating the KV cache, restore this drop-in, run `sudo systemctl daemon-reload`, restart Ollama, clear page cache, and warm the model again.

## Cable and authorization recovery

The ADB service automatically handles ordinary disconnects. If it does not recover:

```bash
journalctl -u metax-adb-reverse -n 50 --no-pager
adb devices -l
```

Disconnect extra phones. For `unauthorized`, unlock the intended phone and approve the prompt. For `offline`, reconnect the data cable. Do not replace the loopback binds with LAN addresses.

## Service and model recovery

```bash
sudo systemctl restart ollama metax-gateway metax-adb-reverse
journalctl -u ollama -u metax-gateway -u metax-adb-reverse -n 100 --no-pager
bash inference_server/verify_jetson.sh
```

To intentionally change models, edit only ignored `inference_server/.env`, run `ollama pull <model>`, restart the gateway, and repeat the full device acceptance suite. The supported default is `gemma3:4b-it-q4_K_M`.

## Stop and offline use

```bash
sudo systemctl stop metax-adb-reverse metax-gateway ollama
```

Normal power-off is `sudo poweroff`; wait until SSH disconnects and the Jetson finishes shutting down before unplugging power. After provisioning, disconnect networking if desired; all inference and application behavior remains local.

## Next-day resume checklist

1. Power the Jetson and wait about two minutes.
2. If using the direct Windows Ethernet link, confirm Windows Ethernet is `192.168.137.1`, find the Jetson in `arp -a`, then SSH to its `192.168.137.x` address.
3. Verify the three services, loopback ports, `/health`, and `ollama ps`.
4. If the model is not loaded, warm it with a tiny `/api/chat` request using `num_ctx=4096` and confirm `100% GPU`.
5. Connect the phone to a Jetson USB host port, authorize USB debugging if prompted, restart `metax-adb-reverse`, and confirm `adb reverse --list`.
6. Launch the Android app and test document scan, document Q&A, and customer assistant. Document transcription now runs on-device in Android through bundled ML Kit OCR; the Jetson receives text only.

## Rollback

Keep the previous repository revision and `.env` backup locally. To roll back code, restore that known revision without touching face assets or the secret, rerun `bash inference_server/setup_jetson.sh`, and repeat validation. Never roll back by exposing either service, enabling cloud AI, or changing Meta DAT from 0.7.0.
