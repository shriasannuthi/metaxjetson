# Jetson local architecture

```text
Meta glasses
  -> Bluetooth / Meta Device Access Toolkit 0.7.0
Android phone
  -> localhost:8000 through adb reverse over direct USB
Jetson Orin Nano 8GB
  -> FastAPI + Pillow on 127.0.0.1:8000
  -> Ollama + gemma3:4b-it-q4_K_M on 127.0.0.1:11434
  -> NVIDIA GPU
```

The workstation is not a runtime component. FastAPI exposes unauthenticated `GET /health` and token-authenticated `POST /chat` and `POST /ground`; their schemas are unchanged. A single `asyncio.Lock` serializes every text and vision request.

## Document path

Android captures one high-resolution image, applies HEIC orientation, encodes JPEG quality 92, and posts it to `/ground`. FastAPI retains the 12MB/24MP validation and Pillow EXIF transpose, RGB conversion, conditional 2000-pixel resize, contrast 1.20, sharpness 1.30, and JPEG quality 95 behavior. Ollama receives the strict Markdown transcription prompt and the same enhanced image for at most one weak-output retry.

Each Ollama grounding attempt may use up to 110 seconds while the complete server operation remains capped at 115 seconds, inside Android’s unchanged 130-second total timeout. A fast weak result can still trigger the existing single retry, but the total deadline always wins. Timeout failures use the existing 503 contract; final `NO_READABLE_TEXT` uses 422.

The Jetson uses a 4096-token Ollama context. Device testing showed that Gemma 3 4B Q4 loads fully on the GPU at 4096 while the previous 8192 setting fails CUDA allocation on the 8GB module.

Document analysis, follow-up Q&A, and RM/customer Q&A continue through `/chat` with their existing prompts and token limits. No OCR, tiling, layout detection, training, cloud service, or alternate transport exists.

## Isolation and recovery

Ollama and Uvicorn are loopback-only systemd services. The ADB manager waits for exactly one authorized physical phone and recreates `adb reverse tcp:8000 tcp:8000` whenever necessary. The ignored token file is readable only by its owner.

Readiness requires the configured model to exist. Provisioning additionally proves text and vision operation, `100% GPU` placement, acceptable memory, and no thermal or latency failure. A CPU fallback is a deployment error.
