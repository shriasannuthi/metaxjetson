# Daily Start and Complete Shutdown

Use this only after completing [WINDOWS_LOCAL_SETUP.md](WINDOWS_LOCAL_SETUP.md), installing the
current Android app, and successfully testing customer Q&A and Qwen3-VL document transcription.

## What Must Stay Connected

- Laptop charger.
- Data-capable USB cable between phone and laptop.
- Bluetooth between phone and Meta glasses.
- PowerShell window running `start_local_ai.ps1 -UsbOnly`.
- Ollama in the Windows system tray.

Wi-Fi, Ethernet, cellular data, Mobile Hotspot, and USB tethering remain off.

## Switch On

### 1. Prepare the laptop

1. Connect the charger.
2. Unplug Ethernet.
3. Open Windows Quick Settings and turn Wi-Fi off.
4. Open **Settings > Network & internet > Mobile hotspot** and confirm it is off.
5. Confirm **Power mode** is **Best performance**.

### 2. Prepare the phone and glasses

1. Connect the phone with the data-capable USB cable.
2. Unlock the phone.
3. Approve **Allow USB debugging?** if prompted.
4. Enable airplane mode.
5. Confirm Wi-Fi and cellular data are off.
6. Manually enable Bluetooth.
7. Confirm the glasses connect.
8. Confirm USB tethering is off.

### 3. Start Ollama

1. Open Start and search for **Ollama**.
2. Open it.
3. Confirm the Ollama icon appears in the system tray.
4. Do not sign in or select a cloud model.

### 4. Start the USB gateway

1. Open the repository folder in File Explorer.
2. Right-click an empty area and select **Open in Terminal**.
3. Run:

   ```powershell
   Set-ExecutionPolicy -Scope Process Bypass
   .\inference_server\start_local_ai.ps1 -UsbOnly
   ```

4. Wait while the script checks the phone, creates the USB mapping, preloads Qwen3-VL into GPU
   memory, and starts FastAPI.
5. Confirm it prints:

   ```text
   ADB reverse tunnel: phone tcp:8000 -> laptop tcp:8000
   Phone health URL: http://127.0.0.1:8000/health
   Gateway bind address: 127.0.0.1:8000
   ```

6. Keep this terminal open.

### 5. Confirm readiness

1. Open Chrome on the phone.
2. Open `http://127.0.0.1:8000/health`.
3. Confirm `status`, `gateway`, `chat`, and `ground` say `ready`.
4. Close Chrome.
5. Open the Meta glasses app and start streaming.

The system is ready for customer Q&A and document scans.

## While Running

- Keep the gateway terminal open and USB connected.
- Keep phone Bluetooth enabled.
- Do not enable USB tethering or start an Android emulator.
- Do not quit Ollama.
- Qwen3-VL handles text and image inference on the RTX 4060.
- The Python gateway uses only light CPU for requests and image validation.
- Fill the glasses view with the document and avoid motion, low light, and glare.

## Normal Complete Shutdown

### 1. Stop the Android activity

1. Tap **End** if a document session is open.
2. Tap **Stop streaming**.
3. Return to the phone home screen.
4. Open recent apps and swipe the app away.

### 2. Stop FastAPI and remove the USB mapping

1. Return to the PowerShell window running the gateway.
2. Press `Ctrl+C` once.
3. Wait for the normal PowerShell prompt.
4. Confirm it prints `USB reverse tunnel removed.`

The Python gateway is now stopped. No model runs inside Python.

### 3. Unload Qwen3-VL from the GPU

Run:

```powershell
ollama stop qwen3-vl:8b
ollama ps
```

Confirm `ollama ps` shows only headings and no loaded model.

### 4. Quit Ollama

1. Find the Ollama system-tray icon.
2. Right-click it.
3. Select **Quit Ollama** or **Quit**.

### 5. Stop ADB

Run:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" kill-server
```

Close PowerShell and Android Studio, then unplug the phone.

### 6. Optional device shutdown

1. Disable phone Bluetooth if the glasses are no longer needed.
2. Power off or store the glasses normally.
3. Leave airplane mode enabled if the phone must stay isolated.

## Verify Everything Stopped

### Processes

```powershell
Get-Process python,ollama,adb -ErrorAction SilentlyContinue
```

No process belonging to this system should appear.

### Ports

```powershell
Get-NetTCPConnection -LocalPort 8000,11434 -State Listen -ErrorAction SilentlyContinue
```

No result means FastAPI and Ollama are no longer listening.

### GPU

```powershell
nvidia-smi
```

`ollama.exe` must not appear. Windows applications may still use unrelated GPU memory.

## Emergency Cleanup

Use this only when normal shutdown fails.

1. Open PowerShell.
2. Find and stop only the process listening on port 8000:

   ```powershell
   $gateway = Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue
   $gateway | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
   ```

3. Remove the USB rule and stop ADB:

   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" reverse --remove tcp:8000
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" kill-server
   ```

4. Unload Qwen3-VL:

   ```powershell
   ollama stop qwen3-vl:8b
   ```

5. Quit Ollama from the system tray.
6. Repeat the process, port, and GPU checks above.

## After a USB Disconnect

1. Stop the old gateway with `Ctrl+C` if it is still running.
2. Reconnect and unlock the phone.
3. Approve USB debugging if prompted.
4. Run again:

   ```powershell
   .\inference_server\start_local_ai.ps1 -UsbOnly
   ```
