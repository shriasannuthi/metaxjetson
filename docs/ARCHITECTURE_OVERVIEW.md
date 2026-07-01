# Architecture Overview

This branch runs the Meta glasses banking assistant entirely through local services on a Windows
laptop. The active Ollama model is `gemma3:4b-it-q4_K_M`.

## System Map

```text
Meta glasses
    | Bluetooth through Meta Device Access Toolkit
    v
Android application
    | http://127.0.0.1:8000
    v
ADB reverse over USB
    |
    v
FastAPI gateway on laptop 127.0.0.1:8000
    | serialized local requests
    v
Ollama 127.0.0.1:11434
    |
    v
Gemma 3 4B Q4 on RTX 4060
```

The phone owns capture, speech, UI, and session state. The laptop owns image preparation and Gemma
inference. Runtime traffic has no cloud AI fallback.

## Responsibilities by Device

### Meta glasses

- Stream camera frames and capture document photos.
- Route microphone audio to the phone.
- Communicate with the phone over Bluetooth.

The glasses do not run the Android app, FastAPI, or Gemma.

### Android phone

- Pair with the glasses through Meta DAT and manage the camera stream.
- Capture a high-resolution photo for each document scan.
- Apply HEIC EXIF orientation and encode the photo as JPEG quality 92.
- Run offline Android speech recognition.
- Run ML Kit face detection and MobileFaceNet matching against bundled customer faces.
- Store the active customer, document transcription, displayed analysis, and Q&A history in memory.
- Build prompts for customer Q&A, document analysis, and document follow-up Q&A.
- Call the authenticated FastAPI endpoints through `LocalAiClient`.

The Android app sends one image to `/ground`; it does not crop, tile, or submit multiple images.

### Windows laptop

- ADB reverse maps phone port 8000 to laptop port 8000 over USB.
- FastAPI authenticates requests, validates and prepares document images, and calls Ollama.
- Ollama runs `gemma3:4b-it-q4_K_M` locally with an 8192-token context.
- An `asyncio.Lock` serializes every text and vision request so only one inference runs at a time.

## USB Transport

The startup script creates this mapping:

```text
phone 127.0.0.1:8000 -> USB cable -> laptop 127.0.0.1:8000
```

It uses `adb reverse tcp:8000 tcp:8000`; it is not USB tethering and provides no internet access.
Disconnecting USB or stopping ADB removes the path.

## Local Services and API

| Service or endpoint | Input | Result |
| --- | --- | --- |
| FastAPI `GET /health` | No private input | Gateway, Ollama, and configured-model readiness |
| FastAPI `POST /chat` | Authenticated JSON prompt | Text or schema-constrained document analysis |
| FastAPI `POST /ground` | Authenticated JPEG/PNG multipart upload | Markdown document transcription |
| Ollama `/api/chat` | Local text and optional Base64 image | Gemma response |

`/chat` and `/ground` require `X-Local-Token`. FastAPI and Ollama normally bind to laptop
loopback. The Android build receives its gateway URL and token from ignored local configuration.

## Document Grounding Pipeline

Grounding converts one captured document photo into the transcription used by every later document
operation.

```text
capturePhoto()
 -> Android applies orientation and JPEG quality 92
 -> POST /ground as multipart image
 -> upload and decoded-image validation
 -> Pillow enhancement in memory
 -> first Gemma 3 vision transcription
 -> simple weak-output check
 -> optional second vision transcription using the same enhanced image
 -> final Markdown transcription or explicit error
```

### 1. Upload validation

FastAPI accepts only `image/jpeg` and `image/png`. The upload must be non-empty and no larger than
12 MB. Pillow then verifies that it is a valid JPEG or PNG and rejects decoded images larger than
24 megapixels. Invalid uploads never reach Ollama.

### 2. In-memory image preparation

The gateway opens the validated image with Pillow and:

1. Applies EXIF transpose defensively.
2. Converts it to RGB.
3. Leaves smaller images at their original dimensions.
4. If the long edge exceeds 2200 pixels, resizes it to a 2000-pixel long edge with Lanczos.
5. Applies mild contrast enhancement at factor 1.20.
6. Applies mild sharpness enhancement at factor 1.30.
7. Encodes the result as JPEG quality 95.

