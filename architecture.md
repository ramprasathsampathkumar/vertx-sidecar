# LLM Sidecar — Architecture

## Motivation

Enterprise teams building LLM features face the same cross-cutting concerns repeatedly:
**who called what model, at what cost, with what data, and did it comply with policy?**

Without a sidecar, each team solves this independently — inconsistent observability, keys
scattered across services, no shared safety layer, and no ability to enforce org-wide spend
controls. The sidecar moves these concerns out of application code and into infrastructure,
one phase at a time.

---

## Enterprise Overview

Three teams. Three co-located sidecars. One shared observability stack.
No team handles API keys, logging, or cost tracking directly.

```mermaid
graph TB
    subgraph OrderSvc ["Order Service"]
        direction LR
        LA["Vert.x Lambda"] -->|"SIDECAR_URL/openai"| SCA["LLM Sidecar"]
    end

    subgraph SupportBot ["Support Bot"]
        direction LR
        LB["Vert.x Lambda"] -->|"SIDECAR_URL/anthropic"| SCB["LLM Sidecar"]
    end

    subgraph RecEngine ["Recommendation Engine"]
        direction LR
        LC["Vert.x Lambda"] -->|"SIDECAR_URL/openai"| SCC["LLM Sidecar"]
    end

    subgraph Providers ["LLM Providers"]
        OAI["OpenAI"]
        ANT["Anthropic"]
    end

    subgraph Observability ["Platform Observability  (Phase 2)"]
        PROM["Prometheus"]
        GRAF["Grafana\nunified LLM dashboard"]
    end

    SCA & SCB & SCC -->|"authenticated HTTPS\nkeys injected by sidecar"| OAI & ANT
    SCA & SCB & SCC -->|"GET /metrics"| PROM
    PROM --> GRAF
```

---

## Phase 1 — Transparent Proxy ✅

**What it adds:** routing, auth injection, streaming passthrough, health endpoint.

Teams change one env var (`SIDECAR_URL`). The sidecar reads API keys from the
environment and injects them — keys never touch application code.

### Component View

```mermaid
graph LR
    APP(["App\nno API key needed"])

    subgraph SC1 ["LLM Sidecar"]
        direction TB
        ROUTER["Router\n/health · /openai/* · /anthropic/*"]
        AUTH["Auth Injection Middleware\nreads key from env\ninjects Authorization header"]
        PROXY["Proxy Handler\nbuffers request body\npipes response back to caller"]
        HEALTH["GET /health\n→ {status, providers}"]
    end

    LLM(["OpenAI / Anthropic\nauthenticated HTTPS"])

    APP -->|"POST /openai/v1/..."| ROUTER
    ROUTER --> AUTH --> PROXY
    PROXY <-->|"request / response"| LLM
    ROUTER --> HEALTH
```

### Request Flow

```mermaid
sequenceDiagram
    participant App as Vert.x App
    participant SC  as LLM Sidecar
    participant LLM as OpenAI / Anthropic

    App->>SC: POST /openai/v1/chat/completions<br/>(api_key = "ignored")

    Note over SC: Auth Injection Middleware<br/>reads OPENAI_API_KEY from env<br/>injects Authorization: Bearer sk-...

    SC->>LLM: POST /v1/chat/completions<br/>Authorization: Bearer sk-...

    alt Non-streaming
        LLM-->>SC: 200 application/json
        SC-->>App: 200 application/json  (body forwarded)
    else Streaming  (stream: true)
        LLM-->>SC: 200 text/event-stream
        loop SSE chunks
            SC-->>App: chunk forwarded immediately  (zero buffering)
        end
    end
```

---

## Phase 2 — Observability ✅  *(current)*

**What it adds:** token tracking, cost estimation, latency + TTFT metrics,
structured JSON logs, `/metrics` (Prometheus), `/metrics/json` (Gradio UI).

The proxy handler branches on response type to capture metrics without breaking the
streaming pipeline. For SSE, it scans chunks in-flight; for JSON it buffers once to
parse the `usage` field.

### Metrics Capture Pipeline

```mermaid
graph LR
    LLM(["OpenAI / Anthropic"])

    subgraph SC2 ["LLM Sidecar — Phase 2 additions"]
        direction TB

        subgraph PH ["Proxy Handler"]
            BRANCH{"content-type?"}
            SSE["SSE pipe\n· scan chunks for usage\n· TTFT on first content delta"]
            BUF["Buffer response\n· parse usage field\n· extract token counts"]
        end

        STORE["MetricsStore\ntraceId · provider · model\npromptTokens · completionTokens\nlatencyMs · ttftMs · costUsd\n(ring buffer, last 100 requests)"]
        LOG["Structured JSON log\nper request"]

        EP1["GET /metrics\nPrometheus text format\ncounters + gauges + percentiles"]
        EP2["GET /metrics/json\nfor Gradio Metrics tab"]
    end

    PROM(["Prometheus"])
    UI(["Gradio UI\nMetrics tab"])

    LLM -->|"response"| BRANCH
    BRANCH -->|"text/event-stream"| SSE --> STORE
    BRANCH -->|"application/json"| BUF --> STORE
    STORE --> LOG
    STORE --> EP1 --> PROM
    STORE --> EP2 --> UI
```

