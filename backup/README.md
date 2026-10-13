# AuraVision AI: free laptop backup

This feature creates a local, incremental archive while your Windows laptop is on and connected. It is deliberately separate from the production branch until reviewed and configured.

## What is backed up

- Account email, creation time, and internal owner ID. Password hashes and browser sessions are never exported.
- Generation prompts and metadata, plus generated media files still available on the server filesystem.
- Allow-listed payment and subscription metadata. Card numbers, CVV, API secrets, Razorpay payment identifiers, and webhook secrets are not exported.
- The laptop keeps an SQLite metadata archive and a media folder under %LOCALAPPDATA%\AuraVisionAI\backup.

The laptop archive is append/upsert oriented: records removed from the cloud are not automatically deleted locally. This helps preserve history. It is a backup, not a live writable replica and not a full disaster-recovery restore system.

## Free setup

1. Keep this branch unmerged until code review and tests are complete.
2. Generate a random token of at least 32 characters with:
   py -c "import secrets; print(secrets.token_urlsafe(48))"
3. In the Render service's Environment page, add BACKUP_API_TOKEN with that value. Do not put it in GitHub, source files, screenshots, or chat.
4. Deploy this branch only after reviewing it and confirming Render is configured to deploy the intended branch. The currently live service is not changed by this feature branch commit alone.
5. On Windows, from the repository folder, run:
   powershell -ExecutionPolicy Bypass -File .\backup\install-startup.ps1
6. Enter the backend base URL (for example https://auravisionai-docker.onrender.com) and the token when prompted. The token is stored using Windows Credential Manager via Python keyring, not in the config file.
7. The task starts at Windows sign-in and syncs every 15 minutes. Check Task Scheduler for AuraVisionAI-Laptop-Backup; manual test:
   py .\backup\sync_auravision.py --once

## Security and limitations

- The endpoint is HTTPS-only and requires a bearer token; it returns only explicit allow-listed metadata.
- The client refuses non-HTTPS and cross-host redirects, validates archive member names, and never calls extractall.
- Treat the local archive as private. Enable Windows Device Encryption/BitLocker where available, use a strong Windows sign-in, and keep a separate backup of %LOCALAPPDATA%\AuraVisionAI\backup.
- If the laptop is off, asleep, disconnected, or the free cloud app is asleep/unavailable, sync cannot run until both are available again.
- A free Render filesystem is ephemeral. Media already removed by Render cannot be downloaded later; the backup only preserves files that still exist when the sync succeeds.
- This does not migrate H2/PostgreSQL into a running local database, restore accounts into a replacement server, or sync active sessions. It creates a durable local archive. Test restore procedures separately before treating it as disaster recovery.
- The endpoint streams all backup data. Keep the token private and rotate it immediately if exposed. Do not reuse the token for any other service.
- No paid resource is created by these code changes.
