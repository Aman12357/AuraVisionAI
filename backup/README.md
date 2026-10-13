# AuraVision AI: free laptop backup

The backup feature is merged into `main`. The Render endpoint remains disabled until you configure a strong `BACKUP_API_TOKEN` in the **AuraVisionAI-Docker** service.

## What is backed up

- Account email and creation time, plus a one-way SHA-256 owner reference for grouping related records. Raw owner tokens, password hashes, and browser sessions are never exported.
- Generation prompts and metadata, plus generated media files still available on the server filesystem.
- Allow-listed payment and subscription metadata. Card numbers, CVV, API secrets, Razorpay order/payment/subscription identifiers, and webhook secrets are not exported.
- The laptop keeps an SQLite metadata archive and a media folder under `%LOCALAPPDATA%\AuraVisionAI\backup`.

The laptop archive is append/upsert oriented: records removed from the cloud are not automatically deleted locally. This is an archive, not a live writable replica or a tested full disaster-recovery restore.

## One-time setup

1. In Render, open the **AuraVisionAI-Docker** service and go to **Environment**.
2. On your Windows laptop, open PowerShell and generate a secret locally:
   ```powershell
   py -c "import secrets; print(secrets.token_urlsafe(48))"
   ```
   Copy the generated value temporarily. Do not send it in chat, put it in GitHub, or save it in a source file.
3. Add an environment variable named `BACKUP_API_TOKEN` in Render with that exact value, save the change, and let Render redeploy. Keep the same value for the laptop setup below.
4. Download/clone the repository's current `main` branch to your Windows laptop if you do not already have it. In PowerShell, change to the repository folder.
5. Run:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\backup\install-startup.ps1
   ```
6. When prompted, enter `https://auravisionai-docker.onrender.com` and paste the same token at the hidden token prompt. The token is stored in Windows Credential Manager, not the config file.
7. Run a manual first sync:
   ```powershell
   py .\backup\sync_auravision.py --once
   ```
   Check for “AuraVision backup synced.” and inspect `%LOCALAPPDATA%\AuraVisionAI\backup`. Then check Task Scheduler for `AuraVisionAI-Laptop-Backup`. It runs at Windows sign-in and syncs every 15 minutes while the laptop is on.

## Security and limitations

- The endpoint is HTTPS-only and requires a bearer token; it returns only explicit allow-listed metadata.
- The client refuses non-HTTPS and cross-host redirects, validates archive member names, and never calls `extractall`.
- Treat the local archive as private. Enable Windows Device Encryption/BitLocker where available, use a strong Windows sign-in, and keep a separate backup of `%LOCALAPPDATA%\AuraVisionAI\backup`.
- If the laptop is off, asleep, disconnected, or the free cloud app is asleep/unavailable, sync cannot run until both are available again.
- Render's free filesystem is ephemeral. Media already removed before a successful sync cannot be recovered.
- This does not migrate the cloud database into a running local database, restore accounts into a replacement server, or sync active sessions.
- No paid resources are required or created by this feature.
