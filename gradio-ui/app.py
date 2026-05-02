import json
import os
import time
from urllib.error import URLError
from urllib.request import urlopen

import gradio as gr
from openai import OpenAI

SIDECAR_URL = os.getenv("SIDECAR_URL", "http://localhost:8080")

OPENAI_MODELS = ["gpt-4o-mini", "gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo"]


def make_client(sidecar_url: str) -> OpenAI:
    return OpenAI(
        api_key="routed-via-sidecar",
        base_url=f"{sidecar_url}/openai/v1",
    )


def check_health(sidecar_url: str) -> str:
    try:
        resp = urlopen(f"{sidecar_url}/health", timeout=3)
        data = json.loads(resp.read())
        providers = ", ".join(data.get("providers", []))
        return f"✅  Online  |  providers: {providers}"
    except URLError as e:
        return f"❌  Unreachable — {e.reason}"
    except Exception as e:
        return f"❌  Error — {e}"


def respond(message, history, model, system_prompt, do_stream, sidecar_url):
    """
    history is a list of {"role": "user"|"assistant", "content": str} dicts (Gradio 5 format).
    Yields (history, request_json, response_json, latency, tokens).
    """
    client = make_client(sidecar_url)

    messages = []
    if system_prompt.strip():
        messages.append({"role": "system", "content": system_prompt})
    for item in history:
        messages.append({"role": item["role"], "content": item["content"]})
    messages.append({"role": "user", "content": message})

    req_payload = {"model": model, "messages": messages, "stream": do_stream}
    req_json = json.dumps(req_payload, indent=2)

    start = time.time()

    def updated(assistant_text):
        return history + [
            {"role": "user", "content": message},
            {"role": "assistant", "content": assistant_text},
        ]

    try:
        if do_stream:
            response_text = ""
            ttft_ms = None

            stream = client.chat.completions.create(
                model=model,
                messages=messages,
                stream=True,
                stream_options={"include_usage": True},
            )

            usage_text = "—"
            for chunk in stream:
                delta = chunk.choices[0].delta.content if chunk.choices else None
                if delta:
                    if ttft_ms is None:
                        ttft_ms = (time.time() - start) * 1000
                    response_text += delta
                    elapsed = f"{(time.time() - start) * 1000:.0f} ms  |  TTFT {ttft_ms:.0f} ms"
                    yield updated(response_text), req_json, "streaming…", elapsed, usage_text

                if getattr(chunk, "usage", None):
                    u = chunk.usage
                    usage_text = (
                        f"prompt {u.prompt_tokens}  +  "
                        f"completion {u.completion_tokens}  =  "
                        f"{u.total_tokens} tokens"
                    )

            elapsed = f"{(time.time() - start) * 1000:.0f} ms  |  TTFT {ttft_ms:.0f} ms"
            yield updated(response_text), req_json, f"stream complete — {len(response_text)} chars", elapsed, usage_text

        else:
            resp = client.chat.completions.create(model=model, messages=messages)
            elapsed = f"{(time.time() - start) * 1000:.0f} ms"
            resp_json = json.dumps(resp.model_dump(), indent=2)
            u = resp.usage
            usage_text = (
                f"prompt {u.prompt_tokens}  +  "
                f"completion {u.completion_tokens}  =  "
                f"{u.total_tokens} tokens"
            )
            yield updated(resp.choices[0].message.content), req_json, resp_json, elapsed, usage_text

    except Exception as e:
        elapsed = f"{(time.time() - start) * 1000:.0f} ms"
        yield updated(f"❌ {e}"), req_json, str(e), elapsed, "—"


# ── UI ────────────────────────────────────────────────────────────────────────

