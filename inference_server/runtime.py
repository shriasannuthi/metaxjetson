from __future__ import annotations

import asyncio
import io
import os
import re
import time
from dataclasses import dataclass
from typing import Any, Literal

import httpx
import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError


ResponseMode = Literal["text", "document_analysis"]

DOCUMENT_ANALYSIS_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "documentType": {"type": "string"},
        "extractedFields": {"type": "array", "items": {"type": "string"}},
        "summary": {"type": "string"},
        "explanation": {"type": "string"},
        "riskFlags": {"type": "array", "items": {"type": "string"}},
        "recommendedActions": {"type": "array", "items": {"type": "string"}},
    },
    "required": [
        "documentType",
        "extractedFields",
        "summary",
        "explanation",
        "riskFlags",
        "recommendedActions",
    ],
}


class LocalAiRuntimeError(RuntimeError):
    pass


@dataclass(frozen=True)
class Settings:
    token: str
    ollama_url: str = "http://127.0.0.1:11434"
    model: str = "gemma3:4b-it-q4_K_M"
    context_length: int = 8192
    max_image_bytes: int = 12 * 1024 * 1024
    max_image_pixels: int = 24_000_000
    skip_model_load: bool = False

    @classmethod
    def from_environment(cls) -> "Settings":
        return cls(
            token=os.getenv("LOCAL_AI_TOKEN", "").strip(),
            ollama_url=os.getenv("OLLAMA_URL", "http://127.0.0.1:11434").rstrip("/"),
            model=os.getenv("OLLAMA_MODEL", "gemma3:4b-it-q4_K_M").strip(),
            context_length=int(os.getenv("OLLAMA_CONTEXT_LENGTH", "8192")),
            skip_model_load=os.getenv("LOCAL_AI_SKIP_MODEL_LOAD", "0") == "1",
        )


class OllamaClient:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.client = httpx.AsyncClient(
            base_url=settings.ollama_url,
            timeout=httpx.Timeout(connect=5.0, read=90.0, write=30.0, pool=5.0),
            trust_env=False,
        )

    async def close(self) -> None:
        await self.client.aclose()

    async def is_ready(self) -> tuple[bool, str | None]:
        try:
            response = await self.client.get("/api/tags")
            response.raise_for_status()
            models = {item.get("name", "") for item in response.json().get("models", [])}
            is_present = any(
                name == self.settings.model or name.startswith(f"{self.settings.model}:")
                for name in models
            )
            if not is_present:
                return False, f"Model {self.settings.model} is not installed"
            return True, None
        except Exception as exc:  # Health reports the reason instead of failing the process.
            return False, str(exc)

    async def chat(self, prompt: str, response_mode: ResponseMode, max_tokens: int) -> str:
        payload: dict[str, Any] = {
            "model": self.settings.model,
            "messages": [{"role": "user", "content": prompt}],
            "stream": False,
            "keep_alive": -1,
            "options": {
                "num_ctx": self.settings.context_length,
                "num_predict": max_tokens,
                "temperature": 0.2,
                "top_p": 0.9,
            },
        }
        if response_mode == "document_analysis":
            payload["format"] = DOCUMENT_ANALYSIS_SCHEMA

        try:
            response = await self.client.post("/api/chat", json=payload)
            response.raise_for_status()
            text = response.json().get("message", {}).get("content", "").strip()
        except httpx.HTTPStatusError as exc:
            detail = exc.response.text[:500]
            raise LocalAiRuntimeError(
                f"Ollama returned HTTP {exc.response.status_code}: {detail}"
            ) from exc
        except httpx.HTTPError as exc:
            raise LocalAiRuntimeError(f"Cannot reach local Ollama: {exc}") from exc

        if not text:
            raise LocalAiRuntimeError("Ollama returned an empty response")
        return text


