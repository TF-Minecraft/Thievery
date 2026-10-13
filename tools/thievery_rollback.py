#!/usr/bin/env python3
"""Roll Thievery's lock stores back from the character-lock migration (stage 6a) and estate moves (6b).

Run it with the server stopped, against the plugin folder (plugins/Thievery):

    python3 tools/thievery_rollback.py /path/to/plugins/Thievery                  # dry run
    python3 tools/thievery_rollback.py /path/to/plugins/Thievery --apply          # restore
    python3 tools/thievery_rollback.py /path/to/plugins/Thievery --estate-only    # estate moves only

Every migrations/journal-NNNN.json is processed newest first. A file is restored from
that journal's archive only if its current SHA-256 equals the journal's newHash, so files
changed or deleted since the pass are listed and left alone. With --apply the tool
then deletes the journals, the data-version file in each store and
migrations/quarantine.json, so a later upgrade starts clean. Archives are kept.
Nothing is written without --apply, and nothing is written when a journal or archive
cannot be read.

With --estate-only only entries marked "kind": "estate" are restored and the 6a markers
(journals, data-version, quarantine.json) are kept. An older Thievery that completes an
estate journal rewrites it without "kind" and "deathId", and those entries can then only
be undone by a full rollback. Entries skipped at replay moved nothing and are left out.
"""

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
from pathlib import Path, PurePosixPath

STORES = ("data", "door-data", "entities")
JOURNAL = re.compile(r"journal-(\d{4,})\.json")


class RollbackError(Exception):
    """A journal, archive or entry that cannot be trusted; nothing is written."""


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def store_path(root, name):
    """The file a journal entry names, refusing anything outside the three stores."""
    parts = PurePosixPath(name).parts
    if len(parts) < 2 or parts[0] not in STORES or ".." in parts or PurePosixPath(name).is_absolute():
        raise RollbackError(f"journal entry outside the lock stores: {name}")
    return root.joinpath(*parts)


def journals(root):
    """Journal files, newest first."""
    migrations = root / "migrations"
    found = []
    if migrations.is_dir():
        for path in migrations.iterdir():
            match = JOURNAL.fullmatch(path.name)
            if match:
                found.append((int(match.group(1)), path))
    return [path for _, path in sorted(found, reverse=True)]


def read_journal(path):
    try:
        journal = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise RollbackError(f"{path.name} cannot be read: {error}") from error
    if not isinstance(journal, dict) or not isinstance(journal.get("entries"), list) \
            or not isinstance(journal.get("archive"), str):
        raise RollbackError(f"{path.name} is not a lock migration journal")
    return journal


def selected(entry, estate_only):
    """Whether a rollback undoes this entry: skipped estate entries moved nothing."""
    if entry.get("skipped"):
        return False
    return not estate_only or entry.get("kind") == "estate"


def plan(root, estate_only=False):
    """Decide every restore without writing anything. Later journals see earlier restores."""
    current = {}
    restores = []
    report = []
    for path in journals(root):
        journal = read_journal(path)
        archive_path = root / "migrations" / journal["archive"]
        try:
            archive = zipfile.ZipFile(archive_path)
        except (OSError, zipfile.BadZipFile) as error:
            raise RollbackError(f"archive {journal['archive']} for {path.name} cannot be read: {error}") from error
        entries = []
        for entry in journal["entries"]:
            if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
                raise RollbackError(f"{path.name} has an entry without a path")
            store_path(root, entry["path"])
            if selected(entry, estate_only):
                entries.append(entry)
        result = {"journal": path.name, "completed": bool(journal.get("completed")),
                  "entries": len(entries), "restored": [], "changed": [], "missing": [],
                  "archive_mismatch": []}
        with archive:
            for entry in entries:
                name = entry["path"]
                target = store_path(root, name)
                if target not in current:
                    current[target] = sha256(target.read_bytes()) if target.is_file() else None
                if current[target] is None:
                    result["missing"].append(name)
                    continue
                if current[target] != entry.get("newHash"):
                    result["changed"].append(name)
                    continue
                try:
                    original = archive.read(name)
                except KeyError:
                    result["archive_mismatch"].append(name)
                    continue
                if sha256(original) != entry.get("originalHash"):
                    result["archive_mismatch"].append(name)
                    continue
                restores.append((target, original))
                current[target] = sha256(original)
                result["restored"].append(name)
        report.append(result)
    return restores, report


def write_durably(target, data):
    temp = target.with_name("." + target.name + ".rollback.tmp")
    with open(temp, "wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temp, target)
    sync_directory(target.parent)


def sync_directory(directory):
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def apply(root, restores, remove_markers=True):
    for target, original in restores:
        write_durably(target, original)
    removed = []
    if not remove_markers:
        return removed
    for path in journals(root):
        path.unlink()
        removed.append(str(path.relative_to(root)))
    for store in STORES:
        version = root / store / "data-version"
        if version.exists():
            version.unlink()
            removed.append(str(version.relative_to(root)))
    quarantine = root / "migrations" / "quarantine.json"
    if quarantine.exists():
        quarantine.unlink()
        removed.append(str(quarantine.relative_to(root)))
    return removed


def main(argv=None, out=sys.stdout):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("plugin_dir", type=Path, help="the plugins/Thievery folder")
    parser.add_argument("--apply", action="store_true", help="restore files and remove the migration markers")
    parser.add_argument("--estate-only", action="store_true",
                        help="undo estate moves only and keep the 6a migration markers")
    parser.add_argument("--report", type=Path, help="also write the result as JSON to this file")
    args = parser.parse_args(argv)
    root = args.plugin_dir
    try:
        restores, report = plan(root, args.estate_only)
    except RollbackError as error:
        print(f"Nothing changed: {error}", file=out)
        return 2
    for result in report:
        verb = "restored" if args.apply else "would restore"
        print(f"{result['journal']}: {result['entries']} entries, {verb} {len(result['restored'])}, "
              f"changed {len(result['changed'])}, missing {len(result['missing'])}, "
              f"archive mismatch {len(result['archive_mismatch'])}", file=out)
        for key, label in (("changed", "changed since the migration"), ("missing", "missing"),
                           ("archive_mismatch", "archive copy does not match")):
            for name in result[key]:
                print(f"  {label}, left alone: {name}", file=out)
    removed = apply(root, restores, not args.estate_only) if args.apply else []
    if args.apply and args.estate_only:
        print("Estate moves undone; migration markers and archives kept.", file=out)
    elif args.apply:
        print(f"Removed {len(removed)} migration markers; archives kept.", file=out)
    else:
        print("Dry run: nothing was written. Re-run with --apply, with the server stopped.", file=out)
    if args.report:
        args.report.write_text(json.dumps({"apply": args.apply, "estate_only": args.estate_only, "journals": report,
                                           "removed": removed}, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
