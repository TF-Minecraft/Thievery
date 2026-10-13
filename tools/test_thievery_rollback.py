"""Tests for thievery_rollback.py over a fixture laid out as LockMigration writes it."""

import hashlib
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import thievery_rollback  # noqa: E402


def sha(data):
    return hashlib.sha256(data).hexdigest()


class Fixture:
    def __init__(self, root):
        self.root = root
        (root / "migrations" / "backup").mkdir(parents=True)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.encode())
        return path

    def journal(self, number, changes, completed=True, extra=None):
        """changes: {store name: (original text, new text)}; files end up at the new text.
        extra: {store name: fields added to that entry, such as kind and deathId}."""
        archive = f"backup/locks-{number:04d}.zip"
        with zipfile.ZipFile(self.root / "migrations" / archive, "w") as zipped:
            for name, (original, _) in changes.items():
                zipped.writestr(name, original)
        entries = []
        for name, (original, new) in changes.items():
            self.write(name, new)
            entry = {"path": name, "originalHash": sha(original.encode()), "newHash": sha(new.encode()),
                     "previousOwner": "a", "owner": "b", "ownerKind": "CHARACTER"}
            entry.update((extra or {}).get(name, {}))
            entries.append(entry)
        (self.root / "migrations" / f"journal-{number:04d}.json").write_text(
            json.dumps({"number": number, "archive": archive, "completed": completed, "entries": entries}))

    def markers(self):
        for store in thievery_rollback.STORES:
            self.write(f"{store}/data-version", "1\n")
        self.write("migrations/quarantine.json", "{}")

    def text(self, name):
        return (self.root / name).read_text()


class RollbackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.fx = Fixture(Path(self.temp.name) / "Thievery")
        self.addCleanup(self.temp.cleanup)

    def run_tool(self, *args):
        out = io.StringIO()
        code = thievery_rollback.main([str(self.fx.root), *args], out=out)
        return code, out.getvalue()

    def build(self):
        fx = self.fx
        fx.journal(1, {
            "data/0_0/1_64_1.json": ('{"owner":"acct"}', '{"owner":"heir","ownerKind":"CHARACTER"}'),
            "data/0_0/2_64_1.json": ('{"owner":"acct2"}', '{"owner":"heir2","ownerKind":"CHARACTER"}'),
            "door-data/0_0/3_64_1.json": ('{"key":"k","ownerUUID":"acct"}', '{"key":"k","ownerUUID":"heir"}'),
            "entities/e.json": ('{"owner":"acct"}', '{"owner":"heir","ownerKind":"CHARACTER"}'),
        })
        # An old jar rewrote file 1 bare after the first pass and the second pass marked it again, so only the
        # second pass can be undone there. The door went through both passes unchanged in between, so both undo.
        fx.journal(2, {"data/0_0/1_64_1.json": ('{"owner":"heir"}', '{"owner":"heir","ownerKind":"CHARACTER","x":1}'),
                       "door-data/0_0/3_64_1.json": ('{"key":"k","ownerUUID":"heir"}',
                                                     '{"key":"k","ownerUUID":"heir","ownerKind":"CHARACTER"}')})
        fx.write("data/0_0/2_64_1.json", '{"owner":"someone else","ownerKind":"CHARACTER"}')
        (fx.root / "entities/e.json").unlink()
        fx.markers()

    def test_a_dry_run_reports_every_journal_newest_first_and_writes_nothing(self):
        self.build()
        before = {p: p.read_bytes() for p in self.fx.root.rglob("*") if p.is_file()}
        code, out = self.run_tool()
        self.assertEqual(0, code)
        lines = out.splitlines()
        self.assertTrue(lines[0].startswith("journal-0002.json: 2 entries, would restore 2"))
        self.assertIn("journal-0001.json: 4 entries, would restore 1, changed 2, missing 1, archive mismatch 0", out)
        self.assertIn("changed since the migration, left alone: data/0_0/2_64_1.json", out)
        self.assertIn("missing, left alone: entities/e.json", out)
        self.assertIn("Dry run", out)
        self.assertEqual(before, {p: p.read_bytes() for p in self.fx.root.rglob("*") if p.is_file()})

    def test_apply_restores_chained_passes_leaves_changed_and_missing_files_and_removes_markers(self):
        self.build()
        report = Path(self.temp.name) / "report.json"
        code, out = self.run_tool("--apply", "--report", str(report))
        self.assertEqual(0, code)
        self.assertEqual('{"owner":"heir"}', self.fx.text("data/0_0/1_64_1.json"), "only the newer pass matches")
        self.assertEqual('{"owner":"someone else","ownerKind":"CHARACTER"}', self.fx.text("data/0_0/2_64_1.json"))
        self.assertEqual('{"key":"k","ownerUUID":"acct"}', self.fx.text("door-data/0_0/3_64_1.json"), "both passes undone")
        self.assertFalse((self.fx.root / "entities/e.json").exists(), "a missing file is never restored")
        self.assertEqual([], list((self.fx.root / "migrations").glob("journal-*.json")))
        for store in thievery_rollback.STORES:
            self.assertFalse((self.fx.root / store / "data-version").exists())
        self.assertFalse((self.fx.root / "migrations/quarantine.json").exists())
        self.assertTrue((self.fx.root / "migrations/backup/locks-0001.zip").exists(), "archives are kept")
        self.assertEqual([], list(self.fx.root.rglob("*.rollback.tmp")))
        written = json.loads(report.read_text())
        self.assertTrue(written["apply"])
        self.assertEqual(["data/0_0/1_64_1.json", "door-data/0_0/3_64_1.json"], written["journals"][0]["restored"])
        self.assertIn("migrations/quarantine.json", written["removed"])
        self.assertIn("Removed 6 migration markers", out)

    def test_an_archive_copy_that_does_not_match_is_left_alone(self):
        fx = self.fx
        fx.journal(1, {"data/0_0/1_64_1.json": ('{"owner":"acct"}', '{"owner":"heir"}'),
                       "data/0_0/2_64_1.json": ('{"owner":"acct"}', '{"owner":"heir2"}')})
        journal_path = fx.root / "migrations/journal-0001.json"
        journal = json.loads(journal_path.read_text())
        journal["entries"][0]["originalHash"] = "0" * 64
        journal["entries"].append({"path": "data/0_0/9_64_1.json", "originalHash": "x", "newHash": sha(b"nine")})
        fx.write("data/0_0/9_64_1.json", "nine")
        journal_path.write_text(json.dumps(journal))
        code, out = self.run_tool("--apply")
        self.assertEqual(0, code)
        self.assertIn("archive mismatch 2", out)
        self.assertEqual('{"owner":"heir"}', fx.text("data/0_0/1_64_1.json"))
        self.assertEqual('{"owner":"acct"}', fx.text("data/0_0/2_64_1.json"))
        self.assertIn("Removed 1 migration markers", out)

    def test_untrustworthy_journals_or_archives_change_nothing(self):
        fx = self.fx
        fx.journal(1, {"data/0_0/1_64_1.json": ('{"owner":"acct"}', '{"owner":"heir"}')})
        journal_path = fx.root / "migrations/journal-0001.json"
        good = journal_path.read_text()
        for broken in ("{not json", "[]", '{"entries":[]}', '{"archive":"backup/locks-0001.zip","entries":null}',
                       '{"archive":"backup/locks-0001.zip","entries":[{"newHash":"x"}]}',
                       '{"archive":"backup/locks-0001.zip","entries":[{"path":"../../server.properties"}]}',
                       '{"archive":"backup/locks-0001.zip","entries":[{"path":"plugins/x.json"}]}',
                       '{"archive":"backup/locks-0001.zip","entries":[{"path":"/etc/passwd"}]}',
                       '{"archive":"backup/locks-0001.zip","entries":[{"path":"data"}]}',
                       '{"archive":"backup/missing.zip","entries":[]}'):
            journal_path.write_text(broken)
            code, out = self.run_tool("--apply")
            self.assertEqual(2, code, broken)
            self.assertTrue(out.startswith("Nothing changed:"), out)
            self.assertTrue(journal_path.exists())
        journal_path.write_text(good)
        (fx.root / "migrations/backup/locks-0001.zip").write_text("not a zip")
        self.assertEqual(2, self.run_tool("--apply")[0])
        self.assertEqual('{"owner":"heir"}', fx.text("data/0_0/1_64_1.json"))

    def test_a_store_without_journals_only_loses_its_markers(self):
        self.fx.write("migrations/journal-x.json", "ignored")
        self.fx.markers()
        code, out = self.run_tool()
        self.assertEqual(0, code)
        self.assertTrue((self.fx.root / "data/data-version").exists())
        code, out = self.run_tool("--apply")
        self.assertEqual(0, code)
        self.assertFalse((self.fx.root / "data/data-version").exists())
        self.assertTrue((self.fx.root / "migrations/journal-x.json").exists())
        empty = Path(self.temp.name) / "empty"
        empty.mkdir()
        self.assertEqual(0, thievery_rollback.main([str(empty), "--apply"], out=io.StringIO()))


    def build_estates(self):
        """Pass 1 migrates; pass 2 mixes a migration with estate moves; pass 3 moves an estate only."""
        fx = self.fx
        estate = {"kind": "estate", "deathId": "d1"}
        fx.journal(1, {"data/0_0/1_64_1.json": ('{"owner":"acct"}', '{"owner":"dead","ownerKind":"CHARACTER"}')},
                   extra={"data/0_0/1_64_1.json": {"kind": "migrate"}})
        fx.journal(2, {
            "data/0_0/2_64_1.json": ('{"owner":"acct2"}', '{"owner":"heir2","ownerKind":"CHARACTER"}'),
            "data/0_0/3_64_1.json": ('{"owner":"dead","ownerKind":"CHARACTER","lockState":"GUILD"}',
                                     '{"owner":"leader","ownerKind":"CHARACTER","lockState":"GUILD"}'),
            "door-data/0_0/4_64_1.json": ('{"key":"k","ownerUUID":"dead"}', '{"key":"k","ownerUUID":"dead"}'),
        }, extra={"data/0_0/2_64_1.json": {"kind": "migrate"}, "data/0_0/3_64_1.json": estate,
                  "door-data/0_0/4_64_1.json": dict(estate, skipped=True)})
        fx.journal(3, {"entities/e.json": ('{"owner":"dead","ownerKind":"CHARACTER"}',
                                           '{"owner":"heir","ownerKind":"CHARACTER"}')},
                   extra={"entities/e.json": estate})
        fx.markers()

    def test_estate_only_undoes_estate_moves_and_keeps_the_migration_markers(self):
        self.build_estates()
        report = Path(self.temp.name) / "report.json"
        code, out = self.run_tool("--estate-only")
        self.assertEqual(0, code)
        self.assertIn("journal-0003.json: 1 entries, would restore 1", out)
        self.assertIn("journal-0002.json: 1 entries, would restore 1", out)
        self.assertIn("journal-0001.json: 0 entries, would restore 0", out)
        self.assertEqual('{"owner":"heir","ownerKind":"CHARACTER"}', self.fx.text("entities/e.json"))

        code, out = self.run_tool("--estate-only", "--apply", "--report", str(report))
        self.assertEqual(0, code)
        self.assertEqual('{"owner":"dead","ownerKind":"CHARACTER"}', self.fx.text("entities/e.json"))
        self.assertEqual('{"owner":"dead","ownerKind":"CHARACTER","lockState":"GUILD"}', self.fx.text("data/0_0/3_64_1.json"))
        self.assertEqual('{"owner":"heir2","ownerKind":"CHARACTER"}', self.fx.text("data/0_0/2_64_1.json"),
                         "6a moves stay")
        self.assertEqual('{"owner":"dead","ownerKind":"CHARACTER"}', self.fx.text("data/0_0/1_64_1.json"))
        self.assertEqual(3, len(list((self.fx.root / "migrations").glob("journal-*.json"))))
        for store in thievery_rollback.STORES:
            self.assertTrue((self.fx.root / store / "data-version").exists())
        self.assertTrue((self.fx.root / "migrations/quarantine.json").exists())
        self.assertIn("Estate moves undone; migration markers and archives kept.", out)
        written = json.loads(report.read_text())
        self.assertTrue(written["estate_only"])
        self.assertEqual([], written["removed"])

        # A second run finds the files at their originals and leaves them alone.
        code, out = self.run_tool("--estate-only", "--apply")
        self.assertIn("journal-0003.json: 1 entries, restored 0, changed 1", out)

    def test_entries_skipped_at_replay_are_never_restored(self):
        self.build_estates()
        door = "door-data/0_0/4_64_1.json"
        self.fx.write(door, '{"key":"k","ownerUUID":"dead","x":1}')
        code, out = self.run_tool("--apply")
        self.assertEqual(0, code)
        self.assertIn("journal-0002.json: 2 entries, restored 2", out)
        self.assertEqual('{"key":"k","ownerUUID":"dead","x":1}', self.fx.text(door))
        self.assertEqual('{"owner":"acct"}', self.fx.text("data/0_0/1_64_1.json"), "a full rollback undoes both stages")

    def test_estate_entries_an_old_jar_rewrote_without_their_kind_need_a_full_rollback(self):
        fx = self.fx
        fx.journal(1, {"data/0_0/5_64_1.json": ('{"owner":"dead","ownerKind":"CHARACTER"}',
                                               '{"owner":"heir","ownerKind":"CHARACTER"}')})
        code, out = self.run_tool("--estate-only", "--apply")
        self.assertIn("journal-0001.json: 0 entries, restored 0", out)
        self.assertEqual('{"owner":"heir","ownerKind":"CHARACTER"}', fx.text("data/0_0/5_64_1.json"))
        self.run_tool("--apply")
        self.assertEqual('{"owner":"dead","ownerKind":"CHARACTER"}', fx.text("data/0_0/5_64_1.json"))


if __name__ == "__main__":
    unittest.main()
