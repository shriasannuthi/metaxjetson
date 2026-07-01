# Windows 11 USB-Only Local AI Setup

Follow this guide once on a fresh Windows 11 computer. It covers every step from installing the
required software through the first complete offline test.

This guide is written for an HP Omen with Ryzen 7, 32 GB RAM, and an RTX 4060 with 8 GB VRAM.
Internet is required temporarily for installers, source code, Gradle dependencies, and the Gemma 3
download. After setup, normal operation requires no internet or local network.

```text
Meta glasses -> Bluetooth -> Android phone
                              |
                              | phone 127.0.0.1:8000
                              v
                         USB / ADB reverse
                              |
                              v
                    laptop 127.0.0.1:8000 FastAPI
                              |
                              v
                    laptop 127.0.0.1:11434 Ollama
                              |
                              v
                    Gemma 3 4B Q4 on RTX 4060
```

USB debugging is required. USB tethering must remain off. Bluetooth stays enabled on the phone
only for the glasses.

## What Runs Where

| Function | Location |
| --- | --- |
| Glasses pairing, stream, and photo capture | Meta glasses and Android phone |
| Offline speech recognition and face matching | Android phone |
| Image transcription, document analysis, and all Q&A | Gemma 3 on laptop RTX 4060 |
| Authentication, image validation, and request routing | FastAPI on laptop |
| Phone-to-laptop communication | ADB reverse over USB |

## Part 1: Prepare Windows and Check the GPU

1. Connect the laptop charger.
2. Open **Start > Settings > System > Power & battery**.
3. Set **Power mode** to **Best performance**.
4. Open **Start**, type `PowerShell`, and open **Windows PowerShell**.
5. Run:

   ```powershell
   nvidia-smi
   ```

6. Confirm `NVIDIA GeForce RTX 4060 Laptop GPU` appears.
7. If the command fails, install the current NVIDIA driver and restart Windows.
8. Do not install a separate CUDA Toolkit. Ollama includes what this setup needs.

## Part 2: Download the Project

If the project folder has already been provided to you, place it in a permanent location and skip
to step 7.

