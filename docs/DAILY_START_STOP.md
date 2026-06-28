# Daily Start and Complete Shutdown

Use this guide only after completing [WINDOWS_LOCAL_SETUP.md](WINDOWS_LOCAL_SETUP.md), installing
the Android app, and successfully testing both customer Q&A and document scanning.

## What Must Stay Connected While Running

- The laptop charger.
- A data-capable USB cable between the laptop and phone.
- Bluetooth between the phone and Meta glasses.
- The PowerShell window running `start_local_ai.ps1 -UsbOnly`.

Wi-Fi, Ethernet, cellular data, Mobile Hotspot, and USB tethering remain off.

## Switch On the System

### 1. Prepare the laptop

1. Connect the laptop charger.
2. Unplug Ethernet if one is connected.
3. Select the network/speaker/battery area on the Windows taskbar.
4. Confirm the **Wi-Fi** tile is off.
5. Open **Start > Settings > Network & internet > Mobile hotspot**.
6. Confirm **Mobile hotspot** is off.
7. Confirm Windows power mode is **Best performance**.

### 2. Prepare the phone and glasses

1. Connect the phone with the same data-capable USB cable used during setup.
2. Unlock the phone.
3. If **Allow USB debugging?** appears, select **Always allow from this computer** and tap
   **Allow**.
4. Enable airplane mode.
5. Confirm Wi-Fi and cellular data are off.
6. Manually enable Bluetooth.
7. Confirm the glasses connect to the phone.
8. Confirm **USB tethering** is off.
9. Keep the phone connected and unlocked until startup finishes.

### 3. Start Ollama

1. Open the Windows Start menu.
2. Search for **Ollama**.
3. Open it.
4. Confirm the Ollama icon appears in the Windows system tray.
5. Do not sign in and do not select a cloud model.

### 4. Start the USB gateway

1. Open the repository folder in File Explorer.
2. Right-click an empty area and select **Open in Terminal**.
3. Run:

   ```powershell
   Set-ExecutionPolicy -Scope Process Bypass
   .\inference_server\start_local_ai.ps1 -UsbOnly
   ```

4. Wait while the script checks the phone, creates the USB connection, loads Gemma into GPU
   memory, and loads PaddleOCR into CPU memory.
5. Confirm the terminal displays:

   ```text
   ADB reverse tunnel: phone tcp:8000 -> laptop tcp:8000
   Phone health URL: http://127.0.0.1:8000/health
   Gateway bind address: 127.0.0.1:8000
   ```

6. Do not close this terminal.

### 5. Confirm the system is ready

1. On the phone, open Chrome.
2. Enter exactly:

   ```text
   http://127.0.0.1:8000/health
   ```

3. Confirm `status`, `gateway`, `chat`, and `ground` say `ready`.
4. Chrome is only being used for this check. Close it afterward if desired.
5. Open the Meta glasses Android app.
6. Start the glasses stream.
7. The system is now ready for customer Q&A and document scans.

## While the System Is Running

- Keep the gateway PowerShell window open.
- Keep the USB cable connected.
- Keep phone Bluetooth enabled.
- Do not enable USB tethering.
- Do not start an Android emulator.
- Do not close Ollama from the system tray.
- Document scans can use substantial CPU for tens of seconds; customer/document Q&A uses GPU.
- Hold documents close enough that text fills most of the glasses view and use bright, steady
  lighting. The captured document photo is approximately 480x640.

## Normal Complete Shutdown

Follow this order so the stream, CPU process, GPU model, and USB helper all stop cleanly.

### 1. Stop the Android activity

1. In the Android app, tap **End** if a document session is active.
2. Tap **Stop streaming**.
3. Wait until the stream stops.
4. Return to the phone home screen.
5. Open recent apps and swipe the app away.

### 2. Stop the gateway and OCR CPU process

1. Return to the PowerShell window running the gateway.
2. Press `Ctrl+C` once.
3. Wait until the normal PowerShell prompt returns.
4. Confirm the terminal prints:

   ```text
   USB reverse tunnel removed.
   ```

The Python/FastAPI process and the in-memory PaddleOCR pipeline stop here. OCR is no longer using
CPU or RAM.

### 3. Unload Gemma from GPU memory

1. In PowerShell, run:

   ```powershell
   ollama stop gemma3:4b-it-q4_K_M
   ollama ps
   ```

2. Confirm `ollama ps` shows only column headings and no loaded model.

### 4. Quit Ollama completely

1. Find the Ollama icon in the Windows system tray.
2. Right-click it.
3. Select **Quit Ollama** or **Quit**.

Gemma is now unloaded and the Ollama background server is no longer running.

### 5. Stop ADB completely

The startup script removes port 8000 automatically. To also stop Android Debug Bridge's small
background helper, run:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" kill-server
```

You can now close PowerShell, close Android Studio, and unplug the phone.

### 6. Optional phone/glasses shutdown

1. Disable phone Bluetooth if the glasses are no longer needed.
2. Put the glasses away or power them off normally.
3. Leave airplane mode enabled if the phone must remain isolated.

## Verify Everything Has Stopped

### Check CPU processes

Open PowerShell and run:

```powershell
Get-Process python,ollama,adb -ErrorAction SilentlyContinue
```

No `python`, `ollama`, or `adb` row should appear from this system. Other unrelated Python
applications may have their own processes.

### Check the gateway ports

Run:

```powershell
Get-NetTCPConnection -LocalPort 8000,11434 -State Listen -ErrorAction SilentlyContinue
```

No result means neither the FastAPI gateway nor Ollama is listening.

### Check GPU memory

Run:

```powershell
nvidia-smi
```

`ollama.exe` must not appear. Windows, Codex, a browser, or other desktop applications may still
use a small amount of GPU memory; that is unrelated to the local models.

## Emergency Cleanup

Use this only if the gateway terminal was closed unexpectedly or `Ctrl+C` did not work.

1. Open PowerShell in the repository.
2. Find the process listening on gateway port 8000:

   ```powershell
   $gateway = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue
   $gateway
   ```

3. If a row appears, stop only that owning process:

   ```powershell
   $gateway | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
   ```

4. Remove the USB mapping and stop ADB:

   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" reverse --remove tcp:8000
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" kill-server
   ```

5. Unload and quit Ollama:

   ```powershell
   ollama stop gemma3:4b-it-q4_K_M
   ```

6. Quit Ollama from the system tray.
7. Repeat the verification checks above.

## After a USB Disconnect

Disconnecting USB destroys the phone-to-laptop path. To resume:

1. Stop the old gateway with `Ctrl+C` if it is still running.
2. Reconnect and unlock the phone.
3. Approve USB debugging if prompted.
4. Run the normal startup command again:

   ```powershell
   .\inference_server\start_local_ai.ps1 -UsbOnly
   ```
