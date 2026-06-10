#!/usr/bin/env python3
"""Create a read-only inventory of provider modules."""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

EXCLUDED = {
    ".git", ".github", ".gradle", ".idea", ".vscode", "build", "builds",
    "docs", "gradle", "healthcheck", "scripts", "templates"
}
MAIN_URL_RE = re.compile(r"override\s+(?:var|val)\s+mainUrl\s*=\s*\"([^\"]+)\"")
NAME_RE = re.compile(r"override\s+val\s+name\s*=\s*\"([^\"]+)\"")


def module_dirs(root: Path) -> list[Path]:
    return [
        item for item in sorted(root.iterdir(), key=lambda x: x.name.lower())
        if item.is_dir() and item.name not in EXCLUDED and (item / "build.gradle.kts").is_file()
    ]


def scan_module(module: Path, root: Path) -> dict[str, Any]:
    names: set[str] = set()
    urls: set[str] = set()
    files: list[str] = []
    for kt in sorted(module.rglob("*.kt")):
        if "build" in kt.parts:
            continue
        try:
            text = kt.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            text = kt.read_text(encoding="utf-8", errors="ignore")
        found_urls = MAIN_URL_RE.findall(text)
        found_names = NAME_RE.findall(text)
        if found_urls or found_names:
            files.append(str(kt.relative_to(root)))
        urls.update(found_urls)
        names.update(found_names)
    return {
        "module": module.name,
        "names": sorted(names),
        "mainUrls": sorted(urls),
        "files": files,
        "hasProviderSignals": bool(names or urls),
    }


def write_markdown(path: Path, payload: dict[str, Any]) -> None:
    lines = [
        "# Provider Inventory",
        "",
        f"Generated: `{payload['generatedAt']}`",
        "",
        f"Modules: **{payload['moduleCount']}**",
        "",
        "| Module | Names | mainUrl | Files |",
        "|---|---|---|---:|",
    ]
    for item in payload["modules"]:
        names = ", ".join(item["names"])
        urls = ", ".join(item["mainUrls"])
        lines.append(f"| {item['module']} | {names} | {urls} | {len(item['files'])} |")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Create provider inventory from repository files.")
    parser.add_argument("--root", default=".")
    parser.add_argument("--json-output", default="provider-inventory.json")
    parser.add_argument("--markdown-output", default="provider-inventory.md")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    modules = [scan_module(module, root) for module in module_dirs(root)]
    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "moduleCount": len(modules),
        "modules": modules,
    }

    print(f"Provider module candidates: {len(modules)}")
    json_path = Path(args.json_output)
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    write_markdown(Path(args.markdown_output), payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
