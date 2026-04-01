#!/usr/bin/env python3
"""Trakt device auth + authenticated endpoint test.

Examples:
  python trakt_device_auth_test.py \
    --client-id YOUR_CLIENT_ID \
    --client-secret YOUR_CLIENT_SECRET

  python trakt_device_auth_test.py \
    --client-id YOUR_CLIENT_ID \
    --client-secret YOUR_CLIENT_SECRET \
    --test-path /sync/last_activities

Environment variables also work:
  TRAKT_CLIENT_ID, TRAKT_CLIENT_SECRET, TRAKT_REDIRECT_URI
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Optional

import requests

BASE_URL = "https://api.trakt.tv"
DEFAULT_TEST_PATH = "/users/settings"
DEFAULT_TOKEN_FILE = Path("trakt_tokens.json")
DEFAULT_REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"
TIMEOUT = 30


class TraktError(RuntimeError):
    pass


@dataclass
class TokenBundle:
    access_token: str
    refresh_token: str
    expires_in: int
    created_at: int
    token_type: str = "bearer"
    scope: str = "public"

    @property
    def expires_at(self) -> int:
        return int(self.created_at) + int(self.expires_in)

    @property
    def is_expired(self) -> bool:
        # small buffer so we don't cut it too close
        return time.time() >= (self.expires_at - 60)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "access_token": self.access_token,
            "refresh_token": self.refresh_token,
            "expires_in": self.expires_in,
            "created_at": self.created_at,
            "expires_at": self.expires_at,
            "token_type": self.token_type,
            "scope": self.scope,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "TokenBundle":
        return cls(
            access_token=data["access_token"],
            refresh_token=data["refresh_token"],
            expires_in=int(data["expires_in"]),
            created_at=int(data["created_at"]),
            token_type=data.get("token_type", "bearer"),
            scope=data.get("scope", "public"),
        )


class TraktClient:
    def __init__(self, client_id: str, client_secret: str, redirect_uri: str = DEFAULT_REDIRECT_URI) -> None:
        self.client_id = client_id
        self.client_secret = client_secret
        self.redirect_uri = redirect_uri
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Content-Type": "application/json",
                "trakt-api-version": "2",
                "trakt-api-key": self.client_id,
                "User-Agent": "trakt-device-auth-test/1.0",
            }
        )

    def _request(self, method: str, path: str, *, token: Optional[str] = None, **kwargs: Any) -> requests.Response:
        url = f"{BASE_URL}{path}"
        headers = kwargs.pop("headers", {}).copy()
        if token:
            headers["Authorization"] = f"Bearer {token}"
        resp = self.session.request(method, url, headers=headers, timeout=TIMEOUT, **kwargs)
        return resp

    def request_device_code(self) -> Dict[str, Any]:
        resp = self._request("POST", "/oauth/device/code", json={"client_id": self.client_id})
        self._raise_for_status(resp, context="requesting device code")
        return resp.json()

    def poll_for_device_token(self, device_code: str, interval: int, expires_in: int) -> TokenBundle:
        start = time.time()
        while True:
            if (time.time() - start) >= expires_in:
                raise TraktError("Device code expired before authorization completed. Start the flow again.")

            resp = self._request(
                "POST",
                "/oauth/device/token",
                json={
                    "code": device_code,
                    "client_id": self.client_id,
                    "client_secret": self.client_secret,
                },
            )

            if resp.status_code == 200:
                data = resp.json()
                return TokenBundle.from_dict(data)
            if resp.status_code == 400:
                # pending - wait and continue
                time.sleep(interval)
                continue
            if resp.status_code == 404:
                raise TraktError("Invalid device code (404).")
            if resp.status_code == 409:
                raise TraktError("Device code was already used (409).")
            if resp.status_code == 410:
                raise TraktError("Device code expired (410).")
            if resp.status_code == 418:
                raise TraktError("The user denied access (418).")
            if resp.status_code == 429:
                retry_after = resp.headers.get("Retry-After")
                sleep_for = int(retry_after) if retry_after and retry_after.isdigit() else (interval + 5)
                print(f"Polling too quickly; sleeping {sleep_for}s...", file=sys.stderr)
                time.sleep(sleep_for)
                continue

            self._raise_for_status(resp, context="polling for device token")

    def refresh_access_token(self, refresh_token: str) -> TokenBundle:
        resp = self._request(
            "POST",
            "/oauth/token",
            json={
                "refresh_token": refresh_token,
                "client_id": self.client_id,
                "client_secret": self.client_secret,
                "redirect_uri": self.redirect_uri,
                "grant_type": "refresh_token",
            },
        )
        self._raise_for_status(resp, context="refreshing access token")
        return TokenBundle.from_dict(resp.json())

    def test_authenticated_get(self, path: str, access_token: str) -> requests.Response:
        if not path.startswith("/"):
            path = f"/{path}"
        resp = self._request("GET", path, token=access_token)
        self._raise_for_status(resp, context=f"calling {path}")
        return resp

    @staticmethod
    def _raise_for_status(resp: requests.Response, *, context: str) -> None:
        if resp.ok:
            return

        detail: str
        try:
            detail = json.dumps(resp.json(), indent=2)
        except Exception:
            detail = resp.text.strip() or "<empty response body>"
        raise TraktError(f"HTTP {resp.status_code} while {context}:\n{detail}")


def load_tokens(path: Path) -> Optional[TokenBundle]:
    if not path.exists():
        return None
    try:
        return TokenBundle.from_dict(json.loads(path.read_text()))
    except Exception as exc:
        raise TraktError(f"Failed to parse token file {path}: {exc}") from exc


def save_tokens(path: Path, token_bundle: TokenBundle) -> None:
    path.write_text(json.dumps(token_bundle.to_dict(), indent=2) + "\n")


def resolve_credentials(args: argparse.Namespace) -> tuple[str, str, str]:
    client_id = args.client_id or os.getenv("TRAKT_CLIENT_ID")
    client_secret = args.client_secret or os.getenv("TRAKT_CLIENT_SECRET")
    redirect_uri = args.redirect_uri or os.getenv("TRAKT_REDIRECT_URI") or DEFAULT_REDIRECT_URI

    if not client_id or not client_secret:
        raise TraktError(
            "Missing credentials. Pass --client-id and --client-secret, or set "
            "TRAKT_CLIENT_ID and TRAKT_CLIENT_SECRET."
        )

    return client_id, client_secret, redirect_uri


def authenticate(client: TraktClient, token_path: Path, force_device_auth: bool) -> TokenBundle:
    existing = load_tokens(token_path)

    if existing and not force_device_auth:
        if not existing.is_expired:
            print(f"Using existing access token from {token_path}.")
            return existing

        print("Stored access token is expired or near expiry. Refreshing...")
        try:
            refreshed = client.refresh_access_token(existing.refresh_token)
            save_tokens(token_path, refreshed)
            print(f"Refreshed token and saved it to {token_path}.")
            return refreshed
        except Exception as exc:
            print(f"Refresh failed: {exc}\nFalling back to device auth...", file=sys.stderr)

    device = client.request_device_code()
    verification_url = device["verification_url"]
    user_code = device["user_code"]
    device_code = device["device_code"]
    interval = int(device["interval"])
    expires_in = int(device["expires_in"])

    print("\nAuthorize this app in Trakt:")
    print(f"  URL : {verification_url}")
    print(f"  Code: {user_code}")
    print(f"  Expires in: {expires_in}s")
    print(f"  Poll interval: {interval}s\n")

    token_bundle = client.poll_for_device_token(device_code, interval, expires_in)
    save_tokens(token_path, token_bundle)
    print(f"Authorization successful. Tokens saved to {token_path}.")
    return token_bundle


def print_response(resp: requests.Response) -> None:
    print(f"\nEndpoint test succeeded: HTTP {resp.status_code}")
    if resp.status_code == 204 or not resp.content:
        print("<no content>")
        return

    content_type = resp.headers.get("Content-Type", "")
    if "application/json" in content_type:
        try:
            print(json.dumps(resp.json(), indent=2))
            return
        except Exception:
            pass

    print(resp.text)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Trakt device auth + authenticated endpoint test")
    parser.add_argument("--client-id", help="Your Trakt client_id")
    parser.add_argument("--client-secret", help="Your Trakt client_secret")
    parser.add_argument(
        "--redirect-uri",
        default=None,
        help=(
            "Redirect URI configured in your Trakt app. Only needed for token refresh. "
            f"Default: {DEFAULT_REDIRECT_URI}"
        ),
    )
    parser.add_argument(
        "--token-file",
        default=str(DEFAULT_TOKEN_FILE),
        help=f"Where to store the token bundle (default: {DEFAULT_TOKEN_FILE})",
    )
    parser.add_argument(
        "--test-path",
        default=DEFAULT_TEST_PATH,
        help=f"Authenticated GET endpoint to test (default: {DEFAULT_TEST_PATH})",
    )
    parser.add_argument(
        "--force-device-auth",
        action="store_true",
        help="Ignore any stored tokens and always start a fresh device auth flow",
    )
    return parser.parse_args()


def main() -> int:
    try:
        args = parse_args()
        client_id, client_secret, redirect_uri = resolve_credentials(args)
        token_path = Path(args.token_file)
        client = TraktClient(client_id, client_secret, redirect_uri=redirect_uri)

        token_bundle = authenticate(client, token_path, args.force_device_auth)
        response = client.test_authenticated_get(args.test_path, token_bundle.access_token)
        print_response(response)
        return 0
    except KeyboardInterrupt:
        print("\nInterrupted.", file=sys.stderr)
        return 130
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
