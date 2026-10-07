#!/usr/bin/env python3
"""Mutation check: break the code on purpose and require the tests to notice.

A test suite that has never failed proves nothing. This script takes a list of plausible bugs, applies them one
at a time to the source, runs the module's tests, and reports whether any test failed ("killed") or the broken
code passed anyway ("survived"). A survivor is either a gap in the tests or an equivalent mutant (one that does
not change behaviour); both need a human decision, so the exit status is non-zero.

    tools/mutate.py <module> <mutants-file> [name-filter]

    JAVA_HOME=/path/to/jdk21+ tools/mutate.py collections tools/mutants/collections.txt
    tools/mutate.py json tools/mutants/json.txt "depth"

Mutants file format: blocks separated by a line containing only "===". Each block is four parts separated by a
line containing only "---": a name, the file path relative to the repository root, the exact text to replace
(it must occur exactly once), and the replacement (may be empty).

Three guards keep the result honest:
  * the unmodified tree is tested first; if it fails, nothing is mutated, because every mutant would then
    look killed;
  * a mutant whose original text is missing or ambiguous is reported INVALID rather than silently skipped;
  * a mutant that fails to compile is INVALID too: a compile error is not a test noticing a bug.

The original file is restored after every mutant, including when the script is interrupted.
"""
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def run_tests(module: str) -> tuple[bool, str]:
    result = subprocess.run(
        ["./mvnw", "-B", "-q", "-pl", module, "test"],
        cwd=ROOT, capture_output=True, text=True,
    )
    return result.returncode == 0, result.stdout + result.stderr


def parse(path: pathlib.Path) -> list[tuple[str, str, str, str]]:
    mutants = []
    for block in re.split(r"\n===\n", path.read_text(encoding="utf-8")):
        if not block.strip():
            continue
        parts = re.split(r"\n---\n|\n---$", block.strip("\n"))
        if len(parts) == 3:  # an empty replacement leaves nothing after the last separator
            parts.append("")
        if len(parts) != 4:
            sys.exit(f"malformed mutant block (expected 4 parts, got {len(parts)}): {block[:80]!r}")
        mutants.append((parts[0].strip(), parts[1].strip(), parts[2], parts[3]))
    return mutants


def first_failure(output: str) -> str:
    for line in output.splitlines():
        if "expected:" in line or "AssertionFailedError" in line or "<<< FAILURE" in line or "<<< ERROR" in line:
            return line.strip()[:110]
    return ""


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    module, mutants_file = sys.argv[1], pathlib.Path(sys.argv[2])
    name_filter = sys.argv[3].lower() if len(sys.argv) > 3 else ""
    mutants = [m for m in parse(mutants_file) if name_filter in m[0].lower()]

    ok, output = run_tests(module)
    if not ok:
        print("The unmodified code does not pass its tests; refusing to mutate.\n" + output[-1500:])
        return 2
    print(f"baseline green; {len(mutants)} mutant(s)\n")

    killed, survived, invalid = [], [], []
    for name, file, old, new in mutants:
        path = ROOT / file
        original = path.read_text(encoding="utf-8")
        if original.count(old) != 1:
            print(f"INVALID   {name}  (original text found {original.count(old)} times, need exactly 1)")
            invalid.append(name)
            continue
        path.write_text(original.replace(old, new), encoding="utf-8")
        try:
            passed, output = run_tests(module)
        finally:
            path.write_text(original, encoding="utf-8")
        if passed:
            print(f"SURVIVED  {name}")
            survived.append(name)
        elif "COMPILATION ERROR" in output or "Compilation failure" in output:
            print(f"INVALID   {name}  (does not compile)")
            invalid.append(name)
        else:
            detail = first_failure(output)
            print(f"killed    {name}" + (f"\n            {detail}" if detail else ""))
            killed.append(name)

    print(f"\n{len(killed)}/{len(mutants)} killed, {len(survived)} survived, {len(invalid)} invalid")
    return 0 if not survived and not invalid else 1


if __name__ == "__main__":
    sys.exit(main())