### Observability Flow

```mermaid
sequenceDiagram
    participant App  as Vert.x App
    participant SC   as LLM Sidecar
    participant LLM  as OpenAI
    participant MS   as MetricsStore
    participant PROM as Prometheus

    App->>SC: POST /openai/v1/chat/completions

    Note over SC: extract model from request body<br/>assign traceId (or forward x-request-id)

    SC->>LLM: POST /v1/chat/completions<br/>x-request-id: {traceId}

    alt Non-streaming
        LLM-->>SC: 200 { ..., usage: { prompt_tokens: 13, completion_tokens: 9 } }
        Note over SC: parse usage · calculate cost
        SC-->>App: 200  (body forwarded)
        SC->>MS: record(model, tokens=22, latencyMs, costUsd)
    else Streaming
        loop content chunks
            LLM-->>SC: data: {"choices":[{"delta":{"content":"..."}}]}
            SC-->>App: chunk forwarded
            Note over SC: first content chunk → TTFT captured
        end
        LLM-->>SC: data: {"choices":[], "usage":{"prompt_tokens":13,...}}
        SC-->>App: usage chunk forwarded
        SC->>MS: record(model, tokens=22, latencyMs, ttftMs, costUsd)
    end

    Note over SC: emit JSON log  {event, traceId, model, tokens, latencyMs, costUsd}

    PROM->>SC: GET /metrics  (scrape interval)
    SC-->>PROM: llm_requests_total{model="gpt-4o-mini"} 42
```

---

## Middleware Pipeline — All Phases

Active phases (✅) are highlighted. Planned phases are greyed out.

```mermaid
graph LR
    IN(["Incoming\nRequest"])

    subgraph PRE ["Pre-processing"]
        direction TB
        MW1["Auth Injection\nPhase 1 ✅"]
        MW2["PII Redaction\nPhase 3"]
        MW3["Rate Limiter\nPhase 4"]
        MW4["Cache Lookup\nPhase 4"]
        MW1 --> MW2 --> MW3 --> MW4
    end

    PROXY(["Upstream\nLLM Call"])

    subgraph POST ["Post-processing"]
        direction TB
        MW5["Output Policy\nPhase 3"]
        MW6["Metrics Capture\nPhase 2 ✅"]
        MW7["Cache Write\nPhase 4"]
        MW8["Structured Log\nPhase 2 ✅"]
        MW5 --> MW6 --> MW7 --> MW8
    end

    OUT(["Outgoing\nResponse"])

    IN --> MW1
    MW4 -->|"cache miss"| PROXY
    MW4 -->|"cache hit"| OUT
    PROXY --> MW5
    MW8 --> OUT

    classDef done fill:#d4edda,stroke:#28a745,color:#155724
    classDef planned fill:#f0f0f0,stroke:#bbb,color:#999,stroke-dasharray:4 2

    class MW1,MW6,MW8 done
    class MW2,MW3,MW4,MW5,MW7 planned
```

---

## Sidecar → Gateway Evolution

As adoption grows, the co-located sidecar can be promoted to a shared gateway without
touching any Lambda application code — the middleware pipeline, config schema, and
provider abstractions remain identical.

```mermaid
graph LR
    subgraph SIDE ["Phases 1–4 — Co-located Sidecar"]
        direction TB
        LA["Lambda A"] --> SCA["Sidecar"]
        LB["Lambda B"] --> SCB["Sidecar"]
        LC["Lambda C"] --> SCC["Sidecar"]
    end

    subgraph GATE ["Phase 5 — Shared Gateway"]
        direction TB
        LD["Lambda A"] --> GW["Central LLM Gateway"]
        LE["Lambda B"] --> GW
        LF["Lambda C"] --> GW
    end

    SCA & SCB & SCC -.->|"same config · no code change"| GW
```

The gateway adds: centralized key vault, cross-team budget enforcement, unified audit
trail, and a management API — without touching any Lambda application code.

---

## What Each Role Owns

| Role | Responsibility |
|---|---|
| **Application team** | Business logic, prompt design, model selection hint |
| **Sidecar / Gateway** | Auth, observability, safety, rate limiting, caching, routing |
| **Platform team** | Sidecar deployment, config policy, provider contracts, dashboards |
