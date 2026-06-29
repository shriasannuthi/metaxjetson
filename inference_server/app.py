from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, AsyncIterator, Literal

from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from inference_server.runtime import (
    LocalAiRuntime,
    LocalAiRuntimeError,
    NoReadableDocumentTextError,
    Settings,
)


class ChatRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=50_000)
    responseMode: Literal["text", "document_analysis"] = "text"
    maxTokens: int = Field(default=320, ge=1, le=2_048)


class TextResponse(BaseModel):
    text: str
    model: str | None = None
    latencyMs: int


def create_app(
    runtime: LocalAiRuntime | Any | None = None,
    token: str | None = None,
    load_models_on_start: bool = True,
) -> FastAPI:
    settings = Settings.from_environment()
    active_runtime = runtime or LocalAiRuntime(settings)
    expected_token = settings.token if token is None else token

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        if load_models_on_start:
            try:
                await active_runtime.start()
            except LocalAiRuntimeError:
                # Keep /health available with the exact startup failure.
                pass
        yield
        close = getattr(active_runtime, "close", None)
        if close is not None:
            await close()

    app = FastAPI(
        title="Local Meta AI Gateway",
        version="1.0.0",
        docs_url=None,
        redoc_url=None,
        lifespan=lifespan,
    )

    async def require_token(x_local_token: str | None = Header(default=None)) -> None:
        if not expected_token:
            raise HTTPException(status_code=503, detail="LOCAL_AI_TOKEN is not configured")
        if x_local_token != expected_token:
            raise HTTPException(status_code=401, detail="Invalid local AI token")

    @app.exception_handler(LocalAiRuntimeError)
    async def runtime_error_handler(_, exc: LocalAiRuntimeError) -> JSONResponse:
        return JSONResponse(status_code=503, content={"detail": str(exc)})

    @app.exception_handler(NoReadableDocumentTextError)
    async def no_readable_text_handler(
        _, exc: NoReadableDocumentTextError
    ) -> JSONResponse:
        return JSONResponse(status_code=422, content={"detail": str(exc)})

    @app.get("/health")
    async def health() -> dict[str, Any]:
        return await active_runtime.health()

    @app.post("/chat", response_model=TextResponse, dependencies=[Depends(require_token)])
    async def chat(request: ChatRequest) -> TextResponse:
        text, latency_ms = await active_runtime.chat(
            request.prompt.strip(), request.responseMode, request.maxTokens
        )
        return TextResponse(
            text=text,
            model=getattr(getattr(active_runtime, "settings", None), "model", None),
            latencyMs=latency_ms,
        )

    @app.post("/ground", response_model=TextResponse, dependencies=[Depends(require_token)])
    async def ground(file: UploadFile = File(...)) -> TextResponse:
        if file.content_type not in {"image/jpeg", "image/png"}:
            raise HTTPException(status_code=415, detail="Only JPEG and PNG images are supported")
        image_bytes = await file.read(settings.max_image_bytes + 1)
        if not image_bytes:
            raise HTTPException(status_code=400, detail="Image file is empty")
        if len(image_bytes) > settings.max_image_bytes:
            raise HTTPException(status_code=413, detail="Image exceeds the 12 MB limit")
        try:
            text, latency_ms = await active_runtime.ground(image_bytes)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return TextResponse(
            text=text,
            model=getattr(getattr(active_runtime, "settings", None), "model", None),
            latencyMs=latency_ms,
        )

    return app


app = create_app()
