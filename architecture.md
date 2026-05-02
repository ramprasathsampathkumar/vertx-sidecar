# LLM Sidecar — Architecture

## Motivation

Enterprise teams building LLM features face the same cross-cutting concerns repeatedly:
**Who called what model, at what cost, with what data, and did it comply with policy?**

Without a sidecar, each team solves this independently — inconsistent observability, keys
scattered across services, no shared safety layer, and no ability to enforce org-wide spend
controls. The sidecar moves these concerns out of application code and into infrastructure.

---

## Enterprise Context

```mermaid
graph TB
    subgraph "Engineering Teams"
        T1["Order Service\n(Team A)"]
        T2["Support Bot\n(Team B)"]
        T3["Recommendation\n(Team C)"]
    end

    subgraph "Docker: Order Service"
        direction LR
        A1["Vert.x Lambda"]
        SC1["LLM Sidecar\n:8080"]
        A1 -->|"localhost:8080/openai"| SC1
    end

    subgraph "Docker: Support Bot"
        direction LR
        A2["Vert.x Lambda"]
        SC2["LLM Sidecar\n:8080"]
        A2 -->|"localhost:8080/anthropic"| SC2
    end

    subgraph "Docker: Recommendation"
        direction LR
        A3["Vert.x Lambda"]
        SC3["LLM Sidecar\n:8080"]
        A3 -->|"localhost:8080/openai"| SC3
    end

    subgraph "LLM Providers"
        OAI["OpenAI"]
        ANT["Anthropic"]
        BED["AWS Bedrock"]
    end

    subgraph "Platform Observability"
        PROM["Prometheus"]
        GRAF["Grafana\n(unified LLM dashboard)"]
        OTEL["OpenTelemetry\nCollector"]
    end

    T1 --> A1
    T2 --> A2
    T3 --> A3

    SC1 & SC2 & SC3 -->|"authenticated\nHTTPS"| OAI & ANT & BED
    SC1 & SC2 & SC3 -->|"/metrics"| PROM
    PROM --> GRAF
    SC1 & SC2 & SC3 -->|"traces"| OTEL
```

---

## Request Flow (Phase 1)

```mermaid
sequenceDiagram
    participant App as Vert.x Lambda
    participant SC as LLM Sidecar :8080
    participant OAI as OpenAI API

    App->>SC: POST /openai/v1/chat/completions<br/>(no API key needed)

    rect rgb(230, 245, 255)
        Note over SC: Auth Injection Middleware<br/>reads OPENAI_API_KEY from env
    end

    SC->>OAI: POST /v1/chat/completions<br/>Authorization: Bearer sk-...

    alt Non-streaming response
        OAI-->>SC: 200 application/json { ... }
        SC-->>App: 200 application/json { ... }
    else Streaming response (stream: true)
        OAI-->>SC: 200 text/event-stream<br/>data: {"delta": ...}\n\n
        SC-->>App: chunks forwarded as received<br/>(zero buffering)
    end
```

---

## Middleware Pipeline (all phases)

```mermaid
graph LR
    IN["Incoming\nRequest"]

    subgraph "Pre-processing"
        direction TB
        MW1["Auth Injection\n(Phase 1)"]
        MW2["PII Redaction\n(Phase 3)"]
        MW3["Rate Limiter\n(Phase 4)"]
        MW4["Cache Lookup\n(Phase 4)"]
        MW1 --> MW2 --> MW3 --> MW4
    end

    PROXY["Upstream\nLLM Call"]

    subgraph "Post-processing"
        direction TB
        MW5["Output Policy\n(Phase 3)"]
        MW6["Token Counter\n(Phase 2)"]
        MW7["Cache Write\n(Phase 4)"]
        MW8["Metrics Emit\n(Phase 2)"]
        MW5 --> MW6 --> MW7 --> MW8
    end

    OUT["Outgoing\nResponse"]

    IN --> MW1
    MW4 --> PROXY
    PROXY --> MW5
    MW8 --> OUT

    MW4 -->|"cache hit"| OUT
```

---

## Sidecar → Gateway Evolution

As adoption grows, the per-Lambda sidecar can be promoted to a shared gateway. The
middleware pipeline, config schema, and provider abstractions remain identical — only
the deployment topology changes.

```mermaid
graph LR
    subgraph "Phase 1–4: Sidecar per Lambda"
        direction TB
        LA["Lambda A"] --> SCA["Sidecar"]
        LB["Lambda B"] --> SCB["Sidecar"]
        LC["Lambda C"] --> SCC["Sidecar"]
    end

    subgraph "Phase 5: Shared Gateway"
        direction TB
        LD["Lambda A"] --> GW["Central\nLLM Gateway"]
        LE["Lambda B"] --> GW
        LF["Lambda C"] --> GW
    end

    SCA & SCB & SCC -.->|"promote config\nno code change"| GW
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
