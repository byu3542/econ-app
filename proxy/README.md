# Anthropic API Proxy (Cloudflare Worker)

## Why this exists

Previously the app called `https://api.anthropic.com/v1/messages` directly with the
Anthropic API key in the APK. Anyone who decompiled the APK could extract the key and
run up charges on your account. This tiny proxy holds the key **server-side** instead:

```
App  ──POST /v1/messages──▶  Cloudflare Worker (adds x-api-key)  ──▶  Anthropic
```

The Android app no longer contains the Anthropic key anywhere.

## Deploy (one-time, ~10 minutes)

1. Create a free Cloudflare account at https://dash.cloudflare.com (the free tier
   includes 100,000 requests/day — far more than this app needs).
2. Install Wrangler (Cloudflare's CLI): `npm install -g wrangler`
3. Log in: `wrangler login`
4. From this `proxy/` folder, deploy: `wrangler deploy`
5. Set the secret key (paste your Anthropic key when prompted):
   `wrangler secret put ANTHROPIC_API_KEY`
6. Recommended — set a shared app token (any long random string):
   `wrangler secret put APP_TOKEN`
7. Wrangler prints your worker URL, e.g. `https://econ-dashboard-anthropic-proxy.YOURNAME.workers.dev`

## Point the app at the proxy

In `local.properties` (project root, git-ignored), add:

```
PROXY_BASE_URL=https://econ-dashboard-anthropic-proxy.YOURNAME.workers.dev
PROXY_APP_TOKEN=the-same-random-string-you-set-as-APP_TOKEN
```

Rebuild the app. The AI Analyst now routes through the proxy. If `PROXY_BASE_URL`
is left empty, the AI Analyst shows a setup message instead of calling anything.

## Notes

- `APP_TOKEN` is a light abuse deterrent, not bulletproof (it still ships in the
  APK). The important win is that the *Anthropic* key — the one tied to your
  billing — never leaves the server. You can rotate `APP_TOKEN` anytime without
  touching your Anthropic account.
- For hard rate limiting, enable Cloudflare's built-in rate limiting rules on the
  worker route (dashboard → Security → WAF → Rate limiting rules).
- After confirming the proxy works, ROTATE your Anthropic key at
  https://console.anthropic.com (the old one shipped in previous APK builds and
  in git history, so treat it as compromised).
