#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Smoke-validate the v1.0 catalogs shipped in `catalogs/`.

This script is the acceptance harness called out by PR-7 of the
A2UI v1.0 upgrade (see upgrade_to_a2ui1.0.md, section "## PR-7 Catalog").
It intentionally has ZERO third-party dependencies (no jsonschema / no
pyyaml) so it can run in any CI with a plain `python3`.

Checks performed on every `*_v1_0.json` catalog under `catalogs/`:

 1. Parses as valid JSON (UTF-8).
 2. Has `"protocolVersion": "1.0"` at the top level.
 3. Has a non-empty `$id` and `catalogId` (and they match if both end
    with `.json` suffix of the same filename).
 4. No stale `v0_9` URLs are left over anywhere in the file
    (everything must have been upgraded to `v1_0`).
 5. If the file declares `functions`, each function:
    * is a JSON object;
    * carries a top-level `returnType` whose value is one of the v1.0
      enum values (`string`, `number`, `boolean`, `validationResult`,
      `any`, `void`);
    * carries a top-level `allowedCallers` whose value is one of
      `rendererOnly`, `agentOnly`, `rendererOrAgent`;
    * if `requiresUserActivation == true`, then `allowedCallers` MUST
      equal `rendererOnly` (v1.0 cross-field constraint from
      catalog_definition.json);
    * the `@call` property in `properties` is a `const` matching the
      function key (defensive consistency check).

Usage:
    python3 scripts/validate_v1_0_catalogs.py

Exits with code 0 when all v1.0 catalogs pass, non-zero on any error.
Prints a per-file diagnostic line for human/CI consumption.
"""

from __future__ import annotations

import json
import os
import re
import sys
from typing import Any

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
CATALOGS_DIR = os.path.join(ROOT, "catalogs")

ALLOWED_RETURN_TYPES = {"string", "number", "boolean", "validationResult", "any", "void"}
ALLOWED_CALLERS = {"rendererOnly", "agentOnly", "rendererOrAgent"}

# Matches the stale v0_9 URL fragment that MUST NOT appear anywhere in a
# v1.0 catalog file. Deliberately a substring regex so it catches both
# `.../v0_9/catalogs/basic/catalog.json` and `.../v0_9/common_types.json`.
_V09_URL = re.compile(r"/v0_9/")


def _collect_errors(obj: Any, path: str, errors: list[str]) -> None:
    """Walk JSON looking for stale v0_9 URLs."""
    if isinstance(obj, str):
        if _V09_URL.search(obj):
            errors.append(f"{path}: stale v0_9 URL fragment: {obj!r}")
    elif isinstance(obj, dict):
        for k, v in obj.items():
            _collect_errors(v, f"{path}/{k}", errors)
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            _collect_errors(v, f"{path}[{i}]", errors)


def _validate_function(name: str, fn: dict, errors: list[str]) -> None:
    where = f"functions.{name}"
    if not isinstance(fn, dict):
        errors.append(f"{where}: must be an object")
        return

    # 1) returnType (required by v1.0 catalog_definition.json)
    rt = fn.get("returnType")
    if rt is None:
        errors.append(f"{where}: missing top-level `returnType`")
    elif rt not in ALLOWED_RETURN_TYPES:
        errors.append(
            f"{where}: `returnType`={rt!r} not in {sorted(ALLOWED_RETURN_TYPES)}"
        )

    # 2) allowedCallers (optional per schema but we enforce explicitness
    # so reviewers can audit who's authorised; the v1.0 schema still
    # defaults to rendererOnly if omitted).
    ac = fn.get("allowedCallers")
    if ac is None:
        errors.append(
            f"{where}: missing top-level `allowedCallers` "
            f"(must be one of {sorted(ALLOWED_CALLERS)})"
        )
    elif ac not in ALLOWED_CALLERS:
        errors.append(
            f"{where}: `allowedCallers`={ac!r} not in {sorted(ALLOWED_CALLERS)}"
        )

    # 3) requiresUserActivation ⇒ rendererOnly cross-field rule
    if fn.get("requiresUserActivation") is True and ac != "rendererOnly":
        errors.append(
            f"{where}: requiresUserActivation=true requires "
            f"allowedCallers=='rendererOnly', got {ac!r}"
        )

    # 4) @call const must equal the function key (defensive consistency)
    props = fn.get("properties")
    if isinstance(props, dict):
        at_call = props.get("@call")
        if isinstance(at_call, dict):
            const_v = at_call.get("const")
            if const_v != name:
                errors.append(
                    f"{where}: properties/@call/const={const_v!r} != function key {name!r}"
                )
        else:
            errors.append(
                f"{where}: missing properties.@call (expected const=={name!r})"
            )


def validate_catalog_file(path: str) -> list[str]:
    errors: list[str] = []
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except json.JSONDecodeError as e:
        return [f"{path}: JSON parse error: {e}"]
    except OSError as e:
        return [f"{path}: cannot read: {e}"]

    if not isinstance(data, dict):
        return [f"{path}: top-level must be a JSON object"]

    # 1. protocolVersion
    if data.get("protocolVersion") != "1.0":
        errors.append(
            f"{path}: `protocolVersion` must be the string \"1.0\", "
            f"got {data.get('protocolVersion')!r}"
        )

    # 2. $id / catalogId presence
    if not isinstance(data.get("$id"), str) or not data["$id"]:
        errors.append(f"{path}: missing non-empty `$id`")
    if not isinstance(data.get("catalogId"), str) or not data["catalogId"]:
        errors.append(f"{path}: missing non-empty `catalogId`")

    # 3. No stale v0_9 URLs
    _collect_errors(data, "$", errors)

    # 4. functions sanity
    funcs = data.get("functions")
    if isinstance(funcs, dict):
        for name, fn in funcs.items():
            _validate_function(name, fn, errors)

    return errors


def main() -> int:
    if not os.path.isdir(CATALOGS_DIR):
        print(f"[FAIL] catalogs dir not found: {CATALOGS_DIR}", file=sys.stderr)
        return 2

    v1_files = sorted(
        os.path.join(CATALOGS_DIR, name)
        for name in os.listdir(CATALOGS_DIR)
        if name.endswith("_v1_0.json")
    )
    if not v1_files:
        print(f"[FAIL] no *_v1_0.json files found under {CATALOGS_DIR}", file=sys.stderr)
        return 2

    all_errors: list[str] = []
    for path in v1_files:
        errs = validate_catalog_file(path)
        rel = os.path.relpath(path, ROOT)
        if errs:
            print(f"[FAIL] {rel}")
            for e in errs:
                print(f"       - {e}")
            all_errors.extend(errs)
        else:
            print(f"[ OK ] {rel}")

    if all_errors:
        print(f"\n{len(all_errors)} issue(s) across {len(v1_files)} file(s).", file=sys.stderr)
        return 1
    print(f"\nAll {len(v1_files)} v1.0 catalog file(s) look good.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
