# AuraVision AI: Spring Boot backend

Landing page (`/`) -> "Try AuraVision AI" opens the Studio (`/studio.html`) in a new window.
The Studio creates photos and videos through the Google Gemini API.

## Run
1. Get a key at https://aistudio.google.com/apikey (video generation needs a billing-enabled key)
2. Install Java 17+ and Maven, then:

    export GEMINI_API_KEY=your_key        # Windows: set GEMINI_API_KEY=your_key
    mvn spring-boot:run

3. Open http://localhost:8080

## Database
Default: embedded H2 file in ./data (no setup). For PostgreSQL:

    createdb auravision
    export DB_URL=jdbc:postgresql://localhost:5432/auravision DB_USER=postgres DB_PASSWORD=...
    mvn spring-boot:run -Dspring-boot.run.profiles=postgres

Tables are created automatically. Generated files are saved in ./storage.

## How the frontend and backend connect
- `/` landing page -> "Try AuraVision AI" opens `/studio.html` in a new tab. Both are served by Spring from `static/`.
- Every browser gets an anonymous HttpOnly cookie (`av_token`). Galleries, purchases and quotas are tied to it.
- Checkout calls the backend: it creates an order (price computed on the server), then confirms it, which saves a
  `payments` row and activates a `subscriptions` row. Plan prices and limits live in `model/Plan.java`.
- The Studio header reads the plan and remaining daily quota from `/api/me`. Free: 5 photos/day, no video.
  Plus 30/3, Pro 100/10, Ultra 500/50 (resets at midnight UTC).
- Payments are a DEMO: `BillingService.pay` marks the order paid. Before real use, verify your gateway's
  payment signature there (Razorpay, Stripe, etc.).

## API (all under the visitor cookie)
GET    /api/me                          plan + photos/videos left today
GET    /api/checkout/plans              prices and limits
POST   /api/checkout/orders             {"plan":"PRO","yearly":false,"email":"you@example.com"} -> order
POST   /api/checkout/orders/{id}/pay    {"method":"card"} -> activates the plan
POST   /api/generations                 {"type":"IMAGE"|"VIDEO","prompt":"...","aspectRatio":"16:9"}  -> 202 + job
GET    /api/generations                 your latest 30
GET    /api/generations/{id}            poll: PENDING | RUNNING | DONE | FAILED
GET    /api/generations/{id}/media      the file (add ?download=true)
DELETE /api/generations/{id}

## Models (override with env vars)
GEMINI_IMAGE_MODEL=gemini-2.5-flash-image
GEMINI_VIDEO_MODEL=veo-3.1-generate-preview

## Deploy on Render
This repository is a **Java 17 / Maven Spring Boot application**, not a Node.js app. Deploy it as a **Docker** web service so Render uses the root `Dockerfile`.

1. Create a new Render Web Service and connect `Aman12357/AuraVisionAI` (branch `main`, or this fix branch while testing).
2. Select **Docker** as the runtime. Leave **Root Directory** blank; the `Dockerfile` is at the repository root. Do not use `npm start`, `npm install`, or set the root directory to `server`.
3. Add `GEMINI_API_KEY` under Environment with your Google AI Studio API key. Never commit the key to GitHub.
4. Deploy. The Dockerfile builds with Maven and starts Spring Boot on Render's `PORT`.
5. Open the deployed root URL, then open `/studio.html` and try a short image prompt. If it fails, inspect the generation card and Render logs for the underlying Gemini API error.

Render's local filesystem may not survive restarts or redeploys. Configure persistent storage / a managed database before relying on saved generations, user history, or payments in production. Checkout is currently demo-only and is not safe for real payments.
