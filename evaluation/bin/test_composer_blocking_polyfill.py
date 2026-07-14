"""Unit tests for Windows os.get_blocking polyfill (no Cursor network)."""

from __future__ import annotations

import os
import sys
import unittest
from unittest import mock


class EnsureOsBlockingApiTest(unittest.TestCase):
    def test_polyfill_installs_when_missing(self):
        if sys.platform != "win32":
            self.skipTest("Windows-only polyfill")
        import composer_transpile as ct

        # Simulate pre-3.12 Windows by removing attributes if present.
        original_get = getattr(os, "get_blocking", None)
        original_set = getattr(os, "set_blocking", None)
        try:
            if hasattr(os, "get_blocking"):
                delattr(os, "get_blocking")
            if hasattr(os, "set_blocking"):
                delattr(os, "set_blocking")
            ct.ensure_os_blocking_api()
            self.assertTrue(callable(os.get_blocking))
            self.assertTrue(callable(os.set_blocking))
            # State tracking works even if SetNamedPipeHandleState fails for bad fd
            os.set_blocking(1, False)
            self.assertFalse(os.get_blocking(1))
            os.set_blocking(1, True)
            self.assertTrue(os.get_blocking(1))
        finally:
            if original_get is not None:
                os.get_blocking = original_get
            elif hasattr(os, "get_blocking"):
                delattr(os, "get_blocking")
            if original_set is not None:
                os.set_blocking = original_set
            elif hasattr(os, "set_blocking"):
                delattr(os, "set_blocking")


if __name__ == "__main__":
    unittest.main()