1. Open [Git for Windows](https://git-scm.com/download/win).
2. Download and run the 64-bit installer.
3. Keep the installer defaults and finish installation.
4. Close and reopen PowerShell.
5. Move to the folder where you want the project stored. For example:

   ```powershell
   Set-Location "$env:USERPROFILE\Desktop"
   ```

6. Clone the repository:

   ```powershell
   git clone https://github.com/shriasannuthi/metaxjetson.git
   ```

7. Open the `metaxjetson` project folder.
8. Click the File Explorer address bar, type `powershell`, and press Enter.
9. Confirm the terminal prompt ends with `metaxjetson>`.

All later PowerShell commands marked "in the repository" must be run from this folder.

## Part 3: Install Ollama

Perform these steps manually while online.

1. Open [Ollama for Windows](https://ollama.com/download/windows).
2. Select **Download for Windows**.
3. Run `OllamaSetup.exe`.
4. Finish installation and open Ollama from Start.
5. Confirm the Ollama icon appears in the Windows system tray.
6. Open a new PowerShell window.
7. Run:

   ```powershell
   ollama --version
   ```

8. If the command is not found, close and reopen PowerShell.
9. Do not sign in to Ollama and do not select a cloud model.

This branch uses `gemma3:4b-it-q4_K_M` and enables local-only operation.

## Part 4: Install Python 3.11.9

Perform these steps manually while online. Do not use Python 3.13 for this project.

1. Open the [Python 3.11.9 release page](https://www.python.org/downloads/release/python-3119/).
2. Download **Windows installer (64-bit)**.
3. Run the installer.
4. Select **Add python.exe to PATH** on the first screen.
5. Select **Install Now**.
6. Select **Disable path length limit** if offered.
7. Close and reopen PowerShell.
8. Run:

   ```powershell
   py -3.11 --version
   ```

9. Confirm the result starts with `Python 3.11`.

Python hosts only the lightweight FastAPI gateway. Gemma 3 inference runs through Ollama on the GPU.

## Part 5: Install Android Studio and Android SDK

Perform these steps manually while online.

1. Open the official [Android Studio download page](https://developer.android.com/studio).
2. Select **Download Android Studio for Windows** and accept the terms.
3. Run the downloaded `.exe` installer.
4. Keep **Android Studio** and **Android Virtual Device** selected.
5. Finish installation and open Android Studio.
6. Complete the Setup Wizard using **Standard** setup.
7. Allow the wizard to download its recommended SDK components.
8. On the Welcome screen, select **More Actions > SDK Manager**.
9. Open the **SDK Platforms** tab.
10. Select **Android 15.0 (API Level 35)**.
11. Open the **SDK Tools** tab.
12. Select **Android SDK Build-Tools**, **Android SDK Platform-Tools**, and
    **Android SDK Command-line Tools (latest)**.
13. Select **Apply > OK** and wait for every component to install.
14. Close SDK Manager.

Do not create or run an emulator for this project. Use the physical phone.

## Part 6: Create the GitHub Packages Token

Gradle needs a GitHub personal access token only to download Meta's Android package while building.
The installed app does not use this token and does not need internet.

1. Sign in to the GitHub account that has access to Meta's wearable Android repository/package.
2. Open **Profile picture > Settings > Developer settings**.
3. Open **Personal access tokens > Tokens (classic)**.
4. Select **Generate new token > Generate new token (classic)**.
5. Enter a clear note such as `Meta Android package read access`.
6. Choose an appropriate expiration date.
7. Select only **read:packages**. If the associated repository is private, also select **repo**.
8. Select **Generate token**.
9. Store the token temporarily in a secure password manager. GitHub will not show it again.
10. In the repository folder, right-click an empty area and select **Open in Terminal**.
11. Run:

    ```powershell
    notepad .\local.properties
    ```

12. If Notepad asks to create the file, select **Yes**.
13. Add these two lines, replacing the username and token placeholders:

    ```text
    sdk.dir=C:/Users/YOUR_WINDOWS_USERNAME/AppData/Local/Android/Sdk
    github_token=YOUR_GITHUB_PAT
    ```

14. Save and close Notepad.
15. Never commit or share `local.properties`. It is already ignored by Git.

GitHub Packages requires a classic token with `read:packages`; a fine-grained token is not used by
this build. See [GitHub's Gradle registry documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry).

## Part 7: Set Up the Local AI Server

Keep internet access enabled.

1. Open PowerShell in the repository.
2. Run these commands:

   ```powershell
   Set-ExecutionPolicy -Scope Process Bypass
   $env:OLLAMA_MODEL="gemma3:4b-it-q4_K_M"
   .\inference_server\setup_windows.ps1
   ```

   Do not omit the `OLLAMA_MODEL` line on this branch. The setup script is shared across local-model
   branches and uses that process-level override to pull Gemma 3 and write it into the real,
   ignored `inference_server\.env` file. `.env.example` is documentation only and is not read at
   runtime.

3. Wait while the script:
   - Configures Ollama for local-only, single-model operation.
   - Downloads `gemma3:4b-it-q4_K_M`.
   - Creates `inference_server\.venv` with Python 3.11.
   - Installs FastAPI and image-validation dependencies.
   - Creates `inference_server\.env` with a random private token.
   - Writes the selected model to `inference_server\.env` and verifies Gemma 3 readiness.
4. Confirm the final message says `Setup complete`.
5. Right-click the Ollama system-tray icon and select **Quit**.
6. Open Ollama again from Start so it receives the saved local-only settings.
7. Open PowerShell and run:

   ```powershell
   ollama list
   ```

8. Confirm `gemma3:4b-it-q4_K_M` appears.

It is normal for `ollama ps` to show only column headings here. The startup script loads Gemma 3 into
GPU memory later.

## Part 8: Prepare the Android Phone

1. Connect the Android phone using a data-capable USB cable.
2. Unlock the phone.
3. Open **Settings > About phone > Software information**.
4. Tap **Build number** seven times.
5. Enter the phone PIN if prompted.
6. Return to Settings and open **Developer options**. Depending on the phone, this may be under
   **System** or at the bottom of the main Settings screen.
7. Enable **USB debugging**.
8. When **Allow USB debugging?** appears, select **Always allow from this computer** and tap
   **Allow**.
9. Confirm **USB tethering** is off. Charging or File Transfer mode is acceptable.
10. Leave the phone connected and unlocked.

## Part 9: Configure and Install the Android App

Keep internet available until the build and installation finish.

1. Open PowerShell in the repository.
2. Run:

   ```powershell
   Set-ExecutionPolicy -Scope Process Bypass
   .\inference_server\configure_android.ps1 -BaseUrl "http://127.0.0.1:8000"
   ```

3. Confirm the script says the Android local AI configuration was updated.
4. Open Android Studio.
5. Select **Open** and choose the repository's `metaxjetson` folder.
6. Wait for project indexing to finish.
7. Select **File > Sync Project with Gradle Files**.
8. If Gradle reports a GitHub Packages `401` or `403`, recheck Part 6 and the account's package
   access before continuing.
9. In the device selector, choose the connected physical phone. Do not select an emulator.
10. Select **Run > Run 'app'**.
11. Approve installation on the phone if prompted.
12. Wait for Android Studio to install and launch the app.
13. Leave the phone connected by USB.

## Part 10: Disconnect Every External Network

Do this only after the app and all dependencies have been downloaded successfully.

### Laptop

1. Unplug Ethernet.
2. Open Windows Quick Settings and turn Wi-Fi off.
3. Open **Settings > Network & internet > Mobile hotspot** and confirm it is off.
4. Keep the charger and USB cable connected.

### Phone

1. Enable airplane mode.
2. Confirm Wi-Fi and cellular data are off.
3. Manually re-enable Bluetooth.
4. Confirm the Meta glasses reconnect.
5. Confirm USB tethering remains off.
6. Keep the phone connected and unlocked during startup.

The USB cable is now the only phone-to-laptop application data path. It does not provide internet.

## Part 11: Start the USB-Only System

1. Confirm Ollama is running in the Windows system tray. Open it from Start if necessary.
2. Open PowerShell in the repository.
3. Run:

   ```powershell
   Set-ExecutionPolicy -Scope Process Bypass
   .\inference_server\start_local_ai.ps1 -UsbOnly
   ```

4. Wait while the script authorizes the phone, creates the USB mapping, preloads Gemma 3, and starts
   FastAPI.
5. Confirm the terminal prints:

   ```text
   ADB reverse tunnel: phone tcp:8000 -> laptop tcp:8000
   Phone health URL: http://127.0.0.1:8000/health
   Gateway bind address: 127.0.0.1:8000
   ```

6. Keep this PowerShell window open.
7. Keep the USB cable connected.

## Part 12: Verify Readiness and GPU Use

1. On the phone, open Chrome.
2. Open `http://127.0.0.1:8000/health`.
3. Confirm `status`, `gateway`, `chat`, and `ground` all say `ready`, and `model` says
   `gemma3:4b-it-q4_K_M`.
4. Close Chrome. It is only a health check.
5. Open a second PowerShell window on the laptop.
6. Run:

   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" reverse --list
   ollama ps
   ```

7. Confirm the ADB output includes `tcp:8000 tcp:8000`.
8. Confirm Gemma 3's **PROCESSOR** column reports `100% GPU`.
9. Try opening a public website on the phone and laptop. Both must fail.

## Part 13: Complete Offline Test

1. Open the Android app.
2. Pair or reconnect the Meta glasses.
3. Start the camera stream.
4. Confirm local face matching recognizes an enrolled test face.
5. Ask a customer question and confirm a local answer appears.
6. Hold a clear printed page so its text fills most of the glasses view.
7. Use bright, even lighting and keep your head still.
8. Say **Hey Meta scan**.
9. Confirm Gemma 3 returns a faithful text transcription rather than a general image description.
10. Confirm the summary and explanation appear in the top result window.
11. Ask a follow-up question whose answer is printed on the page and confirm it matches the transcription.
12. Ask for the meaning of a banking term shown but not defined, such as EMI, and confirm the answer is labelled as general banking context.
13. Ask for a document-specific value that is absent and confirm Gemma says what is missing rather than inventing it.
14. Ask an unrelated question such as the weather and confirm it is rejected.
15. Repeat document scans with prose, a form, a receipt, and a table.
16. Repeat customer Q&A and document Q&A after restarting the gateway.
17. Confirm all flows work while every external network remains disabled.

## Troubleshooting

### `ollama` is not recognized

Close PowerShell and reopen it. If the command still fails, quit Ollama, rerun the installer, and
restart Windows.

### Python 3.11 is not found

Rerun the Python 3.11.9 installer, select **Add python.exe to PATH**, finish installation, and open
a new PowerShell window.

### Gradle cannot download the Meta package

Confirm `local.properties` contains `github_token=...`, the token is classic, `read:packages` is
selected, the token is not expired, and the GitHub account can access the package. Reauthorize the
token for organization SSO if your organization requires it.

### `adb.exe` is not found

In Android Studio, open **Tools > SDK Manager > SDK Tools**, install **Android SDK
Platform-Tools**, reconnect the phone, and retry.

### The phone is unauthorized or offline

Unlock the phone, reconnect the cable, approve USB debugging, and select **Always allow from this
computer**. Then rerun the startup command.

### An emulator is reported

Close the emulator from Android Studio's Device Manager. The USB startup intentionally accepts
only a physical phone.

### The phone cannot open the health URL

Confirm the gateway terminal is open, the cable supports data, and `adb reverse --list` contains
port 8000. Reconnect and restart after any cable interruption.

### Health says chat or ground is unavailable

Open Ollama, run `ollama list`, and confirm `gemma3:4b-it-q4_K_M` exists. Temporarily reconnect to
the internet and rerun Part 7 if it is missing.

### The scan says no readable text

Fill more of the frame with the page, improve lighting, remove glare, keep the page square to the
camera, and retry. The model fails clearly rather than inventing missing text.

### The transcription summarizes or describes the picture

Stop the gateway with `Ctrl+C`, confirm the repository is current, and restart it. New scans use a
strict transcription-only prompt and do not receive prior document images.

### Gemma 3 does not report 100% GPU

Connect the charger, select **Best performance**, close any Android emulator, quit and reopen
Ollama, restart the gateway, and check `ollama ps` again.

## Setup Complete

The setup is complete when customer Q&A, document transcription, displayed analysis, and document
follow-up Q&A all work with internet disabled and `ollama ps` reports `100% GPU`.

For every later session, follow [DAILY_START_STOP.md](DAILY_START_STOP.md). To understand the
components and data flow, read [ARCHITECTURE_OVERVIEW.md](ARCHITECTURE_OVERVIEW.md).
