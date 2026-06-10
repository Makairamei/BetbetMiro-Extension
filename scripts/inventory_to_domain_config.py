#!/usr/bin/env python3
"""Convert provider inventory JSON into domain-check config JSON."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", default="provider-inventory.json")
    parser.add_argument("--output", default="generated-domain-config.json")
    args = parser.parse_args()

    data = json.loads(Path(args.inventory).read_text(encoding="utf-8"))
    providers = []
    for item in data.get("modules", []):
        module = str(item.get("module") or "")
        names = item.get("names") or []
        name = str(names[0]) if names else module
        for url in item.get("mainUrls") or []:
            if isinstance(url, str) and url.startswith(("http://", "https://")):
                providers.append({
                    "name": name,
                    "module": module,
                    "mainUrl": url,
                    "enabled": True,
                    "expectedStatus": [200, 301, 302, 303, 307, 308, 403]
                })

    providers.sort(key=lambda row: (row["module"].lower(), row["name"].lower(), row["mainUrl"]))
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"providers": providers}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Domain config entries: {len(providers)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
