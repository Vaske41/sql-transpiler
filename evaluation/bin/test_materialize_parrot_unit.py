import json
import tempfile
import unittest
from pathlib import Path
from materialize_parrot import check_against_manifest, materialize_file


class MaterializeTest(unittest.TestCase):
    def test_writes_input_and_target_not_expected(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            jl = root / "pairs.jsonl"
            jl.write_text(
                json.dumps(
                    {
                        "case_id": "00000-smoke-mysql-to-postgresql",
                        "hf_row": 0,
                        "hf_id": "smoke",
                        "source": "mysql",
                        "target": "postgresql",
                        "source_sql": "SELECT 1 AS n",
                        "gold_sql": "SELECT 1 AS n",
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            out = root / "cases"
            n = materialize_file(jl, out)
            self.assertEqual(n, 1)
            case = out / "00000-smoke-mysql-to-postgresql"
            self.assertEqual((case / "input.mysql.sql").read_text(encoding="utf-8"), "SELECT 1 AS n")
            self.assertEqual((case / "target.txt").read_text(encoding="utf-8").strip(), "postgresql")
            self.assertFalse(any(case.glob("expected.*")))
            self.assertFalse(any(case.glob("parrot_gold.*")))

    def test_replace_wipes_orphan_cases(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            out = root / "cases"
            orphan = out / "99999-orphan-mysql-to-postgresql"
            orphan.mkdir(parents=True)
            (orphan / "input.mysql.sql").write_text("SELECT orphan", encoding="utf-8")
            (orphan / "target.txt").write_text("postgresql\n", encoding="utf-8")

            jl = root / "pairs.jsonl"
            jl.write_text(
                json.dumps(
                    {
                        "case_id": "00000-smoke-mysql-to-postgresql",
                        "hf_row": 0,
                        "hf_id": "smoke",
                        "source": "mysql",
                        "target": "postgresql",
                        "source_sql": "SELECT 1 AS n",
                        "gold_sql": "SELECT 1 AS n",
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            n = materialize_file(jl, out)
            self.assertEqual(n, 1)
            self.assertFalse(orphan.exists())
            self.assertTrue((out / "00000-smoke-mysql-to-postgresql" / "input.mysql.sql").is_file())

    def test_manifest_mismatch_is_loud(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            pairs = root / "pairs.jsonl"
            pairs.write_text("{}\n", encoding="utf-8")  # unused; check only
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"pairCount": 2}) + "\n", encoding="utf-8")
            self.assertEqual(check_against_manifest(pairs, 1, manifest), 2)
            self.assertEqual(check_against_manifest(pairs, 2, manifest), 0)
            smoke = root / "pairs.smoke.jsonl"
            self.assertEqual(check_against_manifest(smoke, 99, manifest), 0)


if __name__ == "__main__":
    unittest.main()
