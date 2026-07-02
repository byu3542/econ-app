/**
 * Cloudflare Worker — Anthropic API proxy for the Economic Dashboard app.
 *
 * Purpose: keep the Anthropic API key OFF the Android device. The app sends
 * its chat payload here; this worker attaches the secret key server-side and
 * forwards the request to Anthropic.
 *
 * Secrets (set via `wrangler secret put <NAME>` — never commit these):
 *   ANTHROPIC_API_KEY  — required. Your Anthropic key.
 *   APP_TOKEN          — optional. Shared secret; if set, requests must send
 *                        the same value in the "x-app-token" header.
 */

const ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
const ANTHROPIC_VERSION = "2023-06-01";

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

    let body;
    try {
      body = await request.text();
      JSON.parse(body); // validate it's JSON before forwarding
    } catch (e) {
      return new Response(JSON.stringify({ error: "invalid JSON body" }), {
        status: 400, headers: { "content-type": "application/json" },
      });
    }

    const upstream = await fetch(ANTHROPIC_URL, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": env.ANTHROPIC_API_KEY,
        "anthropic-version": ANTHROPIC_VERSION,
      },
      body,
    });

    // Pass Anthropic's response straight back to the app.
    return new Response(upstream.body, {
      status: upstream.status,
      headers: { "content-type": "application/json" },
    });
  },
};
