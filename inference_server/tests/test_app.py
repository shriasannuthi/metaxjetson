import asyncio
from pathlib import Path
from types import SimpleNamespace

import httpx
from fastapi.testclient import TestClient

from inference_server.app import create_app
from inference_server.runtime import (
    DOCUMENT_ANALYSIS_SCHEMA,
    LocalAiRuntime,
    LocalAiRuntimeError,
    OllamaClient,
    Settings,
)


class FakeRuntime:
    def __init__(self) -> None:
        self.settings = SimpleNamespace(model="test-qwen")
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
            "model": "test-qwen",
            "ollamaError": None,
        }

    async def chat(self, prompt, response_mode, max_tokens):
        self.mode = response_mode
        if prompt == "fail":
            raise LocalAiRuntimeError("Ollama unavailable")
        return f"answer:{prompt}:{max_tokens}", 12


class FakeOllama:
    def __init__(self, responses=None) -> None:
        self.responses = responses or ["Local answer"]
        if isinstance(self.responses, str):
            self.responses = [self.responses]
        self.calls = []

    async def chat(self, prompt, response_mode, max_tokens):
        self.calls.append((prompt, response_mode, max_tokens))
        index = min(len(self.calls) - 1, len(self.responses) - 1)
        return self.responses[index]

    async def is_ready(self):
        return True, None

    async def close(self):
        return None


def make_client(runtime=None):
    app = create_app(
        runtime=runtime or FakeRuntime(), token="secret", load_models_on_start=False
    )
    return TestClient(app)


def test_settings_default_model_and_environment_override(monkeypatch):
    monkeypatch.delenv("OLLAMA_MODEL", raising=False)
    monkeypatch.delenv("OLLAMA_CONTEXT_LENGTH", raising=False)
    assert Settings.from_environment().model == "gemma3:4b-it-q4_K_M"
    assert Settings.from_environment().context_length == 4096

    monkeypatch.setenv("OLLAMA_MODEL", "custom-local-text:latest")
    assert Settings.from_environment().model == "custom-local-text:latest"


def test_health_does_not_require_token_and_reports_text_only_readiness():
    with make_client() as client:
        response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ready"
    assert body["chat"] == "ready"
    assert "ground" not in body


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
        "model": "test-qwen",
        "latencyMs": 12,
    }
    assert runtime.mode == "document_analysis"


def test_ground_endpoint_is_removed():
    with make_client() as client:
        response = client.post(
            "/ground",
            headers={"X-Local-Token": "secret"},
            files={"file": ("doc.jpg", b"not-used", "image/jpeg")},
        )
    assert response.status_code == 404


def test_local_model_errors_fail_closed():
    with make_client() as client:
        chat = client.post(
            "/chat",
            headers={"X-Local-Token": "secret"},
            json={"prompt": "fail"},
        )
    assert chat.status_code == 503
    assert "unavailable" in chat.json()["detail"]


def test_ollama_text_request_uses_plain_prompt_and_document_schema():
    captured = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.update(__import__("json").loads(request.content))
        return httpx.Response(200, json={"message": {"content": "analysis-json"}})

    async def run_test():
        client = OllamaClient(
            Settings(token="secret", model="gemma3:4b-it-q4_K_M"),
            transport=httpx.MockTransport(handler),
        )
        try:
            return await client.chat(
                "Analyze this OCR transcription",
                "document_analysis",
                350,
            )
        finally:
            await client.close()

    assert asyncio.run(run_test()) == "analysis-json"
    message = captured["messages"][0]
    assert message["content"] == "Analyze this OCR transcription"
    assert "images" not in message
    assert captured["format"] == DOCUMENT_ANALYSIS_SCHEMA
    assert "think" not in captured
    assert captured["options"]["num_ctx"] == 4096
    assert captured["options"]["num_predict"] == 350
    assert captured["options"]["temperature"] == 0.2


def test_custom_models_are_not_given_thinking_controls():
    captured = {}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.update(__import__("json").loads(request.content))
        return httpx.Response(200, json={"message": {"content": "answer"}})

    async def run_test():
        client = OllamaClient(
            Settings(token="secret", model="custom-local-text"),
            transport=httpx.MockTransport(handler),
        )
        try:
            return await client.chat("hello", "text", 8)
        finally:
            await client.close()

    assert asyncio.run(run_test()) == "answer"
    assert captured["messages"][0]["content"] == "hello"
    assert "think" not in captured


