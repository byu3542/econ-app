/**
 * Cloudflare Worker — Anthropic API proxy for the Economic Dashboard app.
 *
 * Purpose: keep the Anthropic API key OFF the Android device. The app sends
 * its chat payload here; this worker attaches the secret key server-side and
 * forwards the request to Anthropic.
 *
 * Supports both regular JSON responses and streaming (SSE) — when the app
 * sends "stream": true, Anthropic's text/event-stream body is piped straight
 * through to the client.
 *
 * Secrets (set via `wrangler secret put <NAME>` — never commit these):
 *   ANTHROPIC_API_KEY  — required. Your Anthropic key.
 *   APP_TOKEN          — optional. Shared secret; if set, requests must send
 *                        the same value in the "x-app-token" header.
 *
 * Hardening (TICKET-G / review F7 — minimum for a single-developer hobby
 * deployment; deliberately not a full multi-tenant gateway):
 *   - model is restricted to MODEL_ALLOWLIST and max_tokens is clamped to
 *     MAX_TOKENS_CEILING before forwarding, so a leaked APP_TOKEN can't be
 *     used to run the most expensive request shape possible.
 *   - an optional Workers Rate Limiting binding (RATE_LIMITER, see
 *     wrangler.toml) throttles the route if configured — the app has exactly
 *     one caller sharing one token, so a single route-wide key is enough.
 *   - a non-2xx upstream response is reshaped into a generic error body so
 *     account/billing-adjacent details in Anthropic's raw error never reach
 *     a client.
 */

const ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
const ANTHROPIC_VERSION = "2023-06-01";

// Keep in sync with analyst/ModelConfig.java's MODEL_FAST/MODEL_STRONG — the
// only two models this app actually calls.
const MODEL_ALLOWLIST = ["claude-haiku-4-5-20251001", "claude-sonnet-5"];
const MAX_TOKENS_CEILING = 4096;

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response(JSON.stringify({ error: "POST only" }), {
        status: 405, headers: { "content-type": "application/json" },
      });
    }

    // Optional shared-secret check so random callers can't burn your quota.
    if (env.APP_TOKEN) {
      const token = request.headers.get("x-app-token");
      if (token !== env.APP_TOKEN) {
        return new Response(JSON.stringify({ error: "unauthorized" }), {
          status: 401, headers: { "content-type": "application/json" },
        });
      }
    }

    // Optional crude per-minute throttle — only blunts abuse of a leaked
    // token, not a real per-install quota system. No-ops if the binding
    // isn't configured (see wrangler.toml).
    if (env.RATE_LIMITER) {
      const { success } = await env.RATE_LIMITER.limit({ key: "econ-dashboard-proxy" });
      if (!success) {
        return new Response(JSON.stringify({ error: "rate limited" }), {
          status: 429, headers: { "content-type": "application/json" },
        });
      }
    }

    let payload;
    try {
      payload = JSON.parse(await request.text());
    } catch (e) {
      return new Response(JSON.stringify({ error: "invalid JSON body" }), {
        status: 400, headers: { "content-type": "application/json" },
      });
    }

    if (!MODEL_ALLOWLIST.includes(payload.model)) {
      return new Response(JSON.stringify({ error: "unsupported model" }), {
        status: 400, headers: { "content-type": "application/json" },
      });
    }
    // Clamp regardless of what the caller asked for — bounds the cost of any
    // single request even if the app-side cap is ever bypassed or the token
    // is used by something other than the app.
    payload.max_tokens = Math.min(Number(payload.max_tokens) || MAX_TOKENS_CEILING, MAX_TOKENS_CEILING);

    const upstream = await fetch(ANTHROPIC_URL, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": env.ANTHROPIC_API_KEY,
        "anthropic-version": ANTHROPIC_VERSION,
      },
      body: JSON.stringify(payload),
    });

    // A non-2xx response never streams — reshape it so Anthropic's raw error
    // body (which can include account/billing-adjacent detail) never reaches
    // a client directly; the app's readApiError() already falls back to a
    // generic "API error <code>." message when no error.message is present,
    // so this degrades gracefully client-side.
    if (!upstream.ok) {
      return new Response(
          JSON.stringify({ error: "upstream error", status: upstream.status }),
          { status: upstream.status, headers: { "content-type": "application/json" } });
    }

    // Pass Anthropic's response straight back to the app, preserving the
    // upstream content type so SSE streams stay streams.
    return new Response(upstream.body, {
      status: upstream.status,
      headers: {
        "content-type": upstream.headers.get("content-type") || "application/json",
      },
    });
  },
};
