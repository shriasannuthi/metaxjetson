# Windows 11 USB-Only Local AI Setup

This guide configures the laptop as the only AI processor for the Android app. Internet is needed
once for installers, Gradle dependencies, and model downloads. During normal operation, neither
the laptop nor the phone connects to Wi-Fi, Ethernet, cellular data, or a hotspot.

The Android app reaches the laptop through `adb reverse` over a data-capable USB cable:

```text
Android app -> phone 127.0.0.1:8000
            -> USB cable / adb reverse
            -> laptop 127.0.0.1:8000
               |-> Ollama/Gemma on RTX 4060
               `-> PP-StructureV3/PP-OCRv5 on Ryzen CPU
```

USB debugging is required. USB tethering is different and must remain off. Bluetooth remains
enabled on the phone only because the Meta glasses require it.

Complete every numbered part once, in order. After the first successful setup and test, use
[DAILY_START_STOP.md](DAILY_START_STOP.md) for normal operation. Read
[ARCHITECTURE_OVERVIEW.md](ARCHITECTURE_OVERVIEW.md) for an explanation of every device, process,
terminal, model, and data flow.

## What Runs Where

| Component | Device | Processor |
| --- | --- | --- |
| Meta camera and microphone access | Glasses and phone | Meta DAT SDK |
| Speech recognition | Android phone | Existing offline Android recognizer |
| Customer and document questions | Laptop/Ollama | RTX 4060, Gemma 3 4B Q4 |
| Document extraction and layout | Laptop/PaddleOCR | Ryzen CPU, PP-StructureV3 and PP-OCRv5 |
| Face matching | Android phone | Existing ML Kit and TensorFlow Lite code |
| Phone-to-laptop AI transport | USB cable | ADB reverse port forwarding |

## Part 1: Prepare Windows

Perform this part manually.

1. Connect the HP Omen to its charger.
2. Open **Start > Settings > System > Power & battery**.
3. Set **Power mode** to **Best performance**.
4. Open **Start**, type `PowerShell`, and open **Windows PowerShell**.
5. Run:

   ```powershell
   nvidia-smi
   ```

6. Confirm `NVIDIA GeForce RTX 4060 Laptop GPU` is listed.
7. Driver 581.83 or newer is suitable. A separate CUDA Toolkit installation is not required.

## Part 2: Install Ollama and Gemma

Perform the installer actions manually while internet access is available.

1. Open [Ollama for Windows](https://ollama.com/download/windows).
2. Select **Download for Windows**.
3. Run `OllamaSetup.exe`.
4. Complete the installer and confirm Ollama appears in the Windows system tray.
5. Open a new PowerShell window.
6. Run:

   ```powershell
   ollama --version
   ```

7. If the command is not found, close PowerShell and reopen it.
8. Do not sign in to Ollama. This project uses only a downloaded local model.

The project setup script downloads `gemma3:4b-it-q4_K_M` and configures Ollama for local-only,
single-model operation:

```text
OLLAMA_NO_CLOUD=1
OLLAMA_KEEP_ALIVE=-1
OLLAMA_MAX_LOADED_MODELS=1
OLLAMA_NUM_PARALLEL=1
OLLAMA_CONTEXT_LENGTH=8192
```

Ollama remains bound to `127.0.0.1:11434` and is never exposed to the phone directly.

## Part 3: Install Python 3.11.9

Perform the installer actions manually while internet access is available. Python 3.13 is not
used by this project.

1. Open the [Python 3.11.9 release page](https://www.python.org/downloads/release/python-3119/).
2. Download **Windows installer (64-bit)**.
3. Run the installer.
4. Select **Add python.exe to PATH** on the first screen.
5. Select **Install Now**.
6. When installation finishes, select **Disable path length limit** if shown.
7. Close and reopen PowerShell.
8. Run:

   ```powershell
   py -3.11 --version
   ```

9. Confirm the result starts with `Python 3.11`.

Do not install PaddleOCR globally. The setup script creates `inference_server\.venv`.

## Part 4: Download and Warm All Models

Keep internet access enabled throughout this part.

1. Open the repository folder in File Explorer.
2. Right-click an empty area and select **Open in Terminal**.
3. Run:

   ```powershell
   Set-ExecutionPolicy -Scope Process Bypass
   .\inference_server\setup_windows.ps1
   ```

4. Wait while the script downloads Gemma, creates the Python environment, installs CPU
   PaddlePaddle, and downloads PP-StructureV3/PP-OCRv5 models.
5. Confirm the final message says `Setup complete`.
6. Right-click the Ollama system-tray icon and select **Quit**.
7. Open **Ollama** again from the Start menu so it receives the saved local-only settings.
8. Run:

   ```powershell
   ollama list
   ```

9. Confirm `gemma3:4b-it-q4_K_M` is installed.

It is normal for `ollama ps` to show only column headings here. The USB startup script loads Gemma
into GPU memory later.

The setup configures PaddleOCR to use standard CPU kernels. The affected PaddlePaddle 3.3.0 oneDNN
path is disabled automatically, so no separate repair or package downgrade is required.

## Part 5: Configure and Install Android for USB

Keep internet access enabled until the updated app is installed successfully.

1. Connect the phone to the laptop with a data-capable USB cable.
2. Unlock the phone.
3. Open **Settings > About phone**.
4. If Developer options are not already enabled, tap **Build number** seven times and enter the
   phone PIN when prompted.
5. Open **Settings > System > Developer options**.
6. Enable **USB debugging**.
7. When **Allow USB debugging?** appears, select **Always allow from this computer** and select
   **Allow**.
8. Confirm **USB tethering** is off. Charging or File Transfer USB mode is acceptable.
9. Open PowerShell in the repository.
10. Run:

    ```powershell
    Set-ExecutionPolicy -Scope Process Bypass
    .\inference_server\configure_android.ps1 -BaseUrl "http://127.0.0.1:8000"
    ```

11. Open the project in Android Studio.
12. Select **File > Sync Project with Gradle Files**.
13. Select the physical phone in the device selector. Do not start an emulator.
14. Select **Run > Run 'app'**.
15. Wait until Android Studio installs and launches the app.
16. Leave the phone connected by USB.

The app is now permanently configured to call its own loopback address. ADB carries those calls
through the USB cable to the laptop.

## Part 6: Disconnect Every External Network

Perform these actions manually after the updated app has been installed.

### Laptop

1. Unplug every Ethernet cable.
2. Select the network/speaker/battery area on the Windows taskbar.
3. Select the **Wi-Fi** tile so it is off.
4. Open **Start > Settings > Network & internet > Mobile hotspot**.
5. Confirm **Mobile hotspot** is off.
6. Laptop Bluetooth may also be turned off; the glasses do not connect to the laptop.
7. Keep the USB cable connected.

### Phone

1. Enable airplane mode.
2. Confirm Wi-Fi remains off.
3. Confirm cellular data remains off.
4. Manually re-enable Bluetooth.
5. Confirm the glasses reconnect over Bluetooth.
6. Confirm **USB tethering** remains off.
7. Keep the phone unlocked until ADB authorization has completed.

The USB cable is now the only phone-to-laptop data path. It does not provide internet access.

## Part 7: Start the USB-Only Gateway

1. Confirm Ollama is running in the Windows system tray. If not, open **Ollama** from Start.
2. Open PowerShell in the repository.
3. Run:

   ```powershell
   Set-ExecutionPolicy -Scope Process Bypass
   .\inference_server\start_local_ai.ps1 -UsbOnly
   ```

4. The script will find Android Studio's `adb.exe`, reject emulators, select the authorized
   physical phone, create `adb reverse tcp:8000 tcp:8000`, and preload Gemma.
5. Confirm the output includes:

   ```text
   ADB reverse tunnel: phone tcp:8000 -> laptop tcp:8000
   Phone health URL: http://127.0.0.1:8000/health
   Gateway bind address: 127.0.0.1:8000
   ```

6. Keep this PowerShell window open.
7. Keep the USB cable connected.

No Windows Firewall permission is required because the gateway listens only on laptop loopback.

## Part 8: Verify the USB Tunnel

1. On the phone, open Chrome or another browser.
2. Open:

   ```text
   http://127.0.0.1:8000/health
   ```

3. Confirm `status`, `gateway`, `chat`, and `ground` report `ready`.
4. Try opening a public website on the phone and confirm it fails.
5. Try opening a public website on the laptop and confirm it fails.
6. Open a second PowerShell window on the laptop.
7. Run:

   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" reverse --list
   ollama ps
   ```

