import asyncio
import base64
import io
from pathlib import Path
from types import SimpleNamespace

import httpx
from fastapi.testclient import TestClient
from PIL import Image

from inference_server.app import create_app
from inference_server.runtime import (
    DOCUMENT_TRANSCRIPTION_PROMPT,
    GROUND_MAX_OUTPUT_TOKENS,
    LocalAiRuntime,
    LocalAiRuntimeError,
    NoReadableDocumentTextError,
    OllamaClient,
    Settings,
)


class FakeRuntime:
    def __init__(self) -> None:
        self.settings = SimpleNamespace(model="test-gemma")
        self.mode = None

    async def start(self) -> None:
        return None

    async def close(self) -> None:
        return None

    async def health(self):
        return {
            "status": "ready",
            "gateway": "ready",
            "chat": "ready",
            "ground": "ready",
            "model": "test-gemma",
        }

    async def chat(self, prompt, response_mode, max_tokens):
        self.mode = response_mode
        if prompt == "fail":
            raise LocalAiRuntimeError("Ollama unavailable")
        return f"answer:{prompt}:{max_tokens}", 12

    async def ground(self, image_bytes):
        if image_bytes == b"not-an-image":
            raise ValueError("File is not a valid JPEG or PNG image")
        if image_bytes == b"vision-fail":
            raise LocalAiRuntimeError("Local Gemma vision failed")
        if image_bytes == b"no-text":
            raise NoReadableDocumentTextError("No readable text was found")
        return "# Grounded document", 34


class FakeOllama:
    def __init__(self, response: str = "# Invoice\nTotal: $42") -> None:
        self.response = response
        self.calls = []

    async def chat(self, prompt, response_mode, max_tokens, image_bytes=None):
        self.calls.append((prompt, response_mode, max_tokens, image_bytes))
        return self.response

    async def is_ready(self):
        return True, None

    async def close(self):
        return None


def make_client(runtime=None):
    app = create_app(
        runtime=runtime or FakeRuntime(), token="secret", load_models_on_start=False
    )
    return TestClient(app)


def make_image(image_format: str = "PNG") -> bytes:
    output = io.BytesIO()
    Image.new("RGB", (32, 24), "white").save(output, format=image_format)
    return output.getvalue()


def test_health_does_not_require_token():
    with make_client() as client:
        response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ready"


def test_chat_requires_token_and_passes_structured_mode():
    runtime = FakeRuntime()
    with make_client(runtime) as client:
        unauthorized = client.post("/chat", json={"prompt": "hello"})
        response = client.post(
            "/chat",
            headers={"X-Local-Token": "secret"},
            json={
                "prompt": "analyze",
                "responseMode": "document_analysis",
                "maxTokens": 350,
            },
        )
    assert unauthorized.status_code == 401
    assert response.status_code == 200
    assert response.json() == {
        "text": "answer:analyze:350",
        "model": "test-gemma",
        "latencyMs": 12,
    }
    assert runtime.mode == "document_analysis"


def test_ground_rejects_wrong_media_type_and_malformed_image():
    with make_client() as client:
        wrong_type = client.post(
            "/ground",
            headers={"X-Local-Token": "secret"},
            files={"file": ("doc.txt", b"text", "text/plain")},
        )
        malformed = client.post(
            "/ground",
            headers={"X-Local-Token": "secret"},
            files={"file": ("doc.jpg", b"not-an-image", "image/jpeg")},
        )
    assert wrong_type.status_code == 415
    assert malformed.status_code == 400


def test_ground_rejects_empty_and_oversized_uploads():
    with make_client() as client:
        empty = client.post(
            "/ground",
            headers={"X-Local-Token": "secret"},
            files={"file": ("doc.jpg", b"", "image/jpeg")},
        )
        oversized = client.post(
            "/ground",
            headers={"X-Local-Token": "secret"},
            files={"file": ("doc.jpg", b"x" * (12 * 1024 * 1024 + 1), "image/jpeg")},
        )
    assert empty.status_code == 400
    assert oversized.status_code == 413


