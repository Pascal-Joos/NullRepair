#!/usr/bin/env python3
"""
Installation smoke test for NullRepair.

Validates that all components are correctly installed and functional
without making any LLM API calls. Runs in roughly 30 seconds.

Run from the repository root (virtual environment must be active):
    python3 smoke_test.py
"""

import glob
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))


def _find_annotator_jar():
    # The shadow (fat) jar has no classifier; the plain jar is "-nonshadow". Match the version
    # from gradle.properties without hardcoding it so version bumps don't break this script.
    candidates = [
        j for j in glob.glob(os.path.join(
            ROOT, "annotator-core", "build", "libs", "annotator-core-*.jar"))
        if not any(c in j for c in ("nonshadow", "sources", "javadoc"))
    ]
    return candidates[0] if candidates else os.path.join(
        ROOT, "annotator-core", "build", "libs", "annotator-core-1.3.20-SNAPSHOT.jar")


JAR = _find_annotator_jar()
EUREKA = os.path.join(ROOT, "benchmarks", "eureka")


def check(title, passed, detail=None):
    status = "PASS" if passed else "FAIL"
    print(f"  [{status}] {title}")
    if not passed and detail:
        print(f"         {detail}")
    return passed


def step(title):
    print(f"\n{'='*60}")
    print(f"  {title}")
    print(f"{'='*60}")


def run(cmd, timeout=180, extra_env=None):
    env = os.environ.copy()
    if extra_env:
        env.update(extra_env)
    return subprocess.run(cmd, capture_output=True, text=True,
                          timeout=timeout, env=env, cwd=ROOT)


def main():
    failures = []

    step("1/3  Prerequisites")

    # Java
    try:
        r = run(["java", "-version"])
        version_line = (r.stderr or r.stdout).splitlines()[0]
        java_ok = r.returncode == 0
        if not check("Java is available", java_ok, version_line):
            failures.append("java not found on PATH")
        else:
            print(f"         ({version_line.strip()})")
    except FileNotFoundError:
        check("Java is available", False, "java not found on PATH")
        failures.append("java not found on PATH")
        java_ok = False

    # JAR
    jar_ok = os.path.isfile(JAR)
    if not check("NullRepair JAR is built", jar_ok,
                 f"Not found: {os.path.relpath(JAR, ROOT)}"):
        failures.append("NullRepair JAR not found — run: ./gradlew build -x test")

    # Benchmarks
    eureka_ok = os.path.isdir(EUREKA) and bool(os.listdir(EUREKA))
    if not check("Benchmark projects are checked out", eureka_ok,
                 f"Not found: {os.path.relpath(EUREKA, ROOT)}  — run: bash checkout_benchmarks.sh"):
        failures.append("Benchmark projects not checked out")

    step("2/3  Python evaluation packages")

    missing = []
    for module in ("pandas", "numpy", "sklearn"):
        try:
            __import__(module)
        except ImportError:
            missing.append(module)
    py_ok = not missing
    if not check("pandas, numpy, scikit-learn are importable", py_ok,
                 f"Missing: {', '.join(missing)} — activate the venv: source .venv/bin/activate"):
        failures.append(f"Missing Python packages: {', '.join(missing)}")

    step("3/3  NullRepair end-to-end pipeline (no LLM)")

    print("  Running NullRepair on one error of 'eureka' in disabled mode.")
    print("  Exercises: NullAway static analysis, build, annotation injection,")
    print("  and git integration — no API call is made.")
    print()

    if jar_ok and eureka_ok and java_ok:
        cmd = [
            "java", "-jar", JAR,
            "eureka",
            "--mode", "disabled",
            "--selectedErrorIds", "2",
            "--depth", "1",
        ]
        print(f"  $ {' '.join(os.path.relpath(c, ROOT) if c == JAR else c for c in cmd)}")
        print()
        env = os.environ.copy()
        # The API key is read at class-init time; disabled mode never sends it.
        env.setdefault("OPENAI_API_KEY", "smoke-test-dummy")
        collected = []
        timed_out = False
        try:
            with subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                env=env,
                cwd=ROOT,
            ) as proc:
                for line in proc.stdout:
                    collected.append(line)
                    # Print live; \r in progress-bar lines overwrites correctly
                    # on a real terminal.
                    print(line, end="", flush=True)
                proc.wait(timeout=600)
                returncode = proc.returncode
        except subprocess.TimeoutExpired:
            proc.kill()
            timed_out = True
            returncode = -1

        print()
        all_output = "".join(collected)
        if timed_out:
            check("Pipeline runs end-to-end", False, "Timed out after 600 s")
            failures.append("NullRepair pipeline timed out")
        else:
            pipeline_ok = returncode == 0 and "Finished annotating" in all_output
            if not check("Pipeline runs end-to-end", pipeline_ok):
                failures.append("NullRepair pipeline failed")
    else:
        check("Pipeline runs end-to-end", False, "Skipped — prerequisites not met")
        failures.append("NullRepair pipeline skipped due to unmet prerequisites")

    print(f"\n{'='*60}")
    if failures:
        print(f"  SMOKE TEST FAILED  ({len(failures)} issue(s)):")
        for f in failures:
            print(f"    - {f}")
        print(f"{'='*60}\n")
        sys.exit(1)
    print("  SMOKE TEST PASSED — NullRepair is correctly installed.")
    print(f"{'='*60}\n")


if __name__ == "__main__":
    main()
