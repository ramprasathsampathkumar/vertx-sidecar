# LLM Sidecar for Vert.x

A lightweight proxy that sits beside your Vert.x Lambda function and intercepts every outbound LLM call — so engineering teams focus on their core logic while the sidecar handles the rest.

## Why this exists

Enterprise teams building LLM features independently hit the same wall: each team manages its own API keys, writes its own logging, handles its own rate limits, and implements its own safety checks. None of that is differentiated work.

The sidecar moves those concerns out of application code and into infrastructure:

- **One env var change** to adopt — point your SDK base URL at `http://localhost:8080/openai` instead of OpenAI directly. Nothing else changes.
- **Keys never touch application code** — the sidecar reads API keys from env vars and injects them.
- **Streaming works transparently** — SSE chunks are piped through without buffering.
- **Grows with you** — starts as a co-located proxy, evolves into a shared LLM gateway.

See [`architecture.md`](architecture.md) for diagrams and [`build_plan.md`](build_plan.md) for the full phase roadmap.

---

## Quick start

```bash
./start.sh
```

Then open **http://localhost:7860** for the chat UI.

---

## Manual start

### Prerequisites

- Docker Desktop running
- An OpenAI API key (Anthropic optional)

### Steps

**1. Set your API keys**

```bash
cp .env.example .env
# edit .env and fill in:
#   OPENAI_API_KEY=sk-...
#   ANTHROPIC_API_KEY=sk-ant-...  (optional)
```

**2. Start the stack**

```bash
docker compose up --build
```

This starts two services:

| Service | URL | What it does |
|---|---|---|
| `llm-sidecar` | http://localhost:8080 | Vert.x proxy — intercepts LLM calls |
| `gradio-ui` | http://localhost:7860 | Chat UI + request inspector |

**3. Verify the sidecar is up**

```bash
curl http://localhost:8080/health
# {"status":"up","providers":["openai","anthropic"]}
```

**4. Open the UI**

Go to **http://localhost:7860**, type a message, and send. The Inspector panel on the right shows the exact request JSON sent to OpenAI, the response, latency, and token usage.

**5. Stop**

```bash
docker compose down
```

---

## Calling the sidecar from your own app

Change only the base URL in your SDK. No other code changes needed.

**Python**
```python
from openai import OpenAI

client = OpenAI(
    api_key="ignored",                         # sidecar injects the real key
    base_url="http://localhost:8080/openai/v1"
)
response = client.chat.completions.create(
    model="gpt-4o-mini",
    messages=[{"role": "user", "content": "hello"}]
)
```

**Java (LangChain4j)**
```java
OpenAiChatModel model = OpenAiChatModel.builder()
    .apiKey("ignored")
    .baseUrl("http://localhost:8080/openai/v1")
    .modelName("gpt-4o-mini")
    .build();
```

**curl**
```bash
curl http://localhost:8080/openai/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}]}'
```

---

## Running without Docker (sidecar only)

Requires Java 21 and Maven 3.8+.

```bash
# Build the fat jar
mvn package -DskipTests

# Run with your keys
OPENAI_API_KEY=sk-... \
ANTHROPIC_API_KEY=sk-ant-... \
java -jar target/llm-sidecar-1.0.0-SNAPSHOT-fat.jar
```

To also run the Gradio UI locally (requires Python 3.11+):

```bash
cd gradio-ui
pip install -r requirements.txt
SIDECAR_URL=http://localhost:8080 python app.py
# UI available at http://localhost:7860
```

---

## Project structure

```
vertx-sidecar/
├── src/main/java/com/llmsidecar/
│   ├── MainVerticle.java               # startup, config loading
│   ├── config/                         # SidecarConfig, ProviderConfig
│   ├── provider/ProviderRegistry.java  # resolves path → provider
│   ├── middleware/                     # AuthInjectionMiddleware (+ more in Phase 3)
│   └── proxy/                          # ProxyRouter, ProxyHandler
├── gradio-ui/
│   └── app.py                          # chat UI + inspector (grows per phase)
├── config/sidecar.yaml                 # provider definitions
├── Dockerfile                          # multi-stage Java build
├── docker-compose.yml                  # sidecar + gradio-ui
├── build_plan.md                       # Phase 1–5 roadmap
└── architecture.md                     # Mermaid diagrams
```

---

## Phases

| Phase | Status | Adds |
|---|---|---|
| 1 — Proxy Foundation | ✅ Done | Transparent proxy, auth injection, streaming, Gradio chat UI |
| 2 — Observability | Planned | Token tracking, cost estimates, latency metrics, Prometheus |
| 3 — Safety | Planned | PII redaction, prompt injection detection, content policy |
| 4 — Control Plane | Planned | Rate limiting, semantic caching, model routing, fallbacks |
| 5 — Gateway Evolution | Planned | Shared gateway, key vault, multi-team config, admin API |
