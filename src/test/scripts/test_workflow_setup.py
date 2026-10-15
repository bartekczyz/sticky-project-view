"""Offline guards for the build, release, and cross-platform UI workflows."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = {
    name: (ROOT / f".github/workflows/{name}.yml").read_text()
    for name in ("build", "release", "run-ui-tests")
}


def jobs(workflow):
    return re.findall(r"^  ([\w-]+):\n(.*?)(?=^  [\w-]+:\n|\Z)", workflow.split("\njobs:\n", 1)[1], re.M | re.S)


class WorkflowSetupTest(unittest.TestCase):
    def test_gradle_jobs_set_up_java_and_gradle(self):
        for workflow, text in WORKFLOWS.items():
            for name, job in jobs(text):
                steps = job.split("    steps:\n", 1)[1]
                command = re.search(r"\./gradlew|\.\\gradlew|run: \$\{\{ matrix\.command \}\}", steps)
                if command is None:
                    continue
                with self.subTest(workflow=workflow, job=name):
                    self.assertRegex(steps, r"uses: actions/setup-java@v\d+")
                    self.assertRegex(steps, r"uses: gradle/actions/setup-gradle@v\d+")
                    self.assertLess(steps.index("uses: gradle/actions/setup-gradle@"), command.start())

    def test_every_job_has_a_timeout(self):
        for workflow, text in WORKFLOWS.items():
            for name, job in jobs(text):
                with self.subTest(workflow=workflow, job=name):
                    self.assertRegex(job, re.compile(r"^    timeout-minutes: \d+$", re.M))

    def test_default_token_permissions_are_read_only(self):
        for workflow, text in WORKFLOWS.items():
            with self.subTest(workflow=workflow):
                self.assertIn("\npermissions:\n  contents: read\n", text)

    def test_artifact_is_an_installable_zip(self):
        self.assertIn("path: ./build/distributions/*.zip", WORKFLOWS["build"])
        self.assertNotIn("Prepare Plugin Artifact", WORKFLOWS["build"])

    def test_coverage_upload_has_a_report_generator(self):
        if "codecov/codecov-action@" in WORKFLOWS["build"]:
            self.assertIn("org.jetbrains.kotlinx.kover", (ROOT / "build.gradle.kts").read_text())

    def test_ui_workflow_cancels_superseded_runs(self):
        self.assertIn("\nconcurrency:\n", WORKFLOWS["run-ui-tests"])
        self.assertIn("cancel-in-progress: true", WORKFLOWS["run-ui-tests"])

    def test_macos_ui_tests_handle_native_network_prompt_and_stop_watcher(self):
        macos = WORKFLOWS["run-ui-tests"].split("- os: macos-latest\n", 1)[1].split("\n    steps:", 1)[0]
        self.assertIn("osascript src/test/scripts/dismiss_macos_network_prompt.applescript", macos)
        self.assertIn("trap", macos)
        self.assertIn("EXIT", macos)
        self.assertIn('./gradlew integrationTest', macos)


if __name__ == "__main__":
    unittest.main(verbosity=2)