from __future__ import annotations

import asyncio
import logging
import os
import time
from dataclasses import dataclass
from typing import Any, Literal

import httpx


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

DEFAULT_MODEL = "gemma3:4b-it-q4_K_M"

logger = logging.getLogger(__name__)


class LocalAiRuntimeError(RuntimeError):
    pass


@dataclass(frozen=True)
class Settings:
    token: str
    ollama_url: str = "http://127.0.0.1:11434"
    model: str = DEFAULT_MODEL
    context_length: int = 4096

    @classmethod
    def from_environment(cls) -> "Settings":
        return cls(
            token=os.getenv("LOCAL_AI_TOKEN", "").strip(),
            ollama_url=os.getenv("OLLAMA_URL", "http://127.0.0.1:11434").rstrip("/"),
            model=os.getenv("OLLAMA_MODEL", DEFAULT_MODEL).strip(),
            context_length=int(os.getenv("OLLAMA_CONTEXT_LENGTH", "4096")),
        )


class OllamaClient:
    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.settings = settings
        self.client = httpx.AsyncClient(
            base_url=settings.ollama_url,
            timeout=httpx.Timeout(connect=5.0, read=120.0, write=30.0, pool=5.0),
            trust_env=False,
            transport=transport,
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

    async def chat(
        self,
        prompt: str,
        response_mode: ResponseMode,
        max_tokens: int,
    ) -> str:
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

        return text


class LocalAiRuntime:
    def __init__(
        self,
        settings: Settings,
        ollama: OllamaClient | None = None,
    ) -> None:
        self.settings = settings
        self.ollama = ollama or OllamaClient(settings)
        self.model_lock = asyncio.Lock()

    async def start(self) -> None:
        return None

    async def close(self) -> None:
        await self.ollama.close()

    async def health(self) -> dict[str, Any]:
        ollama_ready, ollama_error = await self.ollama.is_ready()
        return {
            "status": "ready" if ollama_ready else "degraded",
            "gateway": "ready",
            "chat": "ready" if ollama_ready else "unavailable",
            "model": self.settings.model,
            "ollamaError": ollama_error,
        }

    async def chat(
        self, prompt: str, response_mode: ResponseMode, max_tokens: int
    ) -> tuple[str, int]:
        started = time.perf_counter()
        async with self.model_lock:
            text = await self.ollama.chat(prompt, response_mode, max_tokens)
        if not text:
            raise LocalAiRuntimeError("Ollama returned an empty response")
        return text, round((time.perf_counter() - started) * 1000)
