import unittest
from fetch_parrot import iter_pairs, normalize_dialect, FILTER_VERSION


class FetchParrotUnitTest(unittest.TestCase):
    def test_normalize(self):
        self.assertEqual(normalize_dialect("postgres"), "postgresql")
        self.assertEqual(normalize_dialect("mysql"), "mysql")
        self.assertEqual(normalize_dialect("tsql"), "tsql")
        self.assertIsNone(normalize_dialect("oracle"))

    def test_iter_pairs_both_directions(self):
        row = {
            "id": "FIBEN",
            "norm": "SELECT 1",
            "mysql": "SELECT 1",
            "postgres": "SELECT 1",
            "tsql": None,
        }
        pairs = iter_pairs(row, 42)
        keys = {(p["source"], p["target"]) for p in pairs}
        self.assertEqual(
            keys,
            {("mysql", "postgresql"), ("postgresql", "mysql")},
        )
        self.assertTrue(all(p["case_id"].startswith("00042-") for p in pairs))
        self.assertEqual(pairs[0]["gold_sql"], "SELECT 1")

    def test_three_dialects_six_directed_edges(self):
        """C3: same-row parallel columns → full ordered pairs among OUR dialects."""
        row = {
            "id": "FIBEN",
            "norm": "SELECT 1",
            "mysql": "SELECT 1 /*mysql*/",
            "postgres": "SELECT 1 /*pg*/",
            "tsql": "SELECT 1 /*tsql*/",
        }
        pairs = iter_pairs(row, 7)
        self.assertEqual(len(pairs), 6)
        self.assertEqual(FILTER_VERSION, "diverse-dialect-v1")


if __name__ == "__main__":
    unittest.main()
