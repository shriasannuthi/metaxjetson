# Architecture Overview

This document explains what the system consists of, where each part runs, what the open terminal
does, why the phone browser is used, and how information moves from the glasses to local models.

## The Short Version

There are three physical devices:

1. **Meta glasses** capture video, photos, and microphone audio.
2. **Android phone** runs the user interface, controls the glasses, recognizes speech, and performs
   face matching.
3. **Windows laptop** runs document OCR and Gemma, because those models need more CPU, RAM, and GPU
   capacity than the phone.

The phone and laptop communicate only through the USB cable. No router or internet connection is
involved during normal operation.

```text
Meta glasses
    |
    | Bluetooth through Meta DAT
    v
Android application
    |
    | Requests sent to phone address 127.0.0.1:8000
    v
ADB reverse over the USB cable
    |
    | Delivers the same request to laptop 127.0.0.1:8000
    v
FastAPI local gateway
    |                                  |
    | /chat                            | /ground
    v                                  v
Ollama + Gemma 3                  PP-StructureV3 + PP-OCRv5
RTX 4060 GPU                      Ryzen CPU
```

## What Runs on the Meta Glasses

The glasses provide:

- Camera streaming.
- Photo capture used by document scans; the current captured document image is approximately
  480x640, so framing and lighting materially affect OCR quality.
- Microphone audio routed to the phone.
- Bluetooth communication with the phone.

The glasses do not run Gemma, PaddleOCR, the FastAPI gateway, or this repository's Android code.
They are a camera/microphone accessory controlled through Meta's Device Access Toolkit.

## What Runs on the Android Phone

The installed Android application contains:

- The user interface visible in the screenshots.
- Meta DAT integration for glasses pairing, streaming, and photo capture.
- Android `SpeechRecognizer` for offline speech-to-text and "Hey Meta scan."
- ML Kit face detection and a local TensorFlow Lite face-embedding model.
- Customer records bundled in the application assets.
- Prompt builders for customer Q&A, document analysis, and document Q&A.
- `LocalAiClient`, which sends authenticated requests to `http://127.0.0.1:8000`.

The phone does not run Gemma or PaddleOCR. Its `127.0.0.1` normally refers to the phone itself,
but ADB reverse redirects port 8000 through USB to the laptop.

## What ADB Reverse Does

Android Debug Bridge is installed with Android Studio's Platform-Tools. The startup script runs
the equivalent of:

```powershell
adb reverse tcp:8000 tcp:8000
```

This creates a temporary rule:

```text
phone localhost:8000 -> USB cable -> laptop localhost:8000
```

It is not USB tethering. It does not give the phone internet access and does not create a Wi-Fi
network. The mapping exists only while the phone remains connected and ADB is active.

## What Runs in the PowerShell Terminal

The command:

```powershell
.\inference_server\start_local_ai.ps1 -UsbOnly
```

performs these steps:

1. Reads the local gateway token and model settings from `inference_server\.env`.
2. Confirms Ollama is running on laptop port 11434.
3. Locates `adb.exe` in the Android SDK.
4. Confirms exactly one authorized physical phone is connected and no emulator is running.
5. Creates the USB reverse mapping for port 8000.
6. Sends a preload request so Gemma enters GPU memory before the first user question.
7. Starts Python/Uvicorn and the FastAPI gateway on laptop `127.0.0.1:8000`.
8. Loads PP-StructureV3 and PP-OCRv5 into the Python process for document scans.
9. Prints request logs and errors until `Ctrl+C` is pressed.

The terminal must stay open because its Python process is the local gateway and OCR host. Pressing
`Ctrl+C` stops that process and removes the USB mapping.

## What Ollama Is Doing

Ollama is a separate Windows background application listening only on:

```text
http://127.0.0.1:11434
```

It loads `gemma3:4b-it-q4_K_M` into RTX 4060 GPU memory. The Android app never calls Ollama
directly. Only the FastAPI gateway sends it local `/api/chat` requests.

Gemma performs:

- Relationship-manager customer Q&A.
- Structured analysis of OCR document text.
- Follow-up Q&A using the grounded document text.

Gemma does not receive the original document image. It receives only text returned by local OCR.

## What PaddleOCR Is Doing

PaddleOCR runs inside the Python gateway process on the Ryzen CPU. It loads:

- PP-StructureV3 for document layout and reading order.
- PP-OCRv5 server detection for finding text regions.
- PP-OCRv5 server recognition for converting text regions into characters.
- Supporting orientation, unwarping, and table models.

The affected PaddlePaddle 3.3.0 oneDNN path is explicitly disabled because it causes a Windows CPU
inference error. OCR uses standard CPU kernels instead.

PP-Structure sometimes represents a detected picture as an internal filename such as
`imgs/img_in_image_box...`. The gateway removes those generated references. It compares structured
Markdown with Paddle's underlying recognized text lines and sends the richer real text to Gemma.
If no readable text exists, grounding fails instead of asking Gemma to guess an image's contents.

