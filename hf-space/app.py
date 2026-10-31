import os
import json
import asyncio
from typing import List, Optional, Dict, Any, AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request, Depends, Header
from fastapi.responses import StreamingResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import uvicorn
import httpx

# ─── Config ──────────────────────────────────────────────────────────────
HF_TOKEN = os.getenv("HF_TOKEN", "")
DEFAULT_MODEL = os.getenv("DEFAULT_MODEL", "meta-llama/Llama-3.3-70B-Instruct")
MAX_TOKENS = int(os.getenv("MAX_TOKENS", "4096"))
TEMPERATURE = float(os.getenv("TEMPERATURE", "0.7"))
TOP_P = float(os.getenv("TOP_P", "0.95"))

# Hugging Face Inference API endpoint
HF_API_URL = "https://api-inference.huggingface.co/models"

# ─── Models ──────────────────────────────────────────────────────────────
class Message(BaseModel):
    role: str
    content: str
    name: Optional[str] = None

class ChatCompletionRequest(BaseModel):
    model: str
    messages: List[Message]
    temperature: Optional[float] = TEMPERATURE
    top_p: Optional[float] = TOP_P
    max_tokens: Optional[int] = MAX_TOKENS
    stream: Optional[bool] = False
    stop: Optional[List[str]] = None
    presence_penalty: Optional[float] = 0.0
    frequency_penalty: Optional[float] = 0.0
    user: Optional[str] = None

class ChatCompletionChoice(BaseModel):
    index: int
    message: Message
    finish_reason: Optional[str] = None

class ChatCompletionResponse(BaseModel):
    id: str
    object: str = "chat.completion"
    created: int
    model: str
    choices: List[ChatCompletionChoice]
    usage: Dict[str, int]

class ModelInfo(BaseModel):
    id: str
    object: str = "model"
    created: int
    owned_by: str = "huggingface"

class ModelsResponse(BaseModel):
    object: str = "list"
    data: List[ModelInfo]

# ─── Available Models (curated list of good free models on HF) ───────────
AVAILABLE_MODELS = [
    "meta-llama/Llama-3.3-70B-Instruct",
    "meta-llama/Llama-3.1-70B-Instruct",
    "meta-llama/Llama-3.1-8B-Instruct",
    "mistralai/Mistral-7B-Instruct-v0.3",
    "mistralai/Mixtral-8x7B-Instruct-v0.1",
    "google/gemma-2-27b-it",
    "google/gemma-2-9b-it",
    "Qwen/Qwen2.5-72B-Instruct",
    "Qwen/Qwen2.5-32B-Instruct",
    "Qwen/Qwen2.5-14B-Instruct",
    "Qwen/Qwen2.5-7B-Instruct",
    "microsoft/Phi-3.5-mini-instruct",
    "microsoft/Phi-3.5-MoE-instruct",
    "nvidia/Nemotron-3-Ultra",
    "nvidia/Nemotron-3-Super",
    "cohere/command-r-plus",
    "cohere/command-r",
    "deepseek-ai/DeepSeek-V3",
    "deepseek-ai/DeepSeek-R1",
    "deepseek-ai/DeepSeek-Coder-V2-Instruct",
    "01-ai/Yi-1.5-34B-Chat",
    "01-ai/Yi-1.5-9B-Chat",
]

# ─── HTTP Client ─────────────────────────────────────────────────────────
http_client: Optional[httpx.AsyncClient] = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global http_client
    http_client = httpx.AsyncClient(timeout=httpx.Timeout(120.0))
    yield
    await http_client.aclose()

