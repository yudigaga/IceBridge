import copy
import hashlib
from pathlib import Path
import tempfile
import unittest

import manage_rules as rules


def snapshot(revision=1):
    return {"format": rules.FORMAT, "version": 1, "revision": revision,
            "rules": [{"sourcePackage": "*", "host": "example.com", "package": "com.example.app",
                       "conversion": "none", "note": ""}]}


class RuleMaintenanceTests(unittest.TestCase):
    def test_exact_domains_only(self):
        self.assertEqual("example.com", rules.normalize_host(r"example\.com"))
        for value in ("https://example.com", "*.example.com", "example.com/path",
                      "^example.com$", r"example\d.com", "(foo|bar).com", 123):
            with self.subTest(value=value):
                self.assertIsNone(rules.normalize_host(value))

    def test_supported_conversion_and_sources(self):
        document = snapshot()
        document["rules"][0].update(sourcePackage="com.tencent.mm", conversion="fixed_deep_link")
        self.assertIs(document, rules.validate_document(document))
        for patch in ({"conversion": "javascript:run()"}, {"sourcePackage": "bad"},
                      {"package": "a" * 256 + ".app"}, {"note": "😀" * 257}):
            candidate = copy.deepcopy(document)
            candidate["rules"][0].update(patch)
            with self.assertRaises(ValueError):
                rules.validate_document(candidate)

    def test_strict_schema_and_revision(self):
        for patch in ({"version": True}, {"revision": 0}, {"revision": 1 << 63},
                      {"unexpected": "value"}, {"provenance": []}):
            candidate = snapshot()
            candidate.update(patch)
            with self.assertRaises(ValueError):
                rules.validate_document(candidate)
        document = snapshot()
        document["rules"].append(copy.deepcopy(document["rules"][0]))
        with self.assertRaises(ValueError):
            rules.validate_document(document)

    def test_limits_fail_without_truncation(self):
        document = snapshot()
        document["rules"] = [dict(document["rules"][0], host=f"host{i}.example.com") for i in range(97)]
        with self.assertRaisesRegex(ValueError, "96 distinct hosts"):
            rules.validate_document(document)
        self.assertEqual(97, len(document["rules"]))
        document["rules"] = [dict(document["rules"][0], package=f"com.example.app{i}") for i in range(769)]
        with self.assertRaisesRegex(ValueError, "768 rules"):
            rules.validate_document(document)

    def test_exact_utf8_byte_limit_includes_canonical_encoding(self):
        document = snapshot()
        document["provenance"] = {"padding": ""}
        remaining = rules.MAX_BUNDLE_BYTES - len(rules.encode(document))
        document["provenance"]["padding"] = "x" * remaining
        self.assertEqual(rules.MAX_BUNDLE_BYTES, len(rules.encode(document)))
        self.assertIs(document, rules.validate_document(document))
        document["provenance"]["padding"] += "x"
        with self.assertRaisesRegex(ValueError, "1048576 UTF-8 bytes"):
            rules.validate_document(document)

    def test_individually_valid_unicode_rules_cannot_exceed_bundle_byte_limit(self):
        document = snapshot()
        document["rules"] = [
            {"sourcePackage": "p." + "x" * 240 + f"{index:013d}", "host": "example.com",
             "package": "q." + "y" * 253, "conversion": "none", "note": "🌊" * 256}
            for index in range(768)]
        self.assertGreater(len(rules.encode(document)), rules.MAX_BUNDLE_BYTES)
        with self.assertRaisesRegex(ValueError, "UTF-8 bytes"):
            rules.validate_document(document)

    def test_provenance_depth_matches_client_root_at_zero(self):
        document = snapshot()
        current = document["provenance"] = {}
        # Provenance is depth 1; the deepest empty object here is exactly depth 16.
        for unused in range(15):
            current["child"] = {}
            current = current["child"]
        self.assertIs(document, rules.validate_document(document))
        current["child"] = {}
        with self.assertRaisesRegex(ValueError, "JSON depth 16"):
            rules.validate_document(document)

    def test_json_node_limit_counts_containers_and_values_but_not_keys(self):
        document = snapshot()
        document["rules"] = []
        # root, format, version, revision, rules, provenance, values = 7 nodes.
        document["provenance"] = {"values": [None] * (rules.MAX_JSON_NODES - 7)}
        self.assertIs(document, rules.validate_document(document))
        document["provenance"]["values"].append(None)
        with self.assertRaisesRegex(ValueError, "16000 JSON nodes"):
            rules.validate_document(document)

    def test_nonfinite_provenance_numbers_are_never_published(self):
        for value in (float("nan"), float("inf"), float("-inf")):
            document = snapshot()
            document["provenance"] = {"number": value}
            with self.subTest(value=value), self.assertRaises(ValueError):
                rules.validate_document(document)

    def test_failed_wire_validation_does_not_create_publication_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            source, output = directory / "rules.json", directory / "public"
            document = snapshot()
            document["provenance"] = {"padding": "x" * rules.MAX_BUNDLE_BYTES}
            source.write_bytes(rules.encode(document))
            with self.assertRaisesRegex(ValueError, "UTF-8 bytes"):
                rules.build(source, output)
            self.assertFalse(output.exists())

    def test_build_immutable_and_full_replacement(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            source = directory / "rules.json"
            output = directory / "public"
            source.write_bytes(rules.encode(snapshot()))
            manifest = rules.build(source, output)
            first = output / manifest["file"]
            original_bytes = first.read_bytes()
            self.assertEqual(hashlib.sha256(original_bytes).hexdigest(), manifest["sha256"])
            self.assertEqual(manifest, rules.build(source, output))
            changed = snapshot()
            changed["rules"] = []
            source.write_bytes(rules.encode(changed))
            with self.assertRaisesRegex(ValueError, "increment revision"):
                rules.build(source, output)
            changed["revision"] = 2
            source.write_bytes(rules.encode(changed))
            latest = rules.build(source, output)
            self.assertEqual([], rules.read_json(output / latest["file"])["rules"])
            self.assertEqual(original_bytes, first.read_bytes())
            source.write_bytes(rules.encode(snapshot()))
            with self.assertRaisesRegex(ValueError, "backwards"):
                rules.build(source, output)

    def test_extracts_all_files_without_running_or_copying_code(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            entries = []
            for index in range(70):
                path = f"rules/{index:03d}.json"
                document = {"type": "pkg", "pkg": f"com.example.app{index}",
                            "tigger": [r"example\.com", "^.*$"],
                            "script": "never_execute_this()"}
                rules.write_once(directory / path, rules.encode(document))
                entries.append({"path": path, "sha256": "test-record"})
            archive = {"files": entries, "fileCount": 70, "commit": "a" * 40}
            candidate, report = rules.extract_safe_rules(directory, archive, 1)
            self.assertEqual(70, len(candidate["rules"]))
            self.assertEqual(70, report["excludedByReason"]["trigger_not_exact_domain"])
            self.assertNotIn(b"never_execute_this", rules.encode(candidate))
            self.assertNotIn(b"never_execute_this", rules.encode(report))


if __name__ == "__main__":
    unittest.main()
