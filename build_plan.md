# LLM Sidecar — Build Plan

## Overview

A co-located Vert.x service that intercepts all outbound LLM calls from a Lambda function.
Teams point their SDK base URL at `http://localhost:8080/{provider}` and get observability,
safety, and control for free. Evolves into a centralized LLM gateway at Phase 5.

---

## Phase 1 — Proxy Foundation ✅ (current)

**Goal**: Zero-friction adoption. One env var change, full transparency.

| Deliverable | Details |
|---|---|
| HTTP proxy server | Vert.x router that mirrors OpenAI and Anthropic API contracts |
| Streaming support | SSE / chunked responses piped through without buffering |
| Secret injection | API keys read from env vars; teams never handle keys directly |
| Provider routing | `/openai/*` → `api.openai.com`, `/anthropic/*` → `api.anthropic.com` |
| Health endpoint | `GET /health` for Docker/orchestrator liveness checks |
| Docker packaging | Multi-stage Dockerfile + Docker Compose for local dev |

**Adoption cost for teams**: change `OPENAI_BASE_URL` from `https://api.openai.com` to `http://localhost:8080/openai`. Nothing else.

---

## Phase 2 — Observability

**Goal**: Full visibility across every LLM call, zero instrumentation burden on teams.

| Deliverable | Details |
|---|---|
| Token tracking | Input/output token counts per request, aggregated by team and model |
| Latency metrics | Total latency + TTFT (time-to-first-token) for streaming calls |
| Cost estimation | Real-time dollar-cost estimates based on token counts and model pricing |
| Error rates | 4xx/5xx rates per provider and model |
| Structured logging | JSON logs with `traceId`, `team`, `model`, `tokens`, `latencyMs` |
| OpenTelemetry | Trace propagation — `traceparent` injected into upstream LLM calls |
| Prometheus scrape | `/metrics` endpoint; pre-built Grafana dashboard included |

---

## Phase 3 — Safety & Guardrails

**Goal**: Centrally enforce policies so individual teams don't have to.

| Deliverable | Details |
|---|---|
| PII detection | Regex + optional classifier; redact emails, phones, SSNs from prompts |
| Prompt injection detection | Pattern matching + anomaly scoring on incoming prompt content |
| Output content policy | Configurable block / warn / audit rules on model responses |
| Secrets scanning | Prevent API keys or tokens from leaking into prompts |
| Per-team policy config | YAML overrides; teams opt into stricter or looser policies |
| Audit trail | Immutable log of all policy actions (block, redact, warn) |

---

## Phase 4 — Control Plane

**Goal**: Cost control, reliability, and intelligent routing without app-level changes.

| Deliverable | Details |
|---|---|
| Rate limiting | Per-team and per-model token-per-minute / requests-per-minute budgets |
| Budget enforcement | Hard stop when monthly spend limit is reached; configurable fallback |
| Semantic caching | Cache responses for semantically similar prompts via Redis + embeddings |
| Model routing | Route by cost tier, prompt complexity, or A/B experiment config |
| Fallback chains | Auto-retry with a cheaper or different model on failure or budget exhaustion |
| Circuit breaker | Vert.x native; prevents cascade failures when a provider is degraded |

---

## Phase 5 — Gateway Evolution

**Goal**: The sidecar graduates from per-Lambda to shared infrastructure.

| Deliverable | Details |
|---|---|
| Centralized deployment | Single shared gateway replaces per-Lambda sidecars |
| Admin REST API | Runtime config changes without redeploy |
| Multi-team config | Isolated namespaces, per-team dashboards and budgets |
| Key vault integration | AWS Secrets Manager / HashiCorp Vault for API key rotation |
| Audit & compliance | Full request/response archive with retention policy |
| Provider abstraction | Unified request model; swap OpenAI ↔ Anthropic ↔ Bedrock in config |

---

## Principles

- **Fail-open**: if the sidecar crashes, calls fall through to the provider directly (configurable).
- **Non-blocking throughout**: Vert.x event loop; sidecar adds < 5 ms overhead at p99.
- **No SDK lock-in**: any HTTP client (LangChain4j, Spring AI, raw HttpClient) works unchanged.
- **Config over code**: middleware behavior driven by YAML; no redeployment for policy changes.
