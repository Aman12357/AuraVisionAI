#!/usr/bin/env python3
"""Incrementally archive AuraVision AI metadata and generated media on Windows."""
from __future__ import annotations

import json
import os
import re
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

SERVICE_NAME = "AuraVisionAI-Backup"
POLL_SECONDS = 15 * 60
MEDIA_MEMBER = re.compile(r"^media/(\d+)\.(png|jpg|webp|mp4)$")
KINDS = ("users", "generations", "payments", "subscriptions", "razorpayPlans")


def config_path() -> Path:
    base = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "AuraVisionAI" / "backup"
    base.mkdir(parents=True, exist_ok=True)
    return base / "config.json"


def configure() -> None:
    try:
        import keyring
    except ImportError as exc:
        raise SystemExit("Missing dependency. Run: py -m pip install -r backup/requirements.txt") from exc
    url = input("AuraVision backend URL (https://...onrender.com): ").strip().rstrip("/")
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise SystemExit("Use a valid HTTPS URL. HTTP is not accepted.")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise SystemExit("Enter only the base URL, without credentials, query strings, or fragments.")
    import getpass
    token = getpass.getpass("Backup token (will be saved in Windows Credential Manager): ").strip()
    if len(token) < 32:
        raise SystemExit("Token must contain at least 32 characters.")
    config_path().write_text(json.dumps({"base_url": url}, indent=2), encoding="utf-8")
    keyring.set_password(SERVICE_NAME, "backup-token", token)
    print("Configuration saved. The token is stored in Windows Credential Manager, not in this file.")


def load_config() -> tuple[str, str]:
    try:
        import keyring
        config = json.loads(config_path().read_text(encoding="utf-8"))
        token = keyring.get_password(SERVICE_NAME, "backup-token")
    except Exception as exc:
        raise RuntimeError("Backup is not configured. Run: py backup/sync_auravision.py --configure") from exc
    if not token:
        raise RuntimeError("Backup token is missing from Windows Credential Manager.")
    base_url = config.get("base_url", "")
    parsed = urllib.parse.urlparse(base_url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise RuntimeError("Configured URL is invalid; HTTPS is required.")
    return base_url.rstrip("/") + "/api/admin/backup/export", token


class SameHostRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        old = urllib.parse.urlparse(req.full_url)
        new = urllib.parse.urlparse(newurl)
        if new.scheme != "https" or new.hostname != old.hostname:
            raise urllib.error.HTTPError(newurl, code, "Cross-host or non-HTTPS redirect refused", headers, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download_export(url: str, token: str, destination: Path) -> None:
    request = urllib.request.Request(
        url, data=b"", method="POST",
        headers={"Authorization": "Bearer " + token, "Accept": "application/zip",
                 "User-Agent": "AuraVisionAI-Laptop-Backup/1.0"}
    )
    opener = urllib.request.build_opener(SameHostRedirectHandler())
    with opener.open(request, timeout=120) as response, destination.open("wb") as target:
        if response.status != 200:
            raise RuntimeError("Backup endpoint returned HTTP " + str(response.status))
        total = 0
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > 2 * 1024 * 1024 * 1024:
                raise RuntimeError("Export exceeded the 2 GiB safety limit; reduce media archive size.")
            target.write(chunk)


def apply_export(archive: Path, root: Path) -> None:
    database = root / "auravision-backup.sqlite3"
    media_dir = root / "media"
    media_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive, "r") as zf:
        names = zf.namelist()
        if names.count("manifest.json") != 1:
            raise RuntimeError("Export has no unique manifest; refusing to apply it.")
        manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
        if manifest.get("formatVersion") != 1:
            raise RuntimeError("Unsupported backup format version.")
        for name in names:
            if name == "manifest.json":
                continue
            match = MEDIA_MEMBER.fullmatch(name)
            if not match:
                raise RuntimeError("Unexpected archive member; refusing unsafe path.")
            if zf.getinfo(name).file_size > 500 * 1024 * 1024:
                raise RuntimeError("A media file exceeds the 500 MiB safety limit.")

        with sqlite3.connect(database) as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS records (
                    kind TEXT NOT NULL,
                    record_id TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    last_synced_utc TEXT NOT NULL,
                    PRIMARY KEY (kind, record_id)
                )
            """)
            synced = datetime.now(timezone.utc).isoformat()
            with conn:
                for kind in KINDS:
                    rows = manifest.get(kind, [])
                    if not isinstance(rows, list):
                        raise RuntimeError("Invalid manifest collection: " + kind)
                    for row in rows:
                        if not isinstance(row, dict) or row.get("id") is None:
                            continue
                        conn.execute("""
                            INSERT INTO records(kind, record_id, payload_json, last_synced_utc)
                            VALUES (?, ?, ?, ?)
                            ON CONFLICT(kind, record_id) DO UPDATE SET
                              payload_json=excluded.payload_json,
                              last_synced_utc=excluded.last_synced_utc
                        """, (kind, str(row["id"]), json.dumps(row, ensure_ascii=False), synced))

        # Never use extractall; write only validated, flat media filenames.
        for name in names:
            match = MEDIA_MEMBER.fullmatch(name)
            if not match:
                continue
            target = media_dir / ("generation_" + match.group(1) + "." + match.group(2))
            temp = target.with_suffix(target.suffix + ".tmp")
            with zf.open(name) as source, temp.open("wb") as output:
                while True:
                    chunk = source.read(1024 * 1024)
                    if not chunk:
                        break
                    output.write(chunk)
            temp.replace(target)


def sync_once() -> None:
    url, token = load_config()
    root = config_path().parent
    temp = root / "export.tmp.zip"
    try:
        download_export(url, token, temp)
        apply_export(temp, root)
        print(datetime.now().astimezone().isoformat(timespec="seconds") + " - AuraVision backup synced.")
    finally:
        try:
            temp.unlink(missing_ok=True)
        except OSError:
            pass


def main() -> None:
    if "--configure" in sys.argv:
        configure()
        return
    if "--once" in sys.argv:
        sync_once()
        return
    print("AuraVision laptop backup running. Sync interval: 15 minutes. Press Ctrl+C to stop.")
    while True:
        try:
            sync_once()
        except KeyboardInterrupt:
            print("Backup stopped.")
            return
        except Exception as exc:
            print(datetime.now().astimezone().isoformat(timespec="seconds") + " - Sync failed: " + str(exc))
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    main()