def test_runtime_serializes_chat_requests_and_rejects_empty_output():
    ollama = FakeOllama(["", "second"])
    runtime = LocalAiRuntime(Settings(token="secret"), ollama=ollama)

    try:
        asyncio.run(runtime.chat("first", "text", 8))
        raise AssertionError("Expected empty-response failure")
    except LocalAiRuntimeError as exc:
        assert "empty response" in str(exc)

    text, _ = asyncio.run(runtime.chat("second", "text", 8))
    assert text == "second"
    assert ollama.calls == [("first", "text", 8), ("second", "text", 8)]


def test_runtime_configuration_contains_no_legacy_paddle_or_image_dependencies():
    root = Path(__file__).resolve().parents[2]
    checked_files = [
        root / "inference_server" / "runtime.py",
        root / "inference_server" / "app.py",
        root / "inference_server" / "requirements.txt",
        root / "inference_server" / ".env.example",
        root / "inference_server" / "setup_jetson.sh",
        root / "inference_server" / "preload.py",
        root / "inference_server" / "verify_jetson.sh",
    ]
    forbidden = [
        "paddleocr",
        "paddlepaddle",
        "pp-ocr",
        "ocr_detection_model",
        "python-multipart",
        "pillow",
        "from pil",
        "image-input probe",
        "base64",
    ]
    for path in checked_files:
        content = path.read_text(encoding="utf-8").lower()
        assert not any(term in content for term in forbidden), path


def test_scripts_and_config_use_canonical_configurable_jetson_model():
    root = Path(__file__).resolve().parents[2]
    paths = [
        root / "inference_server" / "runtime.py",
        root / "inference_server" / ".env.example",
        root / "inference_server" / "setup_jetson.sh",
        root / "inference_server" / "verify_jetson.sh",
    ]
    combined = "\n".join(path.read_text(encoding="utf-8") for path in paths)
    assert "gemma3:4b-it-q4_K_M" in combined
    assert "qwen3:4b" not in combined
    assert "qwen3-vl:8b" not in combined
    assert 'ollama pull "$MODEL"' in combined
    assert "OLLAMA_MODEL" in combined


def test_jetson_services_are_loopback_only_memory_tuned_and_hardened():
    root = Path(__file__).resolve().parents[2]
    systemd = root / "inference_server" / "systemd"
    gateway = (systemd / "metax-gateway.service").read_text(encoding="utf-8")
    ollama = (systemd / "ollama-loopback.conf").read_text(encoding="utf-8")
    adb = (systemd / "metax-adb-reverse.service").read_text(encoding="utf-8")
    assert "--host 127.0.0.1" in gateway
    assert "OLLAMA_HOST=127.0.0.1:11434" in ollama
    assert "OLLAMA_FLASH_ATTENTION=1" in ollama
    assert "OLLAMA_KV_CACHE_TYPE=q8_0" in ollama
    assert "NoNewPrivileges=true" in gateway and "ProtectSystem=strict" in gateway
    assert "NoNewPrivileges=true" in adb and "Restart=always" in adb
    assert "ReadWritePaths=@USER_HOME@/.android" in adb


def test_jetson_setup_enforces_hardware_credentials_and_gpu_validation():
    root = Path(__file__).resolve().parents[2]
    setup = (root / "inference_server" / "setup_jetson.sh").read_text(encoding="utf-8")
    verify = (root / "inference_server" / "verify_jetson.sh").read_text(encoding="utf-8")
    for expected in ("aarch64", "p3767-0003", "30GB", "25W", "chmod 600"):
        assert expected in setup
    assert "100% GPU" in verify
    assert "tegrastats" in verify
    assert "image-input probe" not in verify


def test_adb_manager_requires_one_phone_and_recovers_reverse_rule():
    root = Path(__file__).resolve().parents[2]
    manager = (root / "inference_server" / "adb_reverse_manager.sh").read_text(encoding="utf-8")
    assert "unauthorized" not in manager  # all non-device states fail closed
    assert "more than one authorized physical Android phone" in manager
    assert 'reverse "tcp:${PORT}" "tcp:${PORT}"' in manager
    assert "while true" in manager
