"""The only door from the internet to this machine during a phone call.

Plivo needs to reach two things: the CRM's three call hooks and the engine's call stream. Everything else must stay
private: the CRM's sign-in (while one-time codes are shown on screen, anyone with a staff number could sign in), its
whole API, and the engine's /stt and /tts. So the tunnel points here, never at the CRM or the engine:

    POST /api/voice/hooks/plivo/answer | hangup | desk  ->  the CRM, byte for byte (Plivo's signature is checked there)
    WebSocket /call?call_id=...&token=...               ->  the engine (the token is checked there)
    anything else                                       ->  404

Set PLIVO_PUBLIC_URL to the tunnel's https:// address and PLIVO_ENGINE_URL to wss://<the same address>/call.

Run:  uvicorn gateway:app --host 127.0.0.1 --port 8099
"""

import asyncio
import os

import httpx
import websockets
from fastapi import FastAPI, Request, Response, WebSocket
from fastapi.responses import PlainTextResponse
from starlette.websockets import WebSocketDisconnect

CRM_URL = os.environ.get("CRM_URL", "http://127.0.0.1:8082").rstrip("/")
ENGINE_URL = os.environ.get("ENGINE_URL", "http://127.0.0.1:8090").rstrip("/")
HOOKS = ("answer", "hangup", "desk")
# Plivo's callbacks are small forms; anything bigger is not Plivo.
MAX_BODY_BYTES = 64 * 1024
# Only what the CRM needs to check and read Plivo's request; cookies and anything else are dropped.
PASS_HEADERS = ("content-type", "x-plivo-signature-v3", "x-plivo-signature-v3-nonce")

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)


def crm_client() -> httpx.AsyncClient:
    """The connection to the CRM; tests replace it with a fake."""
    return httpx.AsyncClient(timeout=15.0)


def engine_ws_url(query: str) -> str:
    base = "ws" + ENGINE_URL[4:] if ENGINE_URL.startswith("http") else ENGINE_URL
    return f"{base}/call" + (f"?{query}" if query else "")


@app.post("/api/voice/hooks/plivo/{hook}")
async def plivo_hook(hook: str, request: Request):
    if hook not in HOOKS:
        return PlainTextResponse("Not found", 404)
    body = await request.body()
    if len(body) > MAX_BODY_BYTES:
        return PlainTextResponse("Too large", 413)
    headers = {k: v for k, v in request.headers.items() if k.lower() in PASS_HEADERS}
    url = f"{CRM_URL}/api/voice/hooks/plivo/{hook}" + (f"?{request.url.query}" if request.url.query else "")
    try:
        async with crm_client() as client:
            r = await client.post(url, content=body, headers=headers)
    except httpx.HTTPError:
        return PlainTextResponse("The CRM is not reachable", 502)
    return Response(r.content, r.status_code, media_type=r.headers.get("content-type"))


@app.websocket("/call")
async def call(ws: WebSocket):
    try:
        upstream = await websockets.connect(engine_ws_url(ws.url.query), max_size=2**20)
    except (OSError, websockets.InvalidHandshake, websockets.InvalidURI):
        # Wrong or missing token (the engine refuses it before accepting), or the engine is down.
        await ws.close(code=1008)
        return
    await ws.accept()

    async def to_engine():
        while True:
            msg = await ws.receive()
            if msg["type"] == "websocket.disconnect":
                return
            if msg.get("text") is not None:
                await upstream.send(msg["text"])
            elif msg.get("bytes") is not None:
                await upstream.send(msg["bytes"])

    async def to_plivo():
        async for msg in upstream:
            if isinstance(msg, str):
                await ws.send_text(msg)
            else:
                await ws.send_bytes(msg)

    tasks = [asyncio.create_task(to_engine()), asyncio.create_task(to_plivo())]
    try:
        # Whichever side ends first (caller hung up, or the engine closed after the goodbye) ends the other.
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for t in tasks:
            t.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await upstream.close()
        try:
            await ws.close()
        except (RuntimeError, WebSocketDisconnect):
            pass                # already closed by the other side