with gr.Blocks(title="LLM Sidecar Monitor") as app:

    gr.Markdown("# LLM Sidecar Monitor")

    with gr.Tabs():

        # ── Phase 1: Chat ─────────────────────────────────────────────────────
        with gr.Tab("💬 Chat"):
            with gr.Row():

                # Left — conversation
                with gr.Column(scale=2):
                    chatbot = gr.Chatbot(
                        height=480,
                        label="Conversation",
                        buttons=["copy", "copy_all"],
                    )
                    with gr.Row():
                        msg_input = gr.Textbox(
                            placeholder="Type a message and press Enter…",
                            show_label=False,
                            scale=5,
                        )
                        send_btn = gr.Button("Send", variant="primary", scale=1)
                    clear_btn = gr.Button("🗑  Clear conversation", size="sm")

                # Right — settings + inspector
                with gr.Column(scale=1):
                    gr.Markdown("### ⚙️ Settings")
                    sidecar_url_input = gr.Textbox(value=SIDECAR_URL, label="Sidecar URL")
                    check_btn = gr.Button("Check health", size="sm")
                    status_box = gr.Textbox(label="Status", interactive=False)

                    model_dd = gr.Dropdown(
                        choices=OPENAI_MODELS, value="gpt-4o-mini", label="Model"
                    )
                    system_prompt_input = gr.Textbox(
                        label="System Prompt",
                        value="You are a helpful assistant.",
                        lines=2,
                    )
                    stream_cb = gr.Checkbox(label="Stream response", value=True)

                    gr.Markdown("### 🔍 Inspector")
                    latency_box = gr.Textbox(label="Latency", interactive=False)
                    tokens_box = gr.Textbox(label="Token Usage", interactive=False)
                    with gr.Accordion("Request JSON", open=False):
                        request_box = gr.Code(language="json", label="")
                    with gr.Accordion("Response JSON", open=False):
                        response_box = gr.Code(language="json", label="")

        # ── Phase 2 placeholder ───────────────────────────────────────────────
        with gr.Tab("📊 Metrics — Phase 2"):
            gr.Markdown("""
### Coming in Phase 2

Once the sidecar emits a `/metrics` Prometheus endpoint, this tab will show:

- Token usage per request — input / output / total, by model and team
- Cost estimates in real-time based on current model pricing
- Latency distribution — p50 / p95 / p99, plus TTFT histogram for streaming calls
- Error rates per provider (4xx / 5xx breakdown)
- Grafana dashboard link for production-grade alerting
            """)

        # ── Phase 3 placeholder ───────────────────────────────────────────────
        with gr.Tab("🛡️ Safety — Phase 3"):
            gr.Markdown("""
### Coming in Phase 3

Once the sidecar's safety middleware is wired in, this tab will show:

- **PII redaction diff** — side-by-side original vs. scrubbed prompt
- **Prompt injection alerts** — flagged requests with confidence score
- **Content policy log** — blocked / warned / audited actions per team
- **Secrets scanner hits** — API keys or tokens caught before they reached the model
            """)

        # ── Phase 4 placeholder ───────────────────────────────────────────────
        with gr.Tab("🎛️ Control — Phase 4"):
            gr.Markdown("""
### Coming in Phase 4

Once the control plane is active, this tab will show:

- **Rate limit gauge** — current vs. budget (tokens/min, requests/min) per team
- **Cache hit / miss ratio** — semantic cache effectiveness over time
- **Routing decisions log** — which model was selected and why
- **Fallback chain status** — last N fallback events with reason and target model
            """)

    # ── Event wiring ──────────────────────────────────────────────────────────

    _outputs = [chatbot, request_box, response_box, latency_box, tokens_box]
    _inputs = [msg_input, chatbot, model_dd, system_prompt_input, stream_cb, sidecar_url_input]

    send_btn.click(respond, inputs=_inputs, outputs=_outputs).then(
        lambda: "", outputs=[msg_input]
    )
    msg_input.submit(respond, inputs=_inputs, outputs=_outputs).then(
        lambda: "", outputs=[msg_input]
    )
    clear_btn.click(
        lambda: ([], "", "", "", ""),
        outputs=[chatbot, request_box, response_box, latency_box, tokens_box],
    )
    check_btn.click(check_health, inputs=[sidecar_url_input], outputs=[status_box])
    app.load(check_health, inputs=[sidecar_url_input], outputs=[status_box])


if __name__ == "__main__":
    app.launch(server_name="0.0.0.0", server_port=7860, theme=gr.themes.Soft())
