#!/usr/bin/env python3
"""Validate, build, and stage IceBridge rule snapshots using Python's standard library."""

import argparse
import collections
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
import tempfile
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
OWNED = ROOT / "online-rules" / "rules.json"
UPSTREAM = "nyehueh/shortx-Fluid_Cloud"
FORMAT = "icebridge-online-rules"
PACKAGE = re.compile(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+")
HOST = re.compile(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
COMMIT = re.compile(r"[0-9a-f]{40}")
CONVERSIONS = {"none", "fixed_deep_link"}
MAX_REVISION = (1 << 63) - 1


def encode(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write_once(path, data):
    """Existing identical records are reusable; different content is never overwritten."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("xb") as stream:
            stream.write(data)
    except FileExistsError:
        if path.read_bytes() != data:
            raise ValueError(f"refusing to overwrite different content: {path}")


def write_atomic(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(data)
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def normalize_host(raw):
    # Mirrors OnlineLinkRuleRepository.normalizeHost; never evaluates a regular expression.
    if not isinstance(raw, str) or len(raw) > 128:
        return None
    host = raw.strip().lower().replace("\\.", ".")
    return host if HOST.fullmatch(host) else None


def validate_document(document):
    if not isinstance(document, dict) or document.get("format") != FORMAT:
        raise ValueError(f"format must be {FORMAT}")
    if set(document) - {"format", "version", "revision", "rules", "provenance"}:
        raise ValueError("unknown snapshot fields")
    if "provenance" in document and not isinstance(document["provenance"], dict):
        raise ValueError("provenance must be an object")
    if type(document.get("version")) is not int or document["version"] != 1:
        raise ValueError("version must be 1")
    if type(document.get("revision")) is not int or not 1 <= document["revision"] <= MAX_REVISION:
        raise ValueError("revision must be a positive signed 64-bit integer")
    rules = document.get("rules")
    if not isinstance(rules, list):
        raise ValueError("rules must be an array; an empty array explicitly withdraws all rules")
    if len(rules) > 768:
        raise ValueError("more than 768 rules; review the candidate instead of silently truncating it")
    seen = set()
    hosts = set()
    fields = {"sourcePackage", "host", "package", "conversion", "note"}
    for index, rule in enumerate(rules):
        where = f"rules[{index}]"
        if not isinstance(rule, dict) or set(rule) != fields:
            raise ValueError(f"{where}: expected exactly {sorted(fields)}")
        if any(not isinstance(value, str) for value in rule.values()):
            raise ValueError(f"{where}: every rule field must be a string")
        if len(rule["sourcePackage"]) > 255 or (rule["sourcePackage"] != "*" and not PACKAGE.fullmatch(rule["sourcePackage"])):
            raise ValueError(f"{where}: invalid sourcePackage")
        if len(rule["package"]) > 255 or not PACKAGE.fullmatch(rule["package"]):
            raise ValueError(f"{where}: invalid package")
        if normalize_host(rule["host"]) != rule["host"]:
            raise ValueError(f"{where}: host must be a normalized exact domain")
        if rule["conversion"] not in CONVERSIONS:
            raise ValueError(f"{where}: unsupported conversion {rule['conversion']!r}")
        if len(rule["note"].encode("utf-16-le")) // 2 > 512:
            raise ValueError(f"{where}: note exceeds 512 characters")
        identity = tuple(rule[key] for key in ("sourcePackage", "host", "package", "conversion"))
        if identity in seen:
            raise ValueError(f"{where}: duplicate rule")
        seen.add(identity)
        hosts.add(rule["host"])
    if len(hosts) > 96:
        raise ValueError("more than 96 distinct hosts; review the candidate instead of silently truncating it")
    return document


def build(source, output):
    document = validate_document(read_json(source))
    data = encode(document)
    revision = document["revision"]
    relative = f"revisions/rules-{revision:06d}.json"
    manifest = {"format": "icebridge-rule-manifest", "version": 1,
                "revision": revision, "file": relative,
                "sha256": hashlib.sha256(data).hexdigest()}
    output = Path(output)
    manifest_path = output / "manifest.json"
    if manifest_path.exists():
        previous = read_json(manifest_path)
        if previous.get("revision", 0) > revision:
            raise ValueError("revision cannot go backwards; publish a new full snapshot to revert")
        if previous.get("revision") == revision and previous != manifest:
            raise ValueError("revision already published with different content; increment revision")
    write_once(output / relative, data)
    write_atomic(manifest_path, encode(manifest))
    return manifest


def fetch(url, maximum):
    request = urllib.request.Request(url, headers={"User-Agent": "IceBridge-rule-maintainer/1",
                                                  "Accept": "application/vnd.github+json"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                data = response.read(maximum + 1)
            if len(data) > maximum:
                raise ValueError(f"response exceeds explicit {maximum}-byte limit: {url}")
            return data
        except (OSError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def archive_upstream(commit, directory):
    if not COMMIT.fullmatch(commit):
        raise ValueError("--commit must be a full lowercase 40-character Git commit SHA")
    directory = Path(directory).resolve()
    if directory.is_relative_to(ROOT):
        raise ValueError("raw upstream archive must be outside the project checkout")
    tree_path = directory / "github-tree.json"
    tree_url = f"https://api.github.com/repos/{UPSTREAM}/git/trees/{commit}?recursive=1"
    tree_data = tree_path.read_bytes() if tree_path.exists() else fetch(tree_url, 2 * 1024 * 1024)
    tree = json.loads(tree_data)
    if tree.get("sha") != commit or tree.get("truncated"):
        raise ValueError("upstream tree is truncated or does not match the pinned commit")
    entries = sorted((entry for entry in tree["tree"]
                      if entry.get("type") == "blob" and entry.get("path", "").startswith("rules/")),
                     key=lambda entry: entry["path"])
    if not entries:
        raise ValueError("upstream commit contains no rules/ files")
    write_once(tree_path, tree_data)

    def download(entry):
        relative = PurePosixPath(entry["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError(f"unsafe upstream path: {relative}")
        destination = directory.joinpath(*relative.parts)
        url = f"https://raw.githubusercontent.com/{UPSTREAM}/{commit}/{urllib.parse.quote(str(relative))}"
        data = destination.read_bytes() if destination.exists() else fetch(url, 1024 * 1024)
        blob = hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()
        if blob != entry["sha"]:
            raise ValueError(f"Git blob checksum mismatch: {relative}")
        write_once(destination, data)
        return {"path": str(relative), "gitBlobSha": blob,
                "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        records = list(executor.map(download, entries))
    record = {"repository": UPSTREAM, "commit": commit, "fileCount": len(records), "files": records}
    write_once(directory / "archive.json", encode(record))
    return record


def extract_safe_rules(directory, archive, revision):
    rules, excluded, files, seen = [], [], [], set()
    for record in archive["files"]:
        path = record["path"]
        try:
            rule = read_json(Path(directory) / path)
        except (ValueError, UnicodeError):
            excluded.append({"file": path, "reason": "invalid_json"})
            continue
        if not isinstance(rule, dict):
            excluded.append({"file": path, "reason": "not_rule_object"})
            continue
        if not isinstance(rule.get("type"), str) or rule["type"].lower() != "pkg":
            excluded.append({"file": path, "reason": "type_not_pkg"})
            continue
        package = rule.get("pkg")
        if not isinstance(package, str) or not PACKAGE.fullmatch(package):
            excluded.append({"file": path, "reason": "invalid_package"})
            continue
        triggers = rule.get("tigger")
        if not isinstance(triggers, list):
            excluded.append({"file": path, "reason": "missing_trigger_array"})
            continue
        accepted = 0
        for index, trigger in enumerate(triggers):
            host = normalize_host(trigger)
            if host is None:
                excluded.append({"file": path, "triggerIndex": index,
                                 "reason": "trigger_not_exact_domain"})
                continue
            identity = (host, package)
            if identity in seen:
                excluded.append({"file": path, "triggerIndex": index,
                                 "reason": "duplicate_host_package"})
                continue
            seen.add(identity)
            rules.append({"sourcePackage": "*", "host": host, "package": package,
                          "conversion": "none", "note": ""})
            accepted += 1
        ignored = sorted(set(rule) - {"type", "pkg", "tigger"})
        files.append({"file": path, "acceptedRules": accepted,
                      "ignoredFields": ignored, "sha256": record["sha256"]})
    document = {"format": FORMAT, "version": 1, "revision": revision, "rules": rules,
                "provenance": {"repository": f"https://github.com/{UPSTREAM}",
                               "commit": archive["commit"], "upstreamFiles": archive["fileCount"],
                               "policy": "pkg plus exact domains only; no upstream executable code"}}
    validate_document(document)
    report = {"repository": UPSTREAM, "commit": archive["commit"],
              "upstreamFiles": archive["fileCount"], "acceptedRules": len(rules),
              "uniqueHosts": len({rule["host"] for rule in rules}),
              "uniquePackages": len({rule["package"] for rule in rules}),
              "excludedByReason": dict(sorted(collections.Counter(item["reason"] for item in excluded).items())),
              "excluded": excluded, "files": files,
              "policy": "Every upstream file inspected; no file-count or host-count truncation. "
                        "All fields other than type/pkg/tigger are discarded, including code. "
                        "This is a full candidate snapshot, not an additive merge."}
    return document, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validation = commands.add_parser("validate", help="validate a complete owned or candidate snapshot")
    validation.add_argument("source", type=Path, nargs="?", default=OWNED)
    building = commands.add_parser("build", help="write an immutable revision and atomically update manifest")
    building.add_argument("--source", type=Path, default=OWNED)
    building.add_argument("--output", type=Path, default=ROOT / "online-rules")
    importing = commands.add_parser("import-upstream", help="archive all raw files and produce a review candidate")
    importing.add_argument("--commit", required=True)
    importing.add_argument("--archive", required=True, type=Path)
    importing.add_argument("--candidate", required=True, type=Path)
    importing.add_argument("--report", required=True, type=Path)
    importing.add_argument("--revision", type=int, required=True)
    args = parser.parse_args()
    try:
        if args.command == "validate":
            document = validate_document(read_json(args.source))
            print(f"Valid revision {document['revision']}: {len(document['rules'])} rules")
        elif args.command == "build":
            print(json.dumps(build(args.source, args.output), ensure_ascii=False))
        else:
            if args.candidate.resolve() == OWNED.resolve() or args.report.resolve() == OWNED.resolve():
                raise ValueError("import never writes the owned rules.json; choose a candidate path")
            archive = archive_upstream(args.commit, args.archive)
            document, report = extract_safe_rules(args.archive, archive, args.revision)
            write_once(args.candidate, encode(document))
            write_once(args.report, encode(report))
            print(f"Archived {archive['fileCount']} files; staged {len(document['rules'])} rules")
            print(json.dumps(report["excludedByReason"], ensure_ascii=False))
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"error: {error}\n")


if __name__ == "__main__":
    main()
