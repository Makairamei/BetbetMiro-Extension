#!/usr/bin/env python3
"""Read-only request header comparison checker.

This script compares a basic request with a configured request for reviewed
runtime sample URLs. It only produces reports and does not prove playback.
"""

from __future__ import annotations

import argparse
import json
import socket
import ssl
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from http.client import HTTPConnection, HTTPSConnection, HTTPResponse
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

DEFAULT_TIMEOUT_SECONDS = 15
DEFAULT_USER_AGENT = "BetbetMiro-RuntimeHealthCheck/1.0"


@dataclass
class ResponseSummary:
    statusCode: int | None
    responseBytes: int
    responseTimeMs: int | None
    error: str | None


@dataclass
class HeaderResult:
    name: str
    enabled: bool
    ok: bool
    status: str
    url: str | None
    basic: dict[str, Any]
    configured: dict[str, Any]
    notes: str | None


def load_providers(path: Path) -> list[dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    providers = data.get("providers") if isinstance(data, dict) else data
    if not isinstance(providers, list):
        raise ValueError("Config must contain providers array or be an array")
    return providers


def pick_url(provider: dict[str, Any]) -> str | None:
    for key in ("playerUrl", "episodeUrl", "detailUrl"):
        value = str(provider.get(key, "")).strip()
        if value:
            return value
    return None


def request(url: str, headers: dict[str, str], timeout: int) -> ResponseSummary:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        return ResponseSummary(None, 0, None, f"unsupported URL scheme: {parsed.scheme}")
    if not parsed.netloc:
        return ResponseSummary(None, 0, None, "missing host")

    path = parsed.path or "/"
    if parsed.query:
        path += "?" + parsed.query

    connection_class = HTTPSConnection if parsed.scheme == "https" else HTTPConnection
    connection: HTTPSConnection | HTTPConnection | None = None
    start = time.monotonic()
    try:
        connection = connection_class(parsed.netloc, timeout=timeout)
        connection.request("GET", path, headers=headers)
        response: HTTPResponse = connection.getresponse()
        body = response.read(256_000)
        elapsed_ms = int((time.monotonic() - start) * 1000)
        return ResponseSummary(response.status, len(body), elapsed_ms, None)
    except (socket.timeout, TimeoutError) as exc:
        return ResponseSummary(None, 0, None, f"timeout: {exc}")
    except (OSError, ssl.SSLError) as exc:
        return ResponseSummary(None, 0, None, str(exc))
    finally:
        if connection is not None:
            connection.close()


def configured_headers(provider: dict[str, Any]) -> dict[str, str]:
    headers = {
        "User-Agent": str(provider.get("userAgent") or DEFAULT_USER_AGENT),
        "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
    }
    if provider.get("referer"):
        headers["Referer"] = str(provider["referer"])
    if provider.get("origin"):
        headers["Origin"] = str(provider["origin"])
    return headers


def classify(basic: ResponseSummary, configured: ResponseSummary, has_custom_config: bool) -> tuple[bool, str]:
    if configured.error and basic.error:
        if "timeout" in configured.error.lower() or "timeout" in basic.error.lower():
            return False, "HEADER_CHECK_TIMEOUT"
        return False, "HEADER_CHECK_ERROR"
    if not has_custom_config:
        return True, "HEADER_NOT_CONFIGURED"
    if basic.statusCode != configured.statusCode:
        return True, "HEADER_STATUS_DIFFERENT"
    if configured.responseBytes > basic.responseBytes * 2 and configured.responseBytes > 500:
        return True, "HEADER_BODY_SIZE_DIFFERENT"
    if configured.statusCode is not None and 200 <= configured.statusCode <= 399:
        return True, "HEADER_CONFIGURED_RESPONSE_OK"
    return False, "HEADER_CONFIGURED_RESPONSE_NOT_OK"


def check_provider(provider: dict[str, Any], timeout: int) -> HeaderResult:
    name = str(provider.get("name", "UnknownProvider"))
    enabled = bool(provider.get("enabled", True))
    notes = str(provider.get("notes")) if provider.get("notes") else None
    if not enabled:
        empty = asdict(ResponseSummary(None, 0, None, None))
        return HeaderResult(name, False, True, "SKIPPED", None, empty, empty, notes)

    url = pick_url(provider)
    if not url:
        empty = asdict(ResponseSummary(None, 0, None, None))
        return HeaderResult(name, True, True, "HEADER_CHECK_NOT_CONFIGURED", None, empty, empty, notes)

    basic_headers = {"User-Agent": DEFAULT_USER_AGENT, "Accept": "*/*"}
    custom_headers = configured_headers(provider)
    has_custom_config = bool(provider.get("referer") or provider.get("origin") or provider.get("userAgent"))

    basic = request(url, basic_headers, timeout)
    configured = request(url, custom_headers, timeout)
    ok, status = classify(basic, configured, has_custom_config)

    return HeaderResult(name, True, ok, status, url, asdict(basic), asdict(configured), notes)


def write_reports(payload: dict[str, Any], json_output: str, markdown_output: str) -> None:
    if json_output:
        path = Path(json_output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if markdown_output:
        path = Path(markdown_output)
        path.parent.mkdir(parents=True, exist_ok=True)
        lines = [
            "# Runtime Header Comparison Report",
            "",
            f"Generated: `{payload['generatedAt']}`",
            "",
            f"Total: **{payload['summary']['total']}**",
            f"OK: **{payload['summary']['ok']}**",
            f"Failed: **{payload['summary']['failed']}**",
            "",
            "> Header comparison is a signal only. It is not CloudStream playback proof.",
            "",
            "| Provider | Status | Basic HTTP | Configured HTTP | Basic bytes | Configured bytes | URL | Notes |",
            "|---|---:|---:|---:|---:|---:|---|---|",
        ]
        for item in payload["results"]:
            basic = item["basic"]
            configured = item["configured"]
            notes = item["notes"] or configured.get("error") or basic.get("error") or ""
            lines.append(
                f"| {item['name']} | {item['status']} | {basic.get('statusCode') or ''} | {configured.get('statusCode') or ''} | {basic.get('responseBytes') or 0} | {configured.get('responseBytes') or 0} | {item['url'] or ''} | {notes} |"
            )
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run read-only request header comparison checks.")
    parser.add_argument("--config", default="healthcheck/runtime-providers.sample.json")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--json-output", default="")
    parser.add_argument("--markdown-output", default="")
    parser.add_argument("--fail-on-down", action="store_true")
    args = parser.parse_args()

    providers = load_providers(Path(args.config))
    results = [asdict(check_provider(provider, args.timeout)) for provider in providers]
    ok_count = sum(1 for item in results if item["ok"])
    failed_count = sum(1 for item in results if item["enabled"] and not item["ok"])
    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "config": args.config,
        "summary": {"total": len(results), "ok": ok_count, "failed": failed_count},
        "results": results,
    }

    print(f"Checked request header samples: {len(results)}")
    print(f"OK: {ok_count}")
    print(f"Failed: {failed_count}")
    for item in results:
        print(f"- {item['name']}: {item['status']}")

    write_reports(payload, args.json_output, args.markdown_output)
    return 1 if args.fail_on_down and failed_count > 0 else 0


if __name__ == "__main__":
    raise SystemExit(main())
