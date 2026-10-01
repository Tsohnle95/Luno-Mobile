#!/usr/bin/env python3
"""Repository verification without third-party Python dependencies."""

import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
GRADLE_TIMEOUT_SECONDS = 20 * 60


def read(path):
    return path.read_text(encoding="utf-8")


def git(*args):
    return subprocess.check_output(
        ["git", "-C", str(ROOT), *args], text=True, stderr=subprocess.PIPE,
        timeout=15,
    ).strip()


def headings(text):
    """GitHub-style IDs for the simple ATX headings used by repository docs."""
    result, counts = set(), {}
    for heading in re.findall(r"^#{1,6}\s+(.+?)\s*#*\s*$", text, re.MULTILINE):
        slug = re.sub(r"[^\w\- ]", "", heading.lower()).replace(" ", "-")
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        result.add(slug if count == 0 else f"{slug}-{count}")
    return result


def check_markdown(root, documents):
    errors = []
    for document in documents:
        # Examples inside fences are not live navigation links.
        body = re.sub(r"^```[^\n]*\n.*?^```\s*$", "", read(document),
                      flags=re.MULTILINE | re.DOTALL)
        for label, href in re.findall(r"\[([^\]\n]+)\]\(([^)\n]+)\)", body):
            href = href.strip().strip("<>")
            parsed = urlsplit(href)
            if parsed.scheme or parsed.netloc:
                continue
            target = (document.parent / unquote(parsed.path)).resolve() if parsed.path else document
            name = document.relative_to(root)
            if not target.is_relative_to(root) or not target.exists():
                errors.append(f"{name}: missing/outside local target {href}")
                continue
            if re.fullmatch(r"L\d+(?:-L\d+)?", parsed.fragment):
                errors.append(f"{name}: brittle source-line link {href}")
            elif parsed.fragment:
                if target.suffix != ".md" or unquote(parsed.fragment) not in headings(read(target)):
                    errors.append(f"{name}: unsupported/missing fragment {href}")
            # A linked Kotlin declaration name is a checked route, not an inventory.
            if target.suffix == ".kt" and re.fullmatch(r"[A-Za-z_]\w*", label):
                pattern = rf"\b(?:class|object|interface|fun|typealias)\s+{re.escape(label)}\b"
                if not re.search(pattern, read(target)):
                    errors.append(f"{name}: missing routed declaration {label} in {parsed.path}")
    return errors


