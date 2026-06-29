# Architecture Overview

This explains what runs on each device, what the open terminal and phone browser do, and how data
moves through the fully local system.

## Short Version

```text
Meta glasses
    | Bluetooth through Meta DAT
    v
Android application
    | http://127.0.0.1:8000
    v
ADB reverse over USB
    |
    v
FastAPI gateway on laptop 127.0.0.1:8000
    |
    | local Ollama API 127.0.0.1:11434
    v
Qwen3-VL 8B on RTX 4060
```

The system has one AI model. Qwen3-VL handles text and images on the GPU. No document model or
language model performs CPU inference.

## Meta Glasses

The glasses provide:

- Camera streaming.
- Photo capture for document scans.
- Microphone audio routed to the phone.
- Bluetooth communication with the phone.

They do not run Qwen3-VL, FastAPI, or the Android application. They are controlled through Meta's
Device Access Toolkit.

## Android Phone

The installed application runs:

- User interface and session state.
- Glasses pairing, streaming, and photo capture through Meta DAT.
- Existing offline Android speech recognition.
- Local ML Kit and TensorFlow Lite face matching.
- Customer data bundled in application assets.
- Prompt construction for customer Q&A, document analysis, and document Q&A.
- `LocalAiClient`, which calls `http://127.0.0.1:8000`.

The phone does not run Qwen3-VL. Its localhost request is transported to the laptop by ADB reverse.

## ADB Reverse and USB

The startup script runs the equivalent of:

```powershell
adb reverse tcp:8000 tcp:8000
```

This creates:

```text
phone localhost:8000 -> USB cable -> laptop localhost:8000
```

It is not USB tethering, does not provide internet access, and disappears when USB disconnects or
the rule is removed.

## Gateway PowerShell Terminal

Running:

```powershell
.\inference_server\start_local_ai.ps1 -UsbOnly
```

does the following:

1. Reads the ignored local token and Ollama settings.
2. Confirms Ollama is running and Qwen3-VL is installed.
3. Finds `adb.exe` and one authorized physical phone.
4. Rejects active emulators and ambiguous device selections.
5. creates the port-8000 USB reverse rule.
6. Preloads Qwen3-VL into GPU memory.
7. Starts Python, Uvicorn, and FastAPI on laptop localhost.
8. Prints request logs until `Ctrl+C` is pressed.

The terminal must stay open because it owns the FastAPI process. Python performs authentication,
multipart handling, JPEG/PNG validation, and local Ollama calls. These are lightweight CPU tasks,
not model inference.

## Ollama and Qwen3-VL

Ollama is a separate Windows application listening only at `127.0.0.1:11434`. It keeps
`qwen3-vl:8b` loaded in RTX 4060 memory.

Qwen3-VL performs:

- Customer Q&A from text prompts.
- Document image transcription through its vision input.
- Structured summary and explanation from the transcription.
- Document follow-up Q&A from transcription and session history.

The gateway serializes all model calls. Only one text or vision request runs at a time, avoiding
GPU memory spikes and nondeterministic overlapping responses.

## FastAPI Endpoints

| Endpoint | Input | Result |
| --- | --- | --- |
| `GET /health` | No private input | Gateway and Qwen3-VL readiness |
| `POST /chat` | Authenticated JSON text prompt | Qwen3-VL text or structured JSON response |
| `POST /ground` | Authenticated JPEG/PNG multipart image | Qwen3-VL Markdown transcription |

`/chat` and `/ground` require `X-Local-Token`. `/health` does not process private data and does not
require a token. Both services bind only to laptop loopback.

## Phone Chrome

Chrome is used only to open:

```text
http://127.0.0.1:8000/health
```

If it reports ready, the cable, USB authorization, ADB mapping, FastAPI process, Ollama process,
and Qwen3-VL installation are working. Chrome runs no model and may be closed after this check.

## Customer Q&A Flow

```text
Spoken question
 -> Android offline speech recognition
 -> Android combines question with matched customer data
 -> POST /chat over phone localhost and USB
 -> FastAPI -> Ollama -> Qwen3-VL on GPU
 -> answer returns over USB
 -> Android displays answer
```

## Document Scan Flow

### Stage 1: faithful transcription

```text
"Hey Meta scan" or scan button
 -> glasses capture photo
 -> Android compresses it as JPEG
 -> POST /ground over USB
 -> FastAPI validates and mildly enhances the image
 -> enhanced image plus strict transcription instructions go to Qwen3-VL
 -> one retry runs when simple heuristics identify a weak first response
 -> Markdown transcription returns to Android
 -> Android stores it as documentSessionText
```

Qwen3-VL is instructed to preserve headings, lists, fields, line order, and tables; avoid summaries
and visual descriptions; mark unreadable fragments; and fail clearly when no text is readable.

### Stage 2: displayed analysis

```text
complete transcription
 -> POST /chat in structured-analysis mode
 -> Qwen3-VL returns document type, fields, summary, explanation, risks, and actions
 -> Android displays that analysis in the top result window
```

### Stage 3: follow-up Q&A

```text
complete transcription
 + up to eight prior document Q&A turns
 + current spoken question
 -> POST /chat
 -> Qwen3-VL answer
```

The displayed summary and analysis JSON are never included in the Q&A prompt. The original image
is not resent for follow-up questions. The transcription remains the source evidence for the
document session.

## Face Recognition Flow

```text
glasses video frame
 -> Android ML Kit face detection
 -> Android TensorFlow Lite embedding
 -> comparison with bundled enrolled faces
 -> customer selected locally
```

The laptop is not involved.

## Speech Recognition Flow

Assistant speech and the "Hey Meta scan" trigger use Android's installed recognizer. This was
verified on the current phone without internet. No speech server runs on the laptop.

## Files and Persistent Data

| Location | Contents |
| --- | --- |
| `app/` | Android source, customer data, faces, and local face model |
| `inference_server/` | Gateway source, scripts, and tests |
| `inference_server/.venv/` | Lightweight private Python environment |
| `inference_server/.env` | Ignored gateway token and Ollama settings |
| `local.properties` | Ignored Android SDK, GitHub token, local URL, and gateway token |
| `%USERPROFILE%\.ollama\models` | Downloaded Qwen3-VL files |

Model files on disk use storage but no CPU or GPU after shutdown.

## Processes While Running

| Process | Purpose | Main resource |
| --- | --- | --- |
| `ollama.exe` | Serves Qwen3-VL text and vision | GPU VRAM and RAM |
| `python.exe` | Runs Uvicorn and FastAPI | Small CPU and RAM use |
| `adb.exe` | Maintains USB communication | Very small CPU and RAM use |
| Android app | Controls glasses and displays results | Phone resources |
| Chrome | Optional health check only | Phone resources until closed |

Android Studio is needed to build and install the app, but may be closed during ordinary use.

## What Ctrl+C Stops

Pressing `Ctrl+C` in the gateway terminal stops FastAPI/Uvicorn and removes the port-8000 reverse
rule. It does not quit Ollama or unload Qwen3-VL. Follow [DAILY_START_STOP.md](DAILY_START_STOP.md)
to release GPU memory and stop ADB completely.

## Privacy Boundary

All app-owned AI endpoints are local loopback addresses with no cloud fallback. Runtime traffic
stays among the phone, USB cable, and laptop. Meta pairing/runtime and Android speech recognition
were independently verified while offline. Meta SDK analytics opt-out remains enabled.
