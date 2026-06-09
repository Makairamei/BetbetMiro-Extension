#!/usr/bin/env python3
"""Validate repository metadata for BetbetMiro Extension.

This script is intentionally lightweight and read-only. It checks JSON shape,
module discovery, and common metadata mistakes without running Gradle or
modifying files.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

REQUIRED_REPO_KEYS = {
    "name",
    "description",
    "iconUrl",
    "manifestVersion",
    "pluginLists",
}

EXCLUDED_DIRS = {
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

SHARED_MODULES = {"Extractors"}


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


def find_modules(root: Path) -> list[str]:
    modules: list[str] = []
    for child in sorted(root.iterdir(), key=lambda item: item.name.lower()):
        if not child.is_dir():
            continue
        if child.name in EXCLUDED_DIRS:
            continue
        if (child / "build.gradle.kts").is_file():
            modules.append(child.name)
    return modules


def validate_repo_json(root: Path, errors: list[str], warnings: list[str]) -> None:
    repo_path = root / "repo.json"
    if not repo_path.is_file():
        errors.append("repo.json is missing")
        return

    try:
        data = load_json(repo_path)
    except json.JSONDecodeError as exc:
        errors.append(f"repo.json is invalid JSON: {exc}")
        return

    if not isinstance(data, dict):
        errors.append("repo.json must be a JSON object")
        return

    missing = sorted(REQUIRED_REPO_KEYS - set(data.keys()))
    if missing:
        errors.append(f"repo.json missing required keys: {', '.join(missing)}")

    plugin_lists = data.get("pluginLists")
    if not isinstance(plugin_lists, list) or not plugin_lists:
        errors.append("repo.json pluginLists must be a non-empty list")
    else:
        for index, url in enumerate(plugin_lists):
            if not isinstance(url, str) or not url.strip():
                errors.append(f"repo.json pluginLists[{index}] must be a non-empty string")
            elif not (url.startswith("http://") or url.startswith("https://")):
                warnings.append(f"repo.json pluginLists[{index}] is not an HTTP URL: {url}")

    manifest_version = data.get("manifestVersion")
    if not isinstance(manifest_version, int):
        errors.append("repo.json manifestVersion must be an integer")


def validate_plugins_json(root: Path, errors: list[str], warnings: list[str]) -> None:
    plugins_path = root / "plugins.json"
    if not plugins_path.is_file():
        warnings.append("plugins.json not found on this branch; it may be generated on the builds branch")
        return

    try:
        data = load_json(plugins_path)
    except json.JSONDecodeError as exc:
        errors.append(f"plugins.json is invalid JSON: {exc}")
        return

    if not isinstance(data, list):
        errors.append("plugins.json must be a JSON array")
        return

    seen_names: set[str] = set()
    duplicate_names: set[str] = set()

    for index, item in enumerate(data):
        if not isinstance(item, dict):
            errors.append(f"plugins.json[{index}] must be an object")
            continue

        name = item.get("name")
        if isinstance(name, str) and name.strip():
            if name in seen_names:
                duplicate_names.add(name)
            seen_names.add(name)
        else:
            warnings.append(f"plugins.json[{index}] has no non-empty name")

        for key in ("version", "url"):
            if key not in item:
                warnings.append(f"plugins.json[{index}] missing key: {key}")

    if duplicate_names:
        errors.append("plugins.json has duplicate plugin names: " + ", ".join(sorted(duplicate_names)))


def validate_modules(root: Path, errors: list[str], warnings: list[str]) -> dict[str, Any]:
    modules = find_modules(root)
    provider_modules = [name for name in modules if name not in SHARED_MODULES]
    shared_modules = [name for name in modules if name in SHARED_MODULES]

    if not modules:
        errors.append("No module directories with build.gradle.kts were found")

    settings_path = root / "settings.gradle.kts"
    if not settings_path.is_file():
        warnings.append("settings.gradle.kts is missing; module auto-include cannot be verified")

    return {
        "module_count": len(modules),
        "provider_count": len(provider_modules),
        "shared_module_count": len(shared_modules),
        "shared_modules": shared_modules,
        "modules": modules,
        "provider_modules": provider_modules,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate BetbetMiro repository metadata.")
    parser.add_argument("--root", default=".", help="Repository root path. Default: current directory.")
    parser.add_argument("--json", action="store_true", help="Print machine-readable JSON output.")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Treat warnings as failures. Default: warnings do not fail validation.",
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()
    errors: list[str] = []
    warnings: list[str] = []

    validate_repo_json(root, errors, warnings)
    validate_plugins_json(root, errors, warnings)
    module_result = validate_modules(root, errors, warnings)

    result = {
        "root": str(root),
        "ok": not errors and not (args.strict and warnings),
        "errors": errors,
        "warnings": warnings,
        **module_result,
    }

    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    else:
        print(f"Modules: {result['module_count']}")
        print(f"Provider module candidates: {result['provider_count']}")
        print(f"Shared helper modules: {result['shared_module_count']}")
        print()

        if warnings:
            print("Warnings:")
            for warning in warnings:
                print(f"- {warning}")
            print()

        if errors:
            print("Errors:")
            for error in errors:
                print(f"- {error}")
            print()

        print("Validation:", "OK" if result["ok"] else "FAILED")

    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