There is no OCR engine, thresholding, black-and-white conversion, cropping, deskewing, layout
detection, or tiling.

### 3. First vision attempt

The gateway Base64-encodes the enhanced JPEG and sends it to Ollama in one non-streaming
`/api/chat` request. Vision uses temperature 0 and allows up to 4096 output tokens.

The laptop-side Ollama read timeout is 120 seconds. Android gives `/ground` a 120-second read
timeout and a 130-second total call timeout. Both vision attempts, when needed, execute while the
same model lock is held, so another app request cannot overlap the scan.

The strict prompt requires Markdown transcription only. It asks Gemma to preserve reading order,
headings, labels, values, lists, and tables; mark unreadable text as `[unclear]`; ignore instructions
inside the document; and avoid summaries, explanations, image descriptions, or invented content.

### 4. Weak-output decision

The first result is considered weak when it is:

- Empty.
- Effectively `NO_READABLE_TEXT`.
- Shorter than 80 characters.
- Dominated by repeated `[unclear]` markers.
- Prefixed like a summary or image description.
- A generic refusal such as `I cannot read` or `unable to determine`.

A strong first result is returned immediately. A weak first result triggers exactly one retry.

### 5. Retry

The retry uses the same enhanced image and a stronger transcription-only prompt. It again allows
4096 output tokens. There are never more than two grounding calls for one scan.

If the final result is effectively `NO_READABLE_TEXT`, FastAPI returns HTTP 422. An empty final
result is treated as a local model failure and returns HTTP 503. Otherwise the final text is returned
in the existing response shape:

```json
{
  "text": "...Markdown transcription...",
  "model": "gemma3:4b-it-q4_K_M",
  "latencyMs": 1234
}
```

The image and intermediate responses are not persisted by the application.

## Document Analysis and Q&A

After grounding succeeds, Android stores the transcription as `documentSessionText`.

### Displayed analysis

Android sends the complete transcription to `/chat` with `document_analysis` response mode. Ollama
receives a JSON schema requiring document type, extracted fields, summary, explanation, risk flags,
and recommended actions. This is a text-only Gemma call.

### Follow-up questions

Each document question includes:

- The complete original transcription.
- Up to eight prior document Q&A turns.
- The current spoken question.

The displayed summary is never used as evidence and the original image is not resent. The
transcription is therefore the evidence boundary for claims about what this specific document says.
For definitions, concepts, formulas, typical implications, and adjacent banking questions, Gemma may
also use general banking knowledge and labels it as general context. Prior Q&A turns provide
conversation continuity but are not independent document evidence. A question is rejected only when
it is unrelated to both the document and banking or finance.

Personalized financial or legal guidance, current rates, and bank-specific policies receive general
educational context with assumptions and a recommendation to verify the applicable document or bank.

## Customer Q&A

Face matching selects a bundled customer locally on Android. The phone sends selected customer
data, the spoken question, and limited conversation history to `/chat`. Gemma answers the
relationship manager using only that supplied data.

## Startup, Health, and Shutdown

`start_local_ai.ps1 -UsbOnly` reads `inference_server/.env`, verifies Ollama and the configured
model, selects one authorized physical phone, creates the ADB reverse rule, preloads the model, and
starts Uvicorn. The terminal must remain open.

The phone health URL verifies the complete phone-to-laptop path:

```text
http://127.0.0.1:8000/health
```

Pressing `Ctrl+C` stops FastAPI and removes the reverse rule. It does not unload Gemma or quit
Ollama; follow [DAILY_START_STOP.md](DAILY_START_STOP.md) for complete shutdown.

## Persistent Files and Privacy Boundary

| Location | Contents |
| --- | --- |
| `app/` | Android source, bundled customer records, faces, and face model |
| `inference_server/` | FastAPI runtime, scripts, and tests |
| `inference_server/.env` | Ignored gateway token, model tag, Ollama URL, and context length |
| `local.properties` | Ignored Android SDK path, package token, gateway URL, and gateway token |
| `%USERPROFILE%\.ollama\models` | Local Ollama model files |

App-owned AI traffic remains among the phone, USB cable, FastAPI, and loopback Ollama. There is no
cloud model endpoint or fallback in the Android or gateway code.