8. Confirm the ADB output contains `tcp:8000 tcp:8000`.
9. Confirm Gemma appears in `ollama ps` and its **PROCESSOR** column reports `100% GPU`.

## Part 9: End-to-End Offline Test

1. Launch the Android app while the USB-only gateway remains running.
2. Pair or reconnect the glasses.
3. Enter the camera stream.
4. Confirm local face matching identifies an enrolled test customer.
5. Hold a clear printed document close enough that its text fills most of the glasses view. Use
   bright, even lighting and keep your head steady.
6. Say **Hey Meta scan**.
7. Confirm real document text appears, followed by structured document analysis.
8. Confirm the result does not contain generated paths such as `imgs/img_in_image_box...`.
9. Ask a spoken follow-up question about the document and confirm the answer is supported by the
   visible document text.
10. Open the customer assistant and ask a question about the matched customer.
11. Repeat the document and customer flows twice.
12. In Android Studio, open **View > Tool Windows > App Inspection > Network Inspector**.
13. Confirm app-owned AI requests go only to `127.0.0.1:8000`.

Warm acceptance targets are 8 seconds for chat, 20 seconds for grounding, and 30 seconds for a
complete scan plus analysis.

## Part 10: Finish the One-Time Setup

The one-time setup is complete when all of these are true:

