# LionAI Hugging Face Space API

OpenAI-compatible API proxy for Hugging Face Inference API, designed to work with the [LionAI Android App](https://github.com/starmaen/LionAI).

## Features

- **OpenAI-compatible API** — Drop-in replacement for OpenAI API
- **Free Hugging Face Models** — Access 20+ curated models (Llama, Mistral, Qwen, Nemotron, DeepSeek, etc.)
- **Streaming Support** — Real-time token streaming via Server-Sent Events
- **No API Key Required** — Works with public HF Inference API (rate limited)
- **Optional Auth** — Set `HF_SPACE_API_KEY` to protect your space
- **CORS Enabled** — Works from any origin (mobile apps, web, etc.)

## Quick Start

### 1. Create your Space

1. Go to [https://huggingface.co/new-space](https://huggingface.co/new-space)
2. Choose **Docker** as the SDK
3. Set visibility to **Public** (or Private if you prefer)
4. Create the space

### 2. Configure Secrets

In your Space settings → **Repository secrets**, add:

| Secret | Required | Description |
|--------|----------|-------------|
| `HF_TOKEN` | No | Your Hugging Face token (higher rate limits, access to gated models) |
| `HF_SPACE_API_KEY` | No | Optional API key to protect your endpoint |
| `DEFAULT_MODEL` | No | Default model (default: `meta-llama/Llama-3.3-70B-Instruct`) |
| `MAX_TOKENS` | No | Max tokens per request (default: `4096`) |
| `TEMPERATURE` | No | Default temperature (default: `0.7`) |
| `TOP_P` | No | Default top_p (default: `0.95`) |

### 3. Deploy

Push the files in this directory to your Space repository:

```bash
cd hf-space
git init
git remote add origin https://huggingface.co/spaces/YOUR_USERNAME/YOUR_SPACE_NAME
git add .
git commit -m "Initial commit"
git push origin main
```

Or use the Hugging Face web UI to upload files.

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check |
| `/v1/models` | GET | List available models |
| `/v1/chat/completions` | POST | Chat completions (OpenAI compatible) |

## Usage with LionAI App

1. Open LionAI app → Settings (⚙️)
2. Select **"مخصص (OpenAI-Compatible)"** as provider
3. Set **Base URL** to: `https://YOUR_USERNAME-YOUR_SPACE_NAME.hf.space/v1`
4. If you set `HF_SPACE_API_KEY`, enter it as **API Key**
5. Tap **تحديث** to fetch models
6. Select a model and start chatting!

## Example Request

```bash
curl -X POST https://YOUR_SPACE.hf.space/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_HF_SPACE_API_KEY" \
  -d '{
    "model": "meta-llama/Llama-3.3-70B-Instruct",
    "messages": [
      {"role": "user", "content": "Hello!"}
    ],
    "stream": true
  }'
```

## Available Models

The space includes 20+ curated models:

- **Llama**: `meta-llama/Llama-3.3-70B-Instruct`, `meta-llama/Llama-3.1-70B-Instruct`, `meta-llama/Llama-3.1-8B-Instruct`
- **Mistral**: `mistralai/Mistral-7B-Instruct-v0.3`, `mistralai/Mixtral-8x7B-Instruct-v0.1`
- **Qwen**: `Qwen/Qwen2.5-72B-Instruct`, `Qwen/Qwen2.5-32B-Instruct`, `Qwen/Qwen2.5-14B-Instruct`, `Qwen/Qwen2.5-7B-Instruct`
- **Gemma**: `google/gemma-2-27b-it`, `google/gemma-2-9b-it`
- **Nemotron**: `nvidia/Nemotron-3-Ultra`, `nvidia/Nemotron-3-Super`
- **Command R**: `cohere/command-r-plus`, `cohere/command-r`
- **DeepSeek**: `deepseek-ai/DeepSeek-V3`, `deepseek-ai/DeepSeek-R1`, `deepseek-ai/DeepSeek-Coder-V2-Instruct`
- **Yi**: `01-ai/Yi-1.5-34B-Chat`, `01-ai/Yi-1.5-9B-Chat`
- **Phi**: `microsoft/Phi-3.5-mini-instruct`, `microsoft/Phi-3.5-MoE-instruct`

> **Note**: You can use ANY model on Hugging Face Hub by specifying its ID. The curated list is just for the `/v1/models` endpoint.

## Rate Limits

- **Without HF_TOKEN**: ~30 requests/minute, shared quota
- **With HF_TOKEN**: Higher limits, access to gated models
- **Model loading**: First request to a cold model may take 30-60s (returns 503, auto-retries)

## Local Development

```bash
cd hf-space
pip install -r requirements.txt
cp .env.example .env  # Edit with your values
python app.py
```

Server runs on `http://localhost:7860`

## Docker

```bash
docker build -t lionai-hf-space .
docker run -p 7860:7860 --env-file .env lionai-hf-space
```

## License

MIT License — See [LICENSE](../LICENSE)

---

**Built for [LionAI](https://github.com/starmaen/LionAI) — Your AI Assistant on Android**