# Pair actual Meta glasses with this app

Use this when moving from MockDeviceKit to real Meta/Ray-Ban Meta glasses.

You do **not** install this APK onto the glasses. Install this app only on the Android phone.

"Companion app" means the **Meta AI app** on the phone. On some older setups it may still be called Meta View.

## 1. Pair the glasses in the Meta AI app

1. Charge the glasses and case.
2. Turn on Bluetooth on the Android phone.
3. Open the **Meta AI** app.
4. Sign in.
5. Go to the glasses/devices section.
6. Tap the option to add or pair glasses.
7. Keep the glasses near the phone.
8. Accept Bluetooth/pairing prompts.
9. If Meta AI asks for a glasses firmware update, install it.
10. Confirm Meta AI shows the glasses as connected.

## 2. Start Jetson services

SSH into the Jetson:

```powershell
ssh user@192.168.137.57
```

Run:

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
sudo systemctl start metax-gateway metax-adb-reverse
```

If the warmup fails once with `cudaMalloc` or `out of memory`, repeat the exact same block once.

Do not switch to q4 KV unless this fails after two clean tries.

Check:

```bash
systemctl is-active ollama metax-gateway metax-adb-reverse
curl --fail --show-error --silent http://127.0.0.1:8000/health
ollama ps
```

Expected:

```text
active
active
active
```

Health should say `ready`, and `ollama ps` should show `100% GPU`, context `4096`, and `Forever`.

## 3. Connect the phone to the Jetson

1. Connect the phone directly to a Jetson USB 3.0 port.
2. Use a data-capable USB cable.
3. Unlock the phone.
4. If USB debugging asks for permission, tap **Allow**.
5. If the Jetson RSA prompt appears, tap **Allow**.

On the Jetson, run:

```bash
adb devices -l
adb reverse --list
```

Expected:

- one phone listed as `device`;
- reverse rule shows `tcp:8000 tcp:8000`.

If reverse is missing:

```bash
sudo systemctl restart metax-adb-reverse
sleep 8
adb reverse --list
```

## 4. Check phone-to-Jetson health

On the Android phone, open Chrome.

Go to:

```text
http://127.0.0.1:8000/health
```

Expected: JSON showing `ready`.

Do not continue until this works.

## 5. Disable MockDeviceKit

In this app:

1. Open the debug/bug button.
2. Open MockDeviceKit.
3. Tap **Disable MockDeviceKit**.
4. Force close and reopen the app if needed.

## 6. Connect this app to the real glasses

1. Open this app on the phone.
2. Tap **Connect my glasses**.
3. Allow requested permissions:
   - Nearby devices / Bluetooth;
   - Camera;
   - Microphone;
   - Meta/DAT permission prompts.
4. If Meta AI opens, complete the prompt there.
5. Return to this app.
6. Wait for the connected/non-streaming screen.

## 7. If update buttons appear

If the app shows a firmware update:

1. Tap the firmware update button.
2. Complete the update in Meta AI.
3. Return to this app.

If the app shows **Update app on glasses**:

1. Tap **Update app on glasses**.
2. Complete the Meta/DAT update in Meta AI.
3. Return to this app.
4. Retry streaming.

This is not sideloading your APK. It is Meta's own glasses-side DAT update.

## 8. Start streaming

1. Tap **Start streaming**.
2. Allow camera permission if prompted.
3. Wait for the live preview.
4. Tap the document scan button.
5. Confirm OCR, analysis, and Q&A work.

## 9. If something fails

### Phone cannot reach health URL

Fix Jetson/ADB reverse:

```bash
adb devices -l
adb reverse --list
sudo systemctl restart metax-adb-reverse
```

### App cannot find glasses

1. Open Meta AI.
2. Confirm glasses are connected there.
3. Disable MockDeviceKit.
4. Reopen this app.
5. Tap **Connect my glasses** again.

### Local AI request failed

Check Jetson:

```bash
systemctl is-active ollama metax-gateway metax-adb-reverse
curl --fail --show-error --silent http://127.0.0.1:8000/health
ollama ps
```

### Scan works but OCR is bad

Retake the photo with:

- better lighting;
- less blur;
- the full document in frame;
- less glare.

## Final checklist

- [ ] Glasses connected in Meta AI.
- [ ] MockDeviceKit disabled.
- [ ] Phone connected to Jetson by USB.
- [ ] `adb devices -l` shows one authorized phone.
- [ ] `adb reverse --list` shows `tcp:8000 tcp:8000`.
- [ ] Phone Chrome opens `http://127.0.0.1:8000/health`.
- [ ] App **Connect my glasses** succeeds.
- [ ] App **Start streaming** succeeds.
- [ ] Document scan succeeds.
