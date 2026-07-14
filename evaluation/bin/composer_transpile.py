#!/usr/bin/env python3
"""Composer 2.5 Cursor SDK transpile helper for Phase 7 evaluation.

Reads SQL from stdin. Renders evaluation/prompts/v1.txt and invokes
Agent.prompt one-shot with model composer-2.5 in an empty temp cwd.

Exit codes: 0 ok, 1 startup failure, 2 run failed / empty output.
"""

from __future__ import annotations

import argparse
import os
import sys
import tempfile
from pathlib import Path


def strip_fences(text: str) -> str:
    if text is None:
        return ""
    t = text.strip()
    if not t.startswith("```"):
        return text
    first_nl = t.find("\n")
    if first_nl < 0:
        return ""
    t = t[first_nl + 1 :]
    fence = t.rfind("```")
    if fence >= 0:
        t = t[:fence]
    return t.strip()


def render_prompt(template: str, src: str, tgt: str, sql: str) -> str:
    return (
        template.replace("{src}", src)
        .replace("{tgt}", tgt)
        .replace("{sql}", sql)
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Composer 2.5 Cursor SDK transpile helper")
    parser.add_argument("--from", dest="source", required=True, help="source dialect")
    parser.add_argument("--to", dest="target", required=True, help="target dialect")
    parser.add_argument("--prompt", required=True, help="absolute path to prompt template")
    args = parser.parse_args(argv)

    key = os.environ.get("CURSOR_API_KEY", "").strip()
    if not key:
        sys.stderr.write("error: composer: missing CURSOR_API_KEY\n")
        return 1

    sql = sys.stdin.read()
    if not sql or not sql.strip():
        sys.stderr.write("error: composer: empty stdin\n")
        return 1

    prompt_path = Path(args.prompt)
    if not prompt_path.is_file():
        sys.stderr.write(f"error: composer: prompt not found: {prompt_path}\n")
        return 1

    try:
        template = prompt_path.read_text(encoding="utf-8")
    except OSError as e:
        sys.stderr.write(f"error: composer: startup: {e}\n")
        return 1

    prompt = render_prompt(template, args.source, args.target, sql)

    try:
        from cursor_sdk import Agent, AgentOptions, CursorAgentError, LocalAgentOptions
    except ImportError as e:
        sys.stderr.write(f"error: composer: startup: {e}\n")
        return 1

    with tempfile.TemporaryDirectory(prefix="composer-eval-") as cwd:
        try:
            local_kwargs: dict = {"cwd": cwd, "setting_sources": []}
            result = Agent.prompt(
                prompt,
                AgentOptions(
                    api_key=key,
                    model="composer-2.5",
                    local=LocalAgentOptions(**local_kwargs),
                ),
            )
        except CursorAgentError as e:
            sys.stderr.write(f"error: composer: startup: {e}\n")
            return 1
        except Exception as e:
            sys.stderr.write(f"error: composer: startup: {e}\n")
            return 1

    if result.status != "finished":
        sys.stderr.write(f"error: composer: run status={result.status}\n")
        return 2

    text = result.result
    if not text or not str(text).strip():
        sys.stderr.write("error: composer: empty model output\n")
        return 2

    out = strip_fences(text)
    if not out.strip():
        sys.stderr.write("error: composer: empty model output\n")
        return 2

    sys.stdout.write(out if out.endswith("\n") else out + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
