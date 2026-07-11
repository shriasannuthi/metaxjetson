# Jetson local architecture

```text
Meta glasses
  -> Bluetooth / Meta Device Access Toolkit 0.7.0
Android phone
  -> capturePhoto()
  -> bundled ML Kit Latin OCR
  -> localhost:8000 through adb reverse over direct USB
Jetson Orin Nano 8GB
  -> FastAPI /chat on 127.0.0.1:8000
  -> Ollama + gemma3:4b-it-q4_K_M on 127.0.0.1:11434
  -> NVIDIA GPU
```

The workstation is not a runtime component. FastAPI exposes unauthenticated `GET /health` and token-authenticated `POST /chat`. A single `asyncio.Lock` serializes every Ollama request. The legacy image `/ground` endpoint is intentionally removed.

## Document path

Android captures the high-resolution glasses photo, applies the existing HEIC/EXIF handling, and runs bundled on-device ML Kit OCR. The resulting OCR transcription is stored as the document session text and sent to Jetson `/chat` for document analysis.

Document analysis, follow-up Q&A, and RM/customer Q&A continue through `/chat` with their existing token limits. Follow-up Q&A still sends the complete original OCR transcription, up to eight prior Q&A turns, and the current question. Document-specific facts must come from the OCR text; general banking context must be labelled as such.

The Jetson no longer receives document images, performs Pillow preprocessing, or runs a vision model. OCR quality now depends on focus, glare, framing, document language, and ML Kit Latin-script support. If OCR text is blank or too short, the Android scan fails locally before calling the Jetson.

## Isolation and recovery

Ollama and Uvicorn are loopback-only systemd services. The ADB manager waits for exactly one authorized physical phone and recreates `adb reverse tcp:8000 tcp:8000` whenever necessary. The ignored token file is readable only by its owner.

Readiness requires the configured text model to exist. Provisioning proves text operation, `100% GPU` placement, acceptable memory, and no thermal failure. A CPU fallback is a deployment error.
