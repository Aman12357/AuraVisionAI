# AuraVision AI

AuraVision AI is a Spring Boot web application with a landing page, an AI image studio, user accounts, account-scoped creation history, and recurring subscriptions.

## Run locally

Requirements: Java 17+ and Maven.

\`\`\`bash
# Windows PowerShell
$env:GEMINI_API_KEY="your-Gemini-key"
$env:CLOUDFLARE_ACCOUNT_ID="your-Cloudflare-account-id"
$env:CLOUDFLARE_API_TOKEN="your-Cloudflare-Workers-AI-token"
mvn spring-boot:run
\`\`\`

Open \`http://localhost:8080\`; the studio is at \`/studio.html\` and user accounts are at \`/account.html\`.

The default local profile uses H2 to make development easier. **Checkout is deliberately disabled while the app uses H2**, because a temporary database is not appropriate for real customer payments or subscription state.

## Accounts and private workspaces

- Users register with an email and password. Passwords are stored as salted PBKDF2 hashes, not plaintext.
- Server-side sessions use an HttpOnly cookie and stored SHA-256 token hashes.
- Each account has a stable owner id. Generation endpoints require authentication; subscription, payment-history and generation queries are scoped to that owner.
- The studio keeps local settings separate per account and syncs server-side generation history after sign-in.
- Media is still stored on the app filesystem. Configure persistent storage/object storage before relying on durable downloads across restarts.

## Real recurring subscriptions

The subscription checkout uses Razorpay's hosted checkout. Card numbers and CVV are not accepted or stored by this app.

- Monthly and yearly prices are set by the server in \`model/Plan.java\`.
- The browser callback is signature-checked. **Paid access is not granted by the browser callback alone**; the backend activates a plan only after a signed Razorpay webhook confirms a matching captured charge.
- Subscription charge ids are unique in the payment table so repeated webhook deliveries cannot create duplicate payment records.
- Account owners can see the latest payments and request cancellation at the end of the paid period.

### Required production setup

1. In Render, create a PostgreSQL database for AuraVision AI. Configure the service to use the PostgreSQL profile with \`SPRING_PROFILES_ACTIVE=postgres\`, \`DB_URL\`, \`DB_USER\`, and \`DB_PASSWORD\` from that database's connection details. Live checkout is disabled unless the app is using a \`jdbc:postgresql:\` datasource.
2. Finish Razorpay merchant onboarding/KYC and add your bank settlement account in the Razorpay Dashboard.
3. In Render > Environment set \`RAZORPAY_KEY_ID\`, \`RAZORPAY_KEY_SECRET\`, and \`RAZORPAY_WEBHOOK_SECRET\`. Never commit these values to GitHub or expose the secret values in frontend code.
4. In the Razorpay Dashboard, create a webhook to \`https://auravisionai-docker.onrender.com/api/payments/razorpay/webhook\`. Select \`subscription.charged\`, \`subscription.activated\`, \`subscription.pending\`, \`subscription.halted\`, \`subscription.cancelled\`, \`subscription.completed\`, and \`payment.captured\`. Copy its webhook secret to \`RAZORPAY_WEBHOOK_SECRET\`.
5. Test the complete subscription creation, payment, webhook, renewal-history and cancellation flow in Razorpay Test Mode. Switch to live keys only after that test succeeds.

**Money is settled to your merchant bank account by Razorpay only after the merchant account is approved and its settlement-bank details are configured.** Deploying this repository does not by itself approve a merchant account or send money to a bank.

## Environment variables

- \`GEMINI_API_KEY\`: text prompt enhancement and Gemini image models.
- \`CLOUDFLARE_ACCOUNT_ID\`, \`CLOUDFLARE_API_TOKEN\`: Cloudflare FLUX image generation.
- \`SPRING_PROFILES_ACTIVE=postgres\`, \`DB_URL\`, \`DB_USER\`, \`DB_PASSWORD\`: persistent production database.
- \`RAZORPAY_KEY_ID\`, \`RAZORPAY_KEY_SECRET\`, \`RAZORPAY_WEBHOOK_SECRET\`: recurring subscription payments.

## Useful routes

- \`/\`: landing page
- \`/learn-more\` and \`/learn-more.html\`: product information
- \`/studio.html\`: private AI studio (requires sign-in to generate)
- \`/account.html\`: register, sign in, subscribe, view payment history, and cancel renewal
- \`POST /api/payments/razorpay/webhook\`: verified Razorpay webhook receiver

## Render deployment

This repository is a Java 17 / Maven Spring Boot application. Use the root \`Dockerfile\` and leave Render's Root Directory blank. The Docker build runs Maven and starts the packaged app on Render's \`PORT\`.