def check_repository():
    documents = sorted(set(ROOT.glob("*.md")) |
                       set((ROOT / "docs").rglob("*.md")) |
                       set((ROOT / ".agent/tasks").glob("*.md")) |
                       set((ROOT / "contracts").rglob("*.md")))
    errors = check_markdown(ROOT, documents)
    for required in ("AGENTS.md", "project_brain.md", "docs/agent-execution.md",
                     ".agent/tasks/README.md", ".agent/tasks/_TEMPLATE.md"):
        if not (ROOT / required).is_file():
            errors.append(f"missing required infrastructure: {required}")

    database = read(ROOT / "app/src/main/java/com/luno/mobile/data/db/AppDatabase.kt")
    version = int(re.search(r"\bversion\s*=\s*(\d+)", database).group(1))
    schema_dir = ROOT / "app/schemas/com.luno.mobile.data.db.AppDatabase"
    for number in range(1, version + 1):
        schema = schema_dir / f"{number}.json"
        if not schema.is_file():
            errors.append(f"missing historical Room schema: {number}")
        elif json.loads(read(schema))["database"]["version"] != number:
            errors.append(f"Room schema filename/version mismatch: {number}")
    registered = re.search(r"\.addMigrations\((.*?)\)", database, re.DOTALL)
    for number in range(1, version):
        symbol = f"MIGRATION_{number}_{number + 1}"
        if not re.search(rf"\b{symbol}\s*=\s*object\s*:\s*Migration\({number},\s*{number + 1}\)", database):
            errors.append(f"missing explicit Room migration: {symbol}")
        if not registered or symbol not in registered.group(1):
            errors.append(f"unregistered Room migration: {symbol}")

    catalog = read(ROOT / "gradle/libs.versions.toml")
    tag = re.search(r'^newpipeExtractor\s*=\s*"([^"]+)"', catalog, re.MULTILINE).group(1)
    settings = read(ROOT / "settings.gradle.kts")
    if 'includeBuild("vendor/NewPipeExtractor")' not in settings:
        errors.append("missing extractor composite build")
    if tag not in read(ROOT / "AGENTS.md"):
        errors.append("extractor intent differs between catalog and root instructions")
    extractor_build = read(ROOT / "vendor/NewPipeExtractor/build.gradle.kts")
    if f'version = "{tag}"' not in extractor_build:
        errors.append("extractor build version differs from catalog intent")
    compiler_major = re.search(r"JavaLanguageVersion.of\((\d+)\)", extractor_build).group(1)
    stage = git("ls-files", "--stage", "vendor/NewPipeExtractor").split()
    if len(stage) != 4 or stage[0] != "160000" or stage[2] != "0":
        errors.append("extractor must be a tracked, conflict-free gitlink")
    elif git("-C", "vendor/NewPipeExtractor", "rev-parse", "HEAD") != stage[1]:
        errors.append("extractor checkout differs from tracked gitlink; inspect, do not reset")
    # Tags may be absent from shallow clones. Never fetch or mutate during checks.
    try:
        tagged = git("-C", "vendor/NewPipeExtractor", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}")
    except subprocess.CalledProcessError:
        tagged = None
    if tagged and len(stage) == 4 and tagged != stage[1]:
        errors.append("extractor tag differs from tracked gitlink")

    build = read(ROOT / "app/build.gradle.kts")
    java_major = re.search(r"sourceCompatibility\s*=\s*JavaVersion.VERSION_(\d+)", build).group(1)
    if f'jvmTarget = "{java_major}"' not in build:
        errors.append("Kotlin and Java targets differ")
    compile_sdk = re.search(r"compileSdk\s*=\s*(\d+)", build).group(1)
    build_tools = set()
    for filename, flag in (("android-verify.yml", ""), ("android-release.yml", " --release")):
        workflow = read(ROOT / ".github/workflows" / filename)
        versions = re.search(r"java-version:\s*\|\n((?:[ \t]+\d+\n)+)", workflow)
        installed = re.findall(r"\d+", versions.group(1)) if versions else []
        if set(installed) != {compiler_major, java_major} or installed[-1:] != [java_major]:
            errors.append(f"{filename}: provision extractor compiler and app JDK, with app JDK last")
        gate = rf"^\s*(?:run:\s*)?python3 scripts/verify\.py{re.escape(flag)}\s*$"
        if not re.search(gate, workflow, re.MULTILINE):
            errors.append(f"{filename}: missing canonical verification command")
        if "submodules: recursive" not in workflow or "timeout-minutes:" not in workflow:
            errors.append(f"{filename}: missing recursive checkout or job bound")
        if f'"platforms;android-{compile_sdk}"' not in workflow:
            errors.append(f"{filename}: provisioned SDK differs from compileSdk")
        tools = re.search(r'"build-tools;([^\"]+)"', workflow)
        if not tools:
            errors.append(f"{filename}: missing build-tools provisioning")
        else:
            build_tools.add(tools.group(1))
    if len(build_tools) != 1:
        errors.append("debug/release CI build-tools provisioning differs")
    return errors, len(documents), java_major


def run_bounded(command, timeout):
    """Bound the complete Gradle process group, including daemon descendants."""
    process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL,
                               start_new_session=True)
    try:
        return process.wait(timeout=timeout)
    except (subprocess.TimeoutExpired, KeyboardInterrupt) as error:
        print("Verification timed out/interrupted; this is a failure.", file=sys.stderr)
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
        # The group can outlive its leader; always stop any remaining children.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()
        return 124 if isinstance(error, subprocess.TimeoutExpired) else 130


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--docs-only", action="store_true")
    modes.add_argument("--release", action="store_true")
    args = parser.parse_args()
    try:
        errors, count, major = check_repository()
        if errors:
            print("\n".join(errors), file=sys.stderr)
            return 1
        print(f"Documentation/config alignment passed ({count} documents).", flush=True)
        if args.docs_only:
            return 0
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
        result = subprocess.run([java, "-version"], capture_output=True, text=True,
                                timeout=15, check=True)
        match = re.search(r'version "(\d+)', result.stderr + result.stdout)
        if not match or match.group(1) != major:
            print(f"Java {major} required; configure JAVA_HOME before verification.", file=sys.stderr)
            return 1
        tasks = (["verifyReleaseVersion"] if args.release else []) + [
            "testDebugUnitTest", "lintDebug", "assembleRelease" if args.release else "assembleDebug"]
        command = [str(ROOT / "gradlew"), "--no-daemon", "--console=plain"]
        # setup-java exports JAVA_HOME_<major>_<arch>; local users can supply
        # JAVA11_HOME for a compiler outside Gradle's usual discovery locations.
        compiler_env = sorted(key for key in os.environ if
                              re.fullmatch(r"JAVA_HOME_\d+_(?:X64|ARM64)", key) or
                              key == "JAVA11_HOME")
        if compiler_env:
            command.append("-Porg.gradle.java.installations.fromEnv=" + ",".join(compiler_env))
        command.extend(tasks)
        print("Running: " + " ".join(command), flush=True)
        return run_bounded(command, GRADLE_TIMEOUT_SECONDS)
    except (OSError, ValueError, KeyError, AttributeError, subprocess.SubprocessError) as error:
        print(f"Verification could not complete: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
