from types import SimpleNamespace

from fastapi.testclient import TestClient

from inference_server.app import create_app
from inference_server.runtime import LocalAiRuntimeError, OcrEngine


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
        if image_bytes == b"ocr-fail":
            raise LocalAiRuntimeError("Local OCR failed")
        return "# Grounded document", 34


def make_client(runtime=None):
    app = create_app(
        runtime=runtime or FakeRuntime(), token="secret", load_models_on_start=False
    )
    return TestClient(app)


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
            files={"file": ("doc.jpg", b"ocr-fail", "image/jpeg")},
        )
    assert chat.status_code == 503
    assert ground.status_code == 503
    assert "unavailable" in chat.json()["detail"]


def test_ocr_ignores_generated_image_reference_and_uses_recognized_text():
    result = SimpleNamespace(
        markdown={
            "markdown_texts": (
                '<div style="text-align:center"><img '
                'src="imgs/img_in_image_box_0_2_480_639.jpg" alt="Image" /></div>'
            )
        },
        json={
            "res": {
                "parsing_res_list": [
                    {"block_label": "image", "block_content": "imgs/generated.jpg"}
                ],
                "overall_ocr_res": {
                    "rec_texts": ["Account statement", "Closing balance: $1,250.00"],
                    "rec_scores": [0.99, 0.97],
                },
            }
        },
    )

    grounded = OcrEngine._extract_markdown(result)

    assert grounded == "Account statement\nClosing balance: $1,250.00"
    assert "img_in_image_box" not in grounded


def test_ocr_prefers_richer_recognized_text_over_partial_layout_text():
    result = SimpleNamespace(
        markdown={"markdown_texts": "Fields"},
        json={
            "res": {
                "parsing_res_list": [],
                "overall_ocr_res": {
                    "rec_texts": ["Application form", "Name: Ada Lovelace", "Status: Approved"],
                    "rec_scores": [0.98, 0.99, 0.96],
                },
            }
        },
    )

    assert OcrEngine._extract_markdown(result) == (
        "Application form\nName: Ada Lovelace\nStatus: Approved"
    )
