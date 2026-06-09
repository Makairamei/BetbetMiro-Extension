#!/usr/bin/env python3
"""Count CloudStream provider/module directories.

A provider/module candidate is an immediate child directory that contains
`build.gradle.kts`. The root `build.gradle.kts` is intentionally ignored.

This script is lightweight and read-only. It does not modify provider files,
metadata files, or build outputs.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterable

DEFAULT_EXCLUDED_DIRS = {
    ".git",
    ".github",
    ".gradle",
    ".idea",
    ".vscode",
    "build",
    "builds",
    "docs",
    "gradle",
    "scripts",
    "templates",
}

DEFAULT_SHARED_MODULES = {
    "Extractors",
}


def find_modules(root: Path, excluded_dirs: Iterable[str]) -> list[str]:
    excluded = set(excluded_dirs)
    modules: list[str] = []

    for child in sorted(root.iterdir(), key=lambda item: item.name.lower()):
        if not child.is_dir():
            continue
        if child.name in excluded:
            continue
        if (child / "build.gradle.kts").is_file():
            modules.append(child.name)

    return modules


def main() -> int:
    parser = argparse.ArgumentParser(description="Count CloudStream provider/module directories.")
    parser.add_argument(
        "--root",
        default=".",
        help="Repository root path. Default: current directory.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print machine-readable JSON output.",
    )
    parser.add_argument(
        "--include-shared",
        action="store_true",
        help="Include shared helper modules such as Extractors in provider_count.",
    )
    parser.add_argument(
        "--exclude",
        action="append",
        default=[],
        help="Additional directory name to exclude. Can be passed multiple times.",
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()
    excluded = DEFAULT_EXCLUDED_DIRS | set(args.exclude)
    modules = find_modules(root, excluded)

    shared_modules = sorted(name for name in modules if name in DEFAULT_SHARED_MODULES)
    provider_modules = modules if args.include_shared else [name for name in modules if name not in DEFAULT_SHARED_MODULES]

    result = {
        "root": str(root),
        "module_count": len(modules),
        "provider_count": len(provider_modules),
        "shared_module_count": len(shared_modules),
        "shared_modules": shared_modules,
        "modules": modules,
        "provider_modules": provider_modules,
    }

    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    else:
        print(f"CloudStream module candidates: {len(modules)}")
        print(f"Provider module candidates: {len(provider_modules)}")
        if shared_modules:
            print(f"Shared helper modules excluded from provider_count: {', '.join(shared_modules)}")
        print()
        for name in provider_modules:
            print(name)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
