from __future__ import annotations

import asyncio

from inference_server.runtime import LocalAiRuntime, Settings


async def main() -> None:
    runtime = LocalAiRuntime(Settings.from_environment())
    try:
        print("Loading PP-StructureV3 and downloading any missing OCR models...")
        await runtime.start()
        print("Warming Gemma through the local Ollama API...")
        text, _ = await runtime.chat("Reply with only: ready", "text", 8)
        print(f"Gemma response: {text}")
        health = await runtime.health()
        if health["status"] != "ready":
            raise RuntimeError(f"Local AI preload did not become ready: {health}")
        print("Local AI models are downloaded and ready for offline use.")
    finally:
        await runtime.close()


if __name__ == "__main__":
    asyncio.run(main())
