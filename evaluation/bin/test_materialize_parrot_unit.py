import json
import tempfile
import unittest
from pathlib import Path
from materialize_parrot import materialize_file


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


if __name__ == "__main__":
    unittest.main()
