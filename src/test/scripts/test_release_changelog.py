"""Offline release guards and isolated Gradle change-note fixtures.

Run with JAVA_HOME set to Java 21: python3 src/test/scripts/test_release_changelog.py
No publication, upload, push, or PR commands are executed.
"""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = (ROOT / ".github/workflows/release.yml").read_text()


def run(*args, cwd, **kwargs):
    try:
        return subprocess.run(args, cwd=cwd, check=True, text=True, capture_output=True, **kwargs).stdout
    except subprocess.CalledProcessError as error:
        raise AssertionError(f"Command failed ({error.returncode}): {args}\n{error.stdout}\n{error.stderr}") from error


def step(name):
    return WORKFLOW.split(f"      - name: {name}\n", 1)[1].split("\n      - name:", 1)[0]


def shell(block):
    return "\n".join(line[10:] for line in block.split("        run: |\n", 1)[1].splitlines()
                     if line.startswith("          "))


class ReleaseChangelogTest(unittest.TestCase):
    def test_file_scoped_detection_and_commit(self):
        detection = step("Detect Changelog Changes")
        pull_request = step("Create Pull Request")
        self.assertIn("id: changelog", detection)
        self.assertIn("if: ${{ steps.changelog.outputs.changed == 'true' }}", pull_request)
        self.assertLess(WORKFLOW.index("- name: Publish Plugin"), WORKFLOW.index("- name: Detect Changelog Changes"))
        commit_script = shell(pull_request)
        # Stop before the first remote operation; execute the real staging/commit commands only.
        local_commit = commit_script[commit_script.index("git add -- CHANGELOG.md"):commit_script.index("git push")]
        self.assertIn('git commit -m "Changelog update - $VERSION" -- CHANGELOG.md', local_commit)
        with tempfile.TemporaryDirectory(dir=ROOT / "build", prefix="release-guard-") as directory:
            repo = Path(directory)
            run("git", "init", "-q", cwd=repo)
            for name in ("CHANGELOG.md", "unrelated.txt"):
                (repo / name).write_text("original\n")
            run("git", "add", "--", "CHANGELOG.md", "unrelated.txt", cwd=repo)
            run("git", "commit", "-qm", "Fixture", cwd=repo)
            output = repo / "output"
            env = dict(os.environ, GITHUB_OUTPUT=str(output), VERSION="fixture")

            def detect(expected):
                output.write_text("")
                run("bash", "-e", "-c", shell(detection), cwd=repo, env=env)
                self.assertEqual(f"changed={expected}\n", output.read_text())

            detect("false")
            (repo / "unrelated.txt").write_text("unrelated staged change\n")
            run("git", "add", "--", "unrelated.txt", cwd=repo)
            detect("false")
            before = run("git", "rev-parse", "HEAD", cwd=repo)
            run("bash", "-e", "-c", local_commit, cwd=repo, env=env)
            self.assertEqual(before, run("git", "rev-parse", "HEAD", cwd=repo))
            (repo / "CHANGELOG.md").write_text("released notes\n")
            detect("true")
            run("git", "add", "--", "CHANGELOG.md", cwd=repo)
            detect("true")
            run("bash", "-e", "-c", local_commit, cwd=repo, env=env)
            self.assertEqual("CHANGELOG.md\n", run("git", "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD", cwd=repo))
            self.assertEqual("unrelated.txt\n", run("git", "diff", "--cached", "--name-only", cwd=repo))
            detect("false")

    def test_released_and_unreleased_packaged_notes(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "build", prefix="release-notes-") as directory:
            fixture = Path(directory)
            for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "CHANGELOG.md"):
                shutil.copy2(ROOT / name, fixture / name)
            shutil.copytree(ROOT / "gradle", fixture / "gradle")
            shutil.copytree(ROOT / "src/main/resources", fixture / "src/main/resources")
            version = re.search(r"^version\s*=\s*(.+)$", (fixture / "gradle.properties").read_text(), re.M).group(1)
            for requested, present, absent in (
                (version, "Click-to-reveal navigation for sticky items.", "Use the actual plugin version"),
                ("unreleased-fixture", "Use the actual plugin version", "Click-to-reveal navigation for sticky items."),
            ):
                result = run(str(ROOT / "gradlew"), "-p", str(fixture), "--offline", "patchPluginXml",
                             f"-Pversion={requested}",
                             f"-Porg.jetbrains.intellij.platform.intellijPlatformCache={ROOT / '.intellijPlatform'}",
                             cwd=ROOT, timeout=120)
                self.assertIn("BUILD SUCCESSFUL", result)
                descriptor = ET.parse(fixture / "build/tmp/patchPluginXml/plugin.xml").getroot()
                self.assertEqual(requested, descriptor.findtext("version"))
                notes = descriptor.findtext("change-notes")
                self.assertIn(present, notes)
                self.assertNotIn(absent, notes)
                for category in ("Added", "Changed", "Fixed"):
                    self.assertIn(f"<h3>{category}</h3>", notes)
                self.assertTrue(notes.startswith("<p><em>Sticky Project View is free and sponsor-supported."))
                self.assertEqual(1, notes.count("https://github.com/sponsors/hsz"))


if __name__ == "__main__":
    (ROOT / "build").mkdir(exist_ok=True)
    unittest.main(verbosity=2)