class OcrEngine:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.pipeline: Any | None = None
        self.load_error: str | None = None

    @property
    def ready(self) -> bool:
        return self.pipeline is not None

    def load(self) -> None:
        if self.pipeline is not None:
            return
        try:
            # PaddlePaddle 3.3.0 has a known PIR-to-oneDNN CPU regression on Windows.
            os.environ["FLAGS_use_mkldnn"] = "0"
            from paddleocr import PPStructureV3

            self.pipeline = PPStructureV3(
                device="cpu",
                enable_mkldnn=False,
                cpu_threads=8,
                layout_detection_model_name="PicoDet-L_layout_17cls",
                text_detection_model_name=os.getenv(
                    "OCR_DETECTION_MODEL", "PP-OCRv5_server_det"
                ),
                text_recognition_model_name="PP-OCRv5_server_rec",
                use_doc_orientation_classify=True,
                use_doc_unwarping=True,
                use_textline_orientation=True,
                use_seal_recognition=False,
                use_table_recognition=True,
                use_formula_recognition=False,
                use_chart_recognition=False,
                use_region_detection=True,
            )
            self.load_error = None
        except Exception as exc:
            self.load_error = str(exc)
            raise LocalAiRuntimeError(f"Unable to load local OCR models: {exc}") from exc

    def ground(self, image_bytes: bytes) -> str:
        if len(image_bytes) > self.settings.max_image_bytes:
            raise ValueError(
                f"Image exceeds the {self.settings.max_image_bytes // (1024 * 1024)} MB limit"
            )
        if self.pipeline is None:
            raise LocalAiRuntimeError(self.load_error or "Local OCR is not ready")

        try:
            with Image.open(io.BytesIO(image_bytes)) as source:
                source.verify()
            with Image.open(io.BytesIO(image_bytes)) as source:
                image = ImageOps.exif_transpose(source).convert("RGB")
                if image.width * image.height > self.settings.max_image_pixels:
                    raise ValueError("Image dimensions are too large")
                image_array = np.asarray(image)
        except (UnidentifiedImageError, OSError) as exc:
            raise ValueError("File is not a valid JPEG or PNG image") from exc

        try:
            output = self.pipeline.predict(
                image_array,
                use_doc_orientation_classify=True,
                use_doc_unwarping=True,
                use_textline_orientation=True,
                use_seal_recognition=False,
                use_table_recognition=True,
                use_formula_recognition=False,
                use_chart_recognition=False,
                use_region_detection=True,
                format_block_content=True,
            )
            pages = [self._extract_markdown(result) for result in output]
        except Exception as exc:
            raise LocalAiRuntimeError(f"Local OCR failed: {exc}") from exc

        text = "\n\n".join(page for page in pages if page).strip()
        if not text:
            raise LocalAiRuntimeError("Local OCR did not find readable text")
        return text

    @staticmethod
    def _extract_markdown(result: Any) -> str:
        structured_text = ""
        markdown = getattr(result, "markdown", None)
        if isinstance(markdown, dict):
            value = (
                markdown.get("markdown_texts")
                or markdown.get("markdown_text")
                or markdown.get("text")
            )
            if isinstance(value, list):
                structured_text = "\n\n".join(str(item) for item in value).strip()
            elif value:
                structured_text = str(value).strip()

        structured_text = OcrEngine._remove_image_references(structured_text)
        ocr_text = OcrEngine._extract_recognized_text(result)

        structured_chars = OcrEngine._content_char_count(structured_text)
        ocr_chars = OcrEngine._content_char_count(ocr_text)
        if ocr_chars > max(12, int(structured_chars * 1.25)):
            return ocr_text
        if structured_chars >= 5:
            return structured_text
        return ocr_text

    @staticmethod
    def _extract_recognized_text(result: Any) -> str:
        json_result = getattr(result, "json", None)
        if not isinstance(json_result, dict):
            return ""
        payload = json_result.get("res", json_result)

        blocks = payload.get("parsing_res_list", [])
        block_text = "\n\n".join(
            str(block.get("block_content", "")).strip()
            for block in blocks
            if block.get("block_label") != "image" and block.get("block_content")
        ).strip()

        overall_ocr = payload.get("overall_ocr_res", {})
        texts = overall_ocr.get("rec_texts", [])
        scores = overall_ocr.get("rec_scores", [])
        recognized_lines = []
        for index, text in enumerate(texts):
            value = str(text).strip()
            if not value:
                continue
            score = float(scores[index]) if index < len(scores) else 1.0
            if score >= 0.45:
                recognized_lines.append(value)
        recognized_text = "\n".join(recognized_lines).strip()

        if OcrEngine._content_char_count(recognized_text) > OcrEngine._content_char_count(
            block_text
        ):
            return recognized_text
        return OcrEngine._remove_image_references(block_text)

    @staticmethod
    def _remove_image_references(text: str) -> str:
        if not text:
            return ""
        cleaned = re.sub(r"<img\b[^>]*>", "", text, flags=re.IGNORECASE)
        cleaned = re.sub(r"!\[[^\]]*]\([^)]*\)", "", cleaned)
        cleaned = re.sub(r"</?div\b[^>]*>", "", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"<br\s*/?>", "\n", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"\n{3,}", "\n\n", cleaned)
        return cleaned.strip()

    @staticmethod
    def _content_char_count(text: str) -> int:
        return sum(character.isalnum() for character in text)


class LocalAiRuntime:
    def __init__(
        self,
        settings: Settings,
        ollama: OllamaClient | None = None,
        ocr: OcrEngine | None = None,
    ) -> None:
        self.settings = settings
        self.ollama = ollama or OllamaClient(settings)
        self.ocr = ocr or OcrEngine(settings)
        self.ocr_lock = asyncio.Lock()

    async def start(self) -> None:
        if not self.settings.skip_model_load:
            await asyncio.to_thread(self.ocr.load)

    async def close(self) -> None:
        await self.ollama.close()

    async def health(self) -> dict[str, Any]:
        ollama_ready, ollama_error = await self.ollama.is_ready()
        ocr_ready = self.ocr.ready
        return {
            "status": "ready" if ollama_ready and ocr_ready else "degraded",
            "gateway": "ready",
            "chat": "ready" if ollama_ready else "unavailable",
            "ground": "ready" if ocr_ready else "unavailable",
            "model": self.settings.model,
            "ollamaError": ollama_error,
            "ocrError": self.ocr.load_error,
        }

    async def chat(
        self, prompt: str, response_mode: ResponseMode, max_tokens: int
    ) -> tuple[str, int]:
        started = time.perf_counter()
        text = await self.ollama.chat(prompt, response_mode, max_tokens)
        return text, round((time.perf_counter() - started) * 1000)

    async def ground(self, image_bytes: bytes) -> tuple[str, int]:
        started = time.perf_counter()
        async with self.ocr_lock:
            text = await asyncio.to_thread(self.ocr.ground, image_bytes)
        return text, round((time.perf_counter() - started) * 1000)
