# Configure Cloudflare FLUX.1 Schnell image generation

Aura Vision AI now defaults to Cloudflare Workers AI's `@cf/black-forest-labs/flux-1-schnell` for image generation. Gemini 3.8 Flash remains the text-only prompt-enhancement model.

Cloudflare currently includes a daily free Workers AI allocation (10,000 Neurons per day, subject to account/model eligibility and Cloudflare's current terms). Requests can fail after the daily allowance is exhausted. See the [official pricing page](https://developers.cloudflare.com/workers-ai/platform/pricing/) and [FLUX.1 Schnell model docs](https://developers.cloudflare.com/workers-ai/models/flux-1-schnell/).

## 1. Create a Cloudflare API token

1. Sign in or create a Cloudflare account at https://dash.cloudflare.com/.
2. Open **Manage Account > Account API tokens** (the menu wording may vary).
3. Create a custom API token with Workers AI permissions sufficient to run AI models. Cloudflare's guide may show the permissions as **Workers AI Read** and **Workers AI Edit**.
4. Copy the token once and keep it private. Never commit it to GitHub or paste it into a public issue/chat.

Official REST API guide: https://developers.cloudflare.com/workers-ai/get-started/rest-api/

## 2. Find the Cloudflare Account ID

In the Cloudflare dashboard, open **Workers & Pages** or the account overview and copy the **Account ID** for the account where Workers AI will run.

## 3. Add both values to Render

Open the [AuraVisionAI Render service](https://dashboard.render.com/web/srv-db5krqo473hc73bhfhmg), then open **Environment** and add:

- `CLOUDFLARE_ACCOUNT_ID` = your Cloudflare Account ID
- `CLOUDFLARE_API_TOKEN` = the private token you created

Do not overwrite or remove the existing `GEMINI_API_KEY`; Aura Vision AI still uses it for Gemini 3.8 prompt enhancement and optional Gemini features.

Save the environment changes and let Render redeploy.

## 4. Test

1. Open https://auravisionai-docker.onrender.com/studio.html.
2. Hard-refresh the page with **Ctrl + Shift + R**.
3. Confirm **Cloudflare FLUX.1 Schnell · Free daily allowance** is selected.
4. Generate a simple prompt such as `a red bicycle on a quiet country road at sunrise`.

If the request fails, check Render logs. A missing Cloudflare configuration will say which environment variables to add. HTTP 401/403 usually means the account ID, token, or token permissions are incorrect. HTTP 429 can mean the rate limit or free daily allocation has been reached.

The Cloudflare token stays on the backend and must never be added to `studio.html`.