## What FastAPI and Uvicorn Are Doing

FastAPI defines the local gateway API. Uvicorn is the Python program serving it.

| Endpoint | Caller | Purpose |
| --- | --- | --- |
| `GET /health` | Phone browser or operator | Reports gateway, Gemma, and OCR readiness |
| `POST /chat` | Android app | Sends text prompts to local Gemma |
| `POST /ground` | Android app | Sends one JPEG/PNG document photo to local OCR |

`/chat` and `/ground` require the `X-Local-Token` header. The Android app and gateway receive the
same generated token during setup. `/health` contains no private data and does not require it.

The gateway binds to laptop loopback only. Other computers cannot connect to it.

## What Chrome on the Phone Is Doing

Chrome is not running a model and is not part of normal app processing. It is used only to open:

```text
http://127.0.0.1:8000/health
```

If that page reports `ready`, it proves all of the following:

- The USB cable supports data.
- USB debugging is authorized.
- The ADB reverse mapping exists.
- The laptop gateway is running.
- Ollama has the Gemma model installed.
- PaddleOCR loaded successfully.

Chrome may be closed immediately after this check. The Android app independently uses the same
phone-local address.

## Customer Q&A Data Flow

```text
Spoken question
 -> Android offline speech recognition
 -> question text
 -> Android combines question with matched customer data
 -> POST /chat over phone localhost
 -> USB ADB reverse
 -> FastAPI gateway
 -> local Ollama/Gemma on GPU
 -> answer text returns over USB
 -> Android displays the answer
```

The customer context can include profile, accounts, recent transactions, and relationship history.
It stays within the phone, USB cable, and laptop.

## Document Scan Data Flow

```text
"Hey Meta scan" or scan button
 -> glasses capture a document photo
 -> Android receives the photo
 -> POST /ground over USB
 -> PaddleOCR on laptop CPU
 -> grounded document text
 -> POST /chat with structured-analysis mode
 -> Gemma on laptop GPU
 -> JSON analysis returns to Android
 -> Android displays document type, summary, fields, risks, and actions
```

For document follow-up questions, Android sends the grounded text, the spoken question, and recent
document Q&A turns to `/chat`. The original image is not resent to Gemma.

## Face Recognition Data Flow

Face recognition does not use the laptop:

```text
glasses video frame
 -> Android ML Kit face detection
 -> Android TensorFlow Lite face embedding
 -> comparison with locally bundled enrolled faces
 -> matched customer selected in the Android app
```

## Speech Recognition Data Flow

Both assistant speech and the "Hey Meta scan" command use Android's installed speech recognizer.
This was verified on the current phone with internet unavailable. No faster-whisper server is
running in the current architecture.

## Files and Persistent Data

| Location | Contents |
| --- | --- |
| `app/` | Android source, customer data, enrolled faces, and local face model |
| `inference_server/` | Gateway source, scripts, and tests |
| `inference_server/.venv/` | Private Python environment and installed packages |
| `inference_server/.env` | Ignored gateway token and model configuration |
| `local.properties` | Ignored Android SDK path, GitHub package token, local URL, and gateway token |
| `%USERPROFILE%\.ollama\models` | Downloaded Gemma model files |
| `%USERPROFILE%\.paddlex\official_models` | Downloaded OCR/layout model files |

Model files remain on disk after shutdown. They consume storage but no CPU or GPU until processes
load them again.

## Processes Visible While Running

| Process | Why it exists | Main resource |
| --- | --- | --- |
| `ollama.exe` | Serves Gemma | GPU VRAM and some RAM |
| `python.exe` | Runs Uvicorn, FastAPI, and PaddleOCR | CPU and RAM |
| `adb.exe` | Maintains USB communication | Very small CPU/RAM usage |
| Android app | Controls glasses and displays results | Phone CPU/RAM |
| Chrome | Optional health check only | Phone resources until closed |

Android Studio is needed to build/install and inspect the app, but it does not need to remain open
for ordinary use after the app is installed. ADB Platform-Tools still remain required.

## What Stops When You Press Ctrl+C

Pressing `Ctrl+C` in the gateway terminal stops:

- FastAPI/Uvicorn.
- The Python OCR process.
- Loaded PaddleOCR models in RAM.
- The port-8000 ADB reverse mapping.

It does not stop the separate Ollama application. Use the complete shutdown procedure in
[DAILY_START_STOP.md](DAILY_START_STOP.md) to unload Gemma, quit Ollama, and stop the ADB helper.

## Privacy Boundary

At runtime, app-owned AI processing has no cloud fallback and all model endpoints are loopback
addresses. Meta pairing/runtime and Android speech recognition were separately verified while
offline. Meta SDK analytics opt-out is enabled in the Android manifest.
