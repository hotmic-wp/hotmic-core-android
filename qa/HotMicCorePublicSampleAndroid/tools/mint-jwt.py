#!/usr/bin/env python3
"""Mint HotMic access tokens (HS256) for QA.

Reads the signing secret from $HOTMIC_CORE_API_SECRET (never pass it on the CLI).
Payload shape is the one documented in the public README:
  {"identity": {"user_id", "display_name", "profile_pic"?, "badge"?}, "iat", "exp"}

Usage:
  mint-jwt.py --user-id U --display-name N [--ttl SECONDS | --expired | --malformed]
"""
import argparse, base64, hashlib, hmac, json, os, sys, time


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def mint(secret: str, payload: dict) -> str:
    header = {"alg": "HS256", "typ": "JWT"}
    signing_input = b64url(json.dumps(header, separators=(",", ":")).encode()) + "." + \
        b64url(json.dumps(payload, separators=(",", ":")).encode())
    sig = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return signing_input + "." + b64url(sig)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--user-id", required=True)
    p.add_argument("--display-name", default="HEM96 QA")
    p.add_argument("--profile-pic")
    p.add_argument("--badge")
    p.add_argument("--ttl", type=int, default=3600, help="seconds until exp")
    p.add_argument("--expired", action="store_true", help="exp one hour in the past")
    p.add_argument("--malformed", action="store_true", help="emit a structurally broken token")
    a = p.parse_args()

    secret = os.environ.get("HOTMIC_CORE_API_SECRET")
    if not secret:
        print("HOTMIC_CORE_API_SECRET not set", file=sys.stderr)
        return 2

    if a.malformed:
        print("not.a.jwt")
        return 0

    now = int(time.time())
    identity = {"user_id": a.user_id, "display_name": a.display_name}
    if a.profile_pic:
        identity["profile_pic"] = a.profile_pic
    if a.badge:
        identity["badge"] = a.badge
    exp = now - 3600 if a.expired else now + a.ttl
    print(mint(secret, {"identity": identity, "iat": now - 5, "exp": exp}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
