# Jetson daily operation

## Start

1. Power the actively cooled Jetson and wait for boot.
2. Connect the unlocked, USB-debug-enabled phone with its data cable.
3. Accept the Jetson RSA prompt if Android asks.
4. Verify readiness:

   ```bash
   systemctl is-active ollama metax-gateway metax-adb-reverse
   curl http://127.0.0.1:8000/health
   adb reverse --list
   ollama ps
   ```

`/health` must report `ready` and the configured model. `ollama ps` must report `100% GPU`. The reverse list must contain `tcp:8000 tcp:8000`. Open `http://127.0.0.1:8000/health` on the phone, then use the Android app.

No laptop, terminal window, network connection, or USB tethering is required at runtime.

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

To intentionally change models, edit only ignored `inference_server/.env`, run `ollama pull <model>`, restart the gateway, and repeat the full device acceptance suite. The supported default remains `gemma3:4b-it-q4_K_M`.

## Stop and offline use

```bash
sudo systemctl stop metax-adb-reverse metax-gateway ollama
```

Normal power-off is `sudo poweroff`. After provisioning, disconnect networking; all inference and application behavior remains local.

## Rollback

Keep the previous repository revision and `.env` backup locally. To roll back code, restore that known revision without touching face assets or the secret, rerun `bash inference_server/setup_jetson.sh`, and repeat validation. Never roll back by exposing either service, enabling cloud AI, or changing Meta DAT from 0.7.0.
