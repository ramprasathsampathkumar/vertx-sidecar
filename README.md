# LLM Sidecar for Vert.x

Enterprise teams building LLM features independently hit the same wall: each team manages its own API keys, writes its own logging, handles its own rate limits, and implements its own safety checks. None of that is differentiated work — and none of it belongs in application code.

The sidecar pattern moves those concerns into infrastructure. Every outbound LLM call from your Vert.x Lambda passes through the sidecar, which handles auth, observability, safety, and cost control transparently. Application teams own business logic. The sidecar owns everything else.

---

## Why Vert.x

This is a deliberate choice: the sidecar is built in the same ecosystem it serves.

In a Java shop running Vert.x Lambdas, introducing a Python sidecar means a second language, a second build pipeline, and a second on-call skill set. The Vert.x sidecar deploys like any other service in the stack — same JVM tooling, same ops playbook, same monitoring.

Beyond the organizational fit, Vert.x is technically well-suited for a transparent proxy:

- **Non-blocking event loop (Netty-backed)** — handles high-concurrency I/O without a thread-per-request model. At gateway scale this matters.
- **Streaming is a first-class concern** — SSE chunks are piped through without buffering; TTFT is captured without breaking the stream.
- **Sub-5ms overhead at p99** — the event loop adds negligible latency on the hot path.
- **Circuit breaker and fallback chains** built into the Vert.x ecosystem natively (Phase 4).

The tradeoff is real: Python has the richer AI library ecosystem (PII redaction, semantic caching, embeddings). For those phases we'll evaluate. But the proxy foundation and the Java teams it serves are a natural fit.

---

## Adoption

One env var change. Nothing else.

```bash
# Before
OPENAI_BASE_URL=https://api.openai.com

# After
OPENAI_BASE_URL=http://llm-sidecar:8080/openai
```

Your SDK, your prompt logic, your error handling — unchanged. The sidecar reads API keys from the environment and injects them. Keys never touch application code.

**Java (LangChain4j)**
```java
OpenAiChatModel model = OpenAiChatModel.builder()
    .apiKey("ignored")                          // sidecar injects the real key
    .baseUrl(System.getenv("SIDECAR_URL") + "/openai/v1")
    .modelName("gpt-4o-mini")
    .build();
```

**Python**
```python
client = OpenAI(
    api_key="ignored",
    base_url=os.environ["SIDECAR_URL"] + "/openai/v1"
)
```

---

## Trajectory

The sidecar starts co-located with a single Lambda. As adoption grows, the same codebase, config schema, and middleware pipeline promote to a shared gateway — without touching any application code.

```
Phase 1–4: Lambda A → [Sidecar]
           Lambda B → [Sidecar]       (per-team, co-located)
           Lambda C → [Sidecar]

Phase 5:   Lambda A ──┐
           Lambda B ──┼──▶ [Central LLM Gateway]
           Lambda C ──┘
```

The gateway adds centralized key management, cross-team budget enforcement, a unified audit trail, and a management API. Application teams notice nothing.

---

## Quick start

```bash
./start.sh
```

Open **http://localhost:7860** — the Gradio UI lets you send messages through the sidecar and inspect every request: latency, token usage, cost, and the raw JSON in and out.

See [`architecture.md`](architecture.md) for diagrams and [`build_plan.md`](build_plan.md) for the full phase roadmap.

---

## Phases

| Phase | Status | What it adds |
|---|---|---|
| 1 — Proxy Foundation | ✅ Done | Transparent proxy, auth injection, streaming passthrough |
| 2 — Observability | ✅ Done | Token tracking, cost estimates, latency + TTFT, Prometheus `/metrics` |
| 3 — Safety | Planned | PII redaction, prompt injection detection, output content policy |
| 4 — Control Plane | Planned | Rate limiting, budget enforcement, semantic caching, model routing, fallbacks |
| 5 — Gateway Evolution | Planned | Shared gateway, key vault, multi-team config, admin API |

---

## What each role owns

| Role | Owns |
|---|---|
| Application team | Business logic, prompt design, model selection |
| Sidecar / Gateway | Auth, observability, safety, rate limiting, caching, routing |
| Platform team | Sidecar deployment, config policy, provider contracts, dashboards |
