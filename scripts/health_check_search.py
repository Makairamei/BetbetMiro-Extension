#!/usr/bin/env python3
"""Read-only search/sample health checker.

This script checks explicit search URL samples from a health-check config. It
is not a CloudStream runtime test and must not be used as playback proof.
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
from urllib.parse import quote_plus, urlparse

DEFAULT_TIMEOUT_SECONDS = 15
DEFAULT_USER_AGENT = "BetbetMiro-SmartHealthCheck/1.0"
MIN_RESPONSE_BYTES = 200


@dataclass
class SearchResult:
    name: str
    enabled: bool
    ok: bool
    status: str
    statusCode: int | None
    searchUrl: str | None
    responseTimeMs: int | None
    responseBytes: int | None
    matchedKeywords: list[str]
    blockedKeywords: list[str]
    error: str | None
    notes: str | None


def load_providers(path: Path) -> list[dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    providers = data.get("providers") if isinstance(data, dict) else data
    if not isinstance(providers, list):
        raise ValueError("Config must contain a providers array or be an array")
    return providers


def http_get(url: str, timeout: int, user_agent: str) -> tuple[int | None, bytes, int | None, str | None]:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        return None, b"", None, f"unsupported URL scheme: {parsed.scheme}"
    if not parsed.netloc:
        return None, b"", None, "missing host"

    path = parsed.path or "/"
    if parsed.query:
        path += "?" + parsed.query

    connection_class = HTTPSConnection if parsed.scheme == "https" else HTTPConnection
    connection: HTTPSConnection | HTTPConnection | None = None
    start = time.monotonic()
    try:
        connection = connection_class(parsed.netloc, timeout=timeout)
        connection.request(
            "GET",
            path,
            headers={
                "User-Agent": user_agent,
                "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            },
        )
        response: HTTPResponse = connection.getresponse()
        body = response.read(256_000)
        elapsed_ms = int((time.monotonic() - start) * 1000)
        return response.status, body, elapsed_ms, None
    except (socket.timeout, TimeoutError) as exc:
        return None, b"", None, f"timeout: {exc}"
    except (OSError, ssl.SSLError) as exc:
        return None, b"", None, str(exc)
    finally:
        if connection is not None:
            connection.close()


def classify(status_code: int | None, body: bytes, error: str | None, matched: list[str], blocked: list[str]) -> tuple[bool, str]:
    if error:
        if "timeout" in error.lower():
            return False, "SEARCH_TIMEOUT"
        return False, "SEARCH_ERROR"
    if status_code is None:
        return False, "SEARCH_ERROR"
    if status_code == 403:
        return False, "SEARCH_BLOCKED"
    if status_code == 404:
        return False, "SEARCH_NOT_FOUND"
    if 500 <= status_code <= 599:
        return False, "SEARCH_SERVER_ERROR"
    if not (200 <= status_code <= 399):
        return False, f"SEARCH_HTTP_{status_code}"
    if blocked:
        return False, "SEARCH_BLOCKED_KEYWORD"
    if len(body) < MIN_RESPONSE_BYTES:
        return False, "SEARCH_EMPTY_OR_SMALL"
    if matched:
        return True, "SEARCH_OK"
    return True, "SEARCH_RESPONSE_OK"


def check_provider(provider: dict[str, Any], timeout: int, user_agent: str) -> SearchResult:
    name = str(provider.get("name", "UnknownProvider"))
    enabled = bool(provider.get("enabled", True))
    notes = provider.get("notes")
    sample_search = str(provider.get("sampleSearch", "")).strip()
    search_template = str(provider.get("searchUrl", "")).strip()

    if not enabled:
        return SearchResult(name, False, True, "SKIPPED", None, None, None, None, [], [], None, str(notes) if notes else None)

    if not sample_search or not search_template:
        return SearchResult(name, True, True, "SEARCH_NOT_CONFIGURED", None, None, None, None, [], [], None, str(notes) if notes else None)

    search_url = search_template.replace("{query}", quote_plus(sample_search))
    status_code, body, elapsed_ms, error = http_get(search_url, timeout, user_agent)
    body_text = body.decode("utf-8", errors="ignore").lower()

    expected_keywords = [str(item) for item in provider.get("expectedKeywords", []) if str(item).strip()]
    blocked_keywords = [str(item) for item in provider.get("blockedKeywords", []) if str(item).strip()]
    matched = [keyword for keyword in expected_keywords if keyword.lower() in body_text]
    blocked = [keyword for keyword in blocked_keywords if keyword.lower() in body_text]

    ok, status = classify(status_code, body, error, matched, blocked)

    return SearchResult(
        name=name,
        enabled=True,
        ok=ok,
        status=status,
        statusCode=status_code,
        searchUrl=search_url,
        responseTimeMs=elapsed_ms,
        responseBytes=len(body),
        matchedKeywords=matched,
        blockedKeywords=blocked,
        error=error,
        notes=str(notes) if notes else None,
    )


def write_reports(payload: dict[str, Any], json_output: str, markdown_output: str) -> None:
    if json_output:
        path = Path(json_output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    if markdown_output:
        path = Path(markdown_output)
        path.parent.mkdir(parents=True, exist_ok=True)
        lines = [
            "# Search Health Report",
            "",
            f"Generated: `{payload['generatedAt']}`",
            "",
            f"Total: **{payload['summary']['total']}**",
            f"OK: **{payload['summary']['ok']}**",
            f"Failed: **{payload['summary']['failed']}**",
            "",
            "> Search health is not CloudStream runtime or playback proof.",
            "",
            "| Provider | Status | HTTP | Bytes | Time | URL | Notes |",
            "|---|---:|---:|---:|---:|---|---|",
        ]
        for item in payload["results"]:
            code = item["statusCode"] if item["statusCode"] is not None else ""
            size = item["responseBytes"] if item["responseBytes"] is not None else ""
            time_ms = f"{item['responseTimeMs']} ms" if item["responseTimeMs"] is not None else ""
            notes = item["notes"] or item["error"] or ""
            lines.append(f"| {item['name']} | {item['status']} | {code} | {size} | {time_ms} | {item['searchUrl'] or ''} | {notes} |")
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run read-only provider search health checks.")
    parser.add_argument("--config", default="healthcheck/providers.sample.json")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--user-agent", default=DEFAULT_USER_AGENT)
    parser.add_argument("--json-output", default="")
    parser.add_argument("--markdown-output", default="")
    parser.add_argument("--fail-on-down", action="store_true")
    args = parser.parse_args()

    providers = load_providers(Path(args.config))
    results = [asdict(check_provider(provider, args.timeout, args.user_agent)) for provider in providers]
    ok_count = sum(1 for item in results if item["ok"])
    failed_count = sum(1 for item in results if item["enabled"] and not item["ok"])
    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "config": args.config,
        "summary": {"total": len(results), "ok": ok_count, "failed": failed_count},
        "results": results,
    }

    print(f"Checked search samples: {len(results)}")
    print(f"OK: {ok_count}")
    print(f"Failed: {failed_count}")
    for item in results:
        print(f"- {item['name']}: {item['status']}")

    write_reports(payload, args.json_output, args.markdown_output)
    return 1 if args.fail_on_down and failed_count > 0 else 0


if __name__ == "__main__":
    raise SystemExit(main())