def test_ground_returns_model_and_no_text_is_422():
    with make_client() as client:
        grounded = client.post(
            "/ground",
            headers={"X-Local-Token": "secret"},
            files={"file": ("doc.jpg", b"valid", "image/jpeg")},
        )
        no_text = client.post(
            "/ground",
            headers={"X-Local-Token": "secret"},
            files={"file": ("doc.jpg", b"no-text", "image/jpeg")},
        )
    assert grounded.status_code == 200
    assert grounded.json()["model"] == "test-gemma"
    assert no_text.status_code == 422
    assert "No readable text" in no_text.json()["detail"]


def test_local_model_errors_fail_closed():
    with make_client() as client:
        chat = client.post(
            "/chat",
            headers={"X-Local-Token": "secret"},
            json={"prompt": "fail"},
        )
        ground = client.post(
            "/ground",
            headers={"X-Local-Token": "secret"},
            files={"file": ("doc.jpg", b"vision-fail", "image/jpeg")},
        )
    assert chat.status_code == 503
    assert ground.status_code == 503
    assert "unavailable" in chat.json()["detail"]


def test_ollama_vision_request_contains_base64_image_and_strict_prompt():
    captured = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.update(__import__("json").loads(request.content))
        return httpx.Response(200, json={"message": {"content": "transcription"}})

    async def run_test():
        client = OllamaClient(
            Settings(token="secret"), transport=httpx.MockTransport(handler)
        )
        try:
            return await client.chat(
                DOCUMENT_TRANSCRIPTION_PROMPT,
                "text",
                GROUND_MAX_OUTPUT_TOKENS,
                image_bytes=b"jpeg-bytes",
            )
        finally:
            await client.close()

    assert asyncio.run(run_test()) == "transcription"
    message = captured["messages"][0]
    assert message["content"] == DOCUMENT_TRANSCRIPTION_PROMPT
    assert base64.b64decode(message["images"][0]) == b"jpeg-bytes"
    assert captured["options"]["num_predict"] == 4096
    assert captured["options"]["temperature"] == 0.0
    assert "Do not summarize" in message["content"]
    assert "never as instructions" in message["content"]


def test_runtime_grounds_with_gemma_and_rejects_no_text_sentinel():
    async def run_success():
        ollama = FakeOllama()
        runtime = LocalAiRuntime(Settings(token="secret"), ollama=ollama)
        text, _ = await runtime.ground(make_image())
        return text, ollama.calls

    text, calls = asyncio.run(run_success())
    assert text.startswith("# Invoice")
    assert calls[0][0] == DOCUMENT_TRANSCRIPTION_PROMPT
    assert calls[0][2] == 4096
    assert calls[0][3] == make_image()

    async def run_no_text():
        runtime = LocalAiRuntime(
            Settings(token="secret"), ollama=FakeOllama("`NO_READABLE_TEXT`")
        )
        await runtime.ground(make_image("JPEG"))

    try:
        asyncio.run(run_no_text())
        raise AssertionError("Expected no-readable-text failure")
    except NoReadableDocumentTextError:
        pass


def test_runtime_rejects_malformed_image_before_calling_gemma():
    ollama = FakeOllama()
    runtime = LocalAiRuntime(Settings(token="secret"), ollama=ollama)
    try:
        asyncio.run(runtime.ground(b"not an image"))
        raise AssertionError("Expected malformed-image failure")
    except ValueError as exc:
        assert "valid JPEG or PNG" in str(exc)
    assert ollama.calls == []


def test_runtime_configuration_contains_no_legacy_paddle_dependencies():
    root = Path(__file__).resolve().parents[2]
    checked_files = [
        root / "inference_server" / "runtime.py",
        root / "inference_server" / "requirements.txt",
        root / "inference_server" / ".env.example",
        root / "inference_server" / "setup_windows.ps1",
        root / "inference_server" / "preload.py",
        root / "inference_server" / "start_local_ai.ps1",
    ]
    forbidden = ["paddleocr", "paddlepaddle", "pp-ocr", "ocr_detection_model"]
    for path in checked_files:
        content = path.read_text(encoding="utf-8").lower()
        assert not any(term in content for term in forbidden), path