- The phone health page reports every component as `ready`.
- Customer Q&A returns a correct local answer.
- A document scan returns text from the physical document, not a generated image filename.
- Document analysis and follow-up Q&A use that grounded text.
- Both flows work with laptop Wi-Fi/Ethernet and phone Wi-Fi/cellular disabled.
- `ollama ps` reports Gemma on GPU.

Bookmark these documents:

1. [DAILY_START_STOP.md](DAILY_START_STOP.md) for every future startup and complete shutdown.
2. [ARCHITECTURE_OVERVIEW.md](ARCHITECTURE_OVERVIEW.md) to understand what is running and where.

No installer, model download, Android rebuild, router, or network connection is required for
ordinary use after this point.

## If the USB Cable Disconnects

1. Stop the gateway PowerShell window with `Ctrl+C` if it is still running.
2. Reconnect the data-capable USB cable.
3. Unlock the phone.
4. Approve USB debugging if prompted.
5. Rerun:

   ```powershell
   .\inference_server\start_local_ai.ps1 -UsbOnly
   ```

The ADB reverse tunnel does not reliably survive a cable disconnect, so the startup command must
be rerun.

## Security Cleanup

The old cloud keys were removed from Android configuration and source, but deleting a key from a
file does not revoke it. Manually revoke the previous keys in the Google AI Studio, Groq, and xAI
dashboards. Keep only the GitHub package token required to build the Meta Maven dependency.

## Troubleshooting

### `adb.exe was not found`

1. Open Android Studio.
2. Select **Tools > SDK Manager**.
3. Open the **SDK Tools** tab.
4. Select **Android SDK Platform-Tools**.
5. Select **Apply > OK**.
6. Rerun the USB-only startup command.

### `The phone has not authorized USB debugging`

1. Unlock the phone and inspect its screen for the authorization dialog.
2. Select **Always allow from this computer** and **Allow**.
3. If no dialog appears, open **Developer options**, select **Revoke USB debugging
   authorizations**, reconnect the cable, and approve the new dialog.

### The script reports an emulator

Close every Android emulator in Android Studio's **Device Manager**, then rerun the startup
command. USB-only mode intentionally refuses to guess between virtual and physical devices.

### More than one phone is connected

Disconnect the extra phone, or identify the desired serial with `adb devices` and run:

```powershell
.\inference_server\start_local_ai.ps1 -UsbOnly -DeviceSerial "YOUR_DEVICE_SERIAL"
```

### The phone cannot open the health URL

1. Confirm the startup PowerShell window is still open.
2. Confirm the USB cable supports data, not charging only.
3. Run the ADB reverse-list command from Part 8.
4. Reconnect the cable and rerun the USB-only startup script.
5. Confirm Android was rebuilt after configuring `http://127.0.0.1:8000`.

### Health says chat is unavailable

Open Ollama from Start, run `ollama list`, and confirm `gemma3:4b-it-q4_K_M` is present. Rerun
`setup_windows.ps1` while temporarily online if the model is missing.

### Health says ground is unavailable

Read `ocrError` in the health response. Rerun `setup_windows.ps1` while temporarily online to
complete any missing model download. OCR intentionally uses CPU PaddlePaddle.

### Scan fails with a oneDNN or `ConvertPirAttribute2RuntimeAttribute` error

The current gateway disables the affected oneDNN path automatically. Stop the gateway with
`Ctrl+C`, confirm the repository contains the latest `inference_server/runtime.py`, and rerun:

```powershell
.\inference_server\start_local_ai.ps1 -UsbOnly
```

No Android rebuild is required for this server-side fix.

### Analysis describes a random image or shows `img_in_image_box`

The current gateway removes Paddle-generated image references and uses underlying recognized OCR
lines. Stop and restart the gateway so it loads the current Python code. Then scan the physical
document again with its text filling most of the glasses view. No Android rebuild is required.

### Grounding consistently takes more than 20 seconds

Open `inference_server\.env`, change:

```text
OCR_DETECTION_MODEL=PP-OCRv5_server_det
```

to:

```text
OCR_DETECTION_MODEL=PP-OCRv5_mobile_det
```

Restart the USB-only gateway. Text recognition remains on the higher-accuracy server model.

### Chat is slow or uses CPU

Connect the laptop charger, select **Best performance**, and run `ollama ps`. Restart Ollama if
Gemma does not report `100% GPU`. Do not run an Android emulator during the demonstration.