# ─── FastAPI App ─────────────────────────────────────────────────────────
app = FastAPI(
    title="LionAI Hugging Face Space API",
    description="OpenAI-compatible API proxy for Hugging Face Inference API",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ─── Helpers ─────────────────────────────────────────────────────────────
async def call_hf_inference(model: str, payload: Dict[str, Any]) -> Dict[str, Any]:
    """Call Hugging Face Inference API."""
    if not http_client:
        raise HTTPException(503, "HTTP client not initialized")
    
    url = f"{HF_API_URL}/{model}"
    headers = {"Content-Type": "application/json"}
    if HF_TOKEN:
        headers["Authorization"] = f"Bearer {HF_TOKEN}"
    
    try:
        response = await http_client.post(url, json=payload, headers=headers)
        if response.status_code == 404:
            raise HTTPException(404, f"Model '{model}' not found on Hugging Face")
        if response.status_code == 401:
            raise HTTPException(401, "Invalid or missing HF_TOKEN")
        if response.status_code == 503:
            # Model is loading, wait and retry
            await asyncio.sleep(10)
            response = await http_client.post(url, json=payload, headers=headers)
        response.raise_for_status()
        return response.json()
    except httpx.HTTPStatusError as e:
        raise HTTPException(e.response.status_code, f"HF API error: {e.response.text}")
    except Exception as e:
        raise HTTPException(500, f"Error calling HF API: {str(e)}")

def format_messages_for_hf(messages: List[Message]) -> str:
    """Convert OpenAI messages to HF chat template format."""
    formatted = []
    for msg in messages:
        if msg.role == "system":
            formatted.append(f"<|system|>\n{msg.content}<|end|>")
        elif msg.role == "user":
            formatted.append(f"<|user|>\n{msg.content}<|end|>")
        elif msg.role == "assistant":
            formatted.append(f"<|assistant|>\n{msg.content}<|end|>")
        else:
            formatted.append(f"<|user|>\n{msg.content}<|end|>")
    formatted.append("<|assistant|>")
    return "\n".join(formatted)

async def stream_hf_response(model: str, payload: Dict[str, Any]) -> AsyncGenerator[str, None]:
    """Stream response from HF Inference API."""
    if not http_client:
        raise HTTPException(503, "HTTP client not initialized")
    
    url = f"{HF_API_URL}/{model}"
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
    if HF_TOKEN:
        headers["Authorization"] = f"Bearer {HF_TOKEN}"
    
    payload["stream"] = True
    
    try:
        async with http_client.stream("POST", url, json=payload, headers=headers, timeout=120.0) as response:
            if response.status_code == 404:
                yield f"data: {json.dumps({'error': f'Model {model} not found'})}\n\n"
                return
            if response.status_code == 401:
                yield f"data: {json.dumps({'error': 'Invalid HF_TOKEN'})}\n\n"
                return
            if response.status_code == 503:
                await asyncio.sleep(10)
                async with http_client.stream("POST", url, json=payload, headers=headers, timeout=120.0) as retry_response:
                    async for line in retry_response.aiter_lines():
                        if line.startswith("data: "):
                            yield line + "\n\n"
                    return
            
            async for line in response.aiter_lines():
                if line.startswith("data: "):
                    yield line + "\n\n"
    except Exception as e:
        yield f"data: {json.dumps({'error': str(e)})}\n\n"
    yield "data: [DONE]\n\n"

# ─── Auth Dependency ─────────────────────────────────────────────────────
async def verify_api_key(authorization: Optional[str] = Header(None)):
    """Optional API key verification. Set HF_SPACE_API_KEY env var to enable."""
    expected_key = os.getenv("HF_SPACE_API_KEY")
    if expected_key and authorization:
        if not authorization.startswith("Bearer ") or authorization[7:] != expected_key:
            raise HTTPException(401, "Invalid API key")
    elif expected_key and not authorization:
        raise HTTPException(401, "API key required")

# ─── Endpoints ───────────────────────────────────────────────────────────
@app.get("/health")
async def health():
    return {"status": "ok", "service": "lionai-hf-space"}

@app.get("/v1/models", response_model=ModelsResponse)
async def list_models(_: None = Depends(verify_api_key)):
    """List available models (OpenAI compatible)."""
    import time
    return ModelsResponse(
        data=[
            ModelInfo(id=model, created=int(time.time()))
            for model in AVAILABLE_MODELS
        ]
    )

@app.post("/v1/chat/completions")
async def chat_completions(
    request: ChatCompletionRequest,
    _: None = Depends(verify_api_key),
):
    """OpenAI-compatible chat completions endpoint."""
    import time
    import uuid
    
    model = request.model
    if model not in AVAILABLE_MODELS:
        # Allow any model name, but warn if not in curated list
        pass
    
    # Convert messages to HF format
    prompt = format_messages_for_hf(request.messages)
    
    payload = {
        "inputs": prompt,
        "parameters": {
            "max_new_tokens": request.max_tokens or MAX_TOKENS,
            "temperature": request.temperature or TEMPERATURE,
            "top_p": request.top_p or TOP_P,
            "return_full_text": False,
            "stop": request.stop or ["<|end|>", "<|user|>", "<|system|>"],
        },
        "stream": request.stream,
    }
    
    if request.stream:
        async def generate() -> AsyncGenerator[str, None]:
            completion_id = f"chatcmpl-{uuid.uuid4().hex[:29]}"
            created = int(time.time())
            
            async for chunk in stream_hf_response(model, payload):
                if chunk.startswith("data: ") and chunk != "data: [DONE]\n\n":
                    try:
                        data = json.loads(chunk[6:].strip())
                        if "token" in data or "generated_text" in data:
                            text = data.get("token", {}).get("text", data.get("generated_text", ""))
                            if text:
                                yield f"data: {json.dumps({
                                    'id': completion_id,
                                    'object': 'chat.completion.chunk',
                                    'created': created,
                                    'model': model,
                                    'choices': [{
                                        'index': 0,
                                        'delta': {'content': text},
                                        'finish_reason': None
                                    }]
                                })}\n\n"
                    except:
                        pass
            yield "data: [DONE]\n\n"
        
        return StreamingResponse(
            generate(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "Connection": "keep-alive"},
        )
    
    # Non-streaming
    result = await call_hf_inference(model, payload)
    
    # Parse HF response
    if isinstance(result, list) and len(result) > 0:
        generated = result[0].get("generated_text", "")
    elif isinstance(result, dict):
        generated = result.get("generated_text", "")
    else:
        generated = str(result)
    
    # Clean up the response
    generated = generated.strip()
    
    completion_id = f"chatcmpl-{uuid.uuid4().hex[:29]}"
    
    # Estimate token counts (rough approximation)
    prompt_tokens = len(prompt) // 4
    completion_tokens = len(generated) // 4
    
    return ChatCompletionResponse(
        id=completion_id,
        created=int(time.time()),
        model=model,
        choices=[
            ChatCompletionChoice(
                index=0,
                message=Message(role="assistant", content=generated),
                finish_reason="stop"
            )
        ],
        usage={
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": prompt_tokens + completion_tokens
        }
    )

@app.get("/")
async def root():
    return {
        "name": "LionAI Hugging Face Space API",
        "version": "1.0.0",
        "description": "OpenAI-compatible API for Hugging Face models",
        "endpoints": {
            "chat": "/v1/chat/completions",
            "models": "/v1/models",
            "health": "/health"
        },
        "documentation": "https://github.com/starmaen/LionAI"
    }

if __name__ == "__main__":
    port = int(os.getenv("PORT", "7860"))
    uvicorn.run(app, host="0.0.0.0", port=port)