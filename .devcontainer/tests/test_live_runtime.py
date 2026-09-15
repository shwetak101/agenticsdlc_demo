"""Opt-in destructive-to-demo-state checks; use only a disposable validation clone."""

import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import unittest
import urllib.parse
import urllib.request

PROJECT = Path(__file__).resolve().parents[2]
SCRIPTS = PROJECT / ".devcontainer" / "scripts"
ROOT = Path(os.environ.get("REFUNDOPS_RUNTIME_ROOT", "/workspaces/.refundops-runtime"))
REPO = Path(os.environ.get("REFUNDOPS_REPO", "/workspaces/agenticsdlc_demo"))


@unittest.skipUnless(os.environ.get("REFUNDOPS_LIVE_TEST") == "1", "Opt-in disposable Linux clone only")
class LiveRuntimeTests(unittest.TestCase):
    @classmethod
    def run_script(cls, name, success=True, env=None):
        result = subprocess.run(["bash", str(SCRIPTS / name)], text=True, capture_output=True,
                                timeout=360, env=env)
        if success and result.returncode:
            raise AssertionError(result.stdout + result.stderr)
        if not success and not result.returncode:
            raise AssertionError(f"{name} unexpectedly succeeded")
        return result

    @classmethod
    def setUpClass(cls):
        cls.run_script("start.sh")

    @classmethod
    def tearDownClass(cls):
        cls.run_script("stop.sh")

    def records(self):
        return {role: json.loads((ROOT / "state" / f"{role}.pid.json").read_text())
                for role in ("before", "after")}

    def test_actual_browser_login_and_distinct_sessions(self):
        names = {"before": "JSESSIONID", "after": "REFUNDOPS_APPROVAL_SESSION"}
        for role, record in self.records().items():
            cookies = http.cookiejar.CookieJar()
            client = urllib.request.build_opener(
                urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cookies))
            base = f"http://127.0.0.1:{record['port']}"
            with client.open(base + "/api/session", timeout=5) as response:
                session = json.load(response)
            self.assertEqual({cookie.name for cookie in cookies}, {names[role]})
            data = urllib.parse.urlencode({
                "username": "shweta",
                "password": os.environ.get("REFUNDS_SHWETA_PASSWORD", "shweta"),
                session["csrf"]["parameterName"]: session["csrf"]["token"],
            }).encode()
            with client.open(base + "/login", data=data, timeout=5) as response:
                self.assertTrue(json.load(response)["success"])
            with client.open(base + "/api/session", timeout=5) as response:
                self.assertEqual(json.load(response)["user"]["username"], "shweta")
            with client.open(base + "/api/dashboard", timeout=5) as response:
                self.assertEqual(response.status, 200)

    def test_idempotent_start_keeps_both_pids(self):
        before = self.records()
        self.run_script("start.sh")
        self.assertEqual(self.records(), before)
        self.run_script("status.sh")

    def test_modified_detached_worktree_is_preserved_and_refused(self):
        path = ROOT / "before" / "README.txt"
        original = path.read_bytes()
        changed = original + b"\nUser edit must survive.\n"
        try:
            path.write_bytes(changed)
            result = self.run_script("start.sh", success=False)
            self.assertIn("User changes found", result.stderr)
            self.assertEqual(path.read_bytes(), changed)
        finally:
            path.write_bytes(original)
        self.run_script("status.sh")

    def test_primary_clone_user_edits_are_untouched(self):
        path = REPO / "README.txt"
        original = path.read_bytes()
        changed = original + b"\nPrimary clone user edit.\n"
        try:
            path.write_bytes(changed)
            head = subprocess.check_output(["git", "-C", str(REPO), "rev-parse", "HEAD"])
            self.run_script("start.sh")
            self.assertEqual(path.read_bytes(), changed)
            self.assertEqual(head, subprocess.check_output(["git", "-C", str(REPO), "rev-parse", "HEAD"]))
        finally:
            path.write_bytes(original)

    def test_reused_pid_record_does_not_kill_unrelated_process(self):
        path = ROOT / "state" / "before.pid.json"
        original = path.read_bytes()
        record = json.loads(original)
        unrelated = subprocess.Popen(["sleep", "60"])
        record["pid"] = unrelated.pid
        record["ticks"] = Path(f"/proc/{unrelated.pid}/stat").read_text().rsplit(")", 1)[1].split()[19]
        try:
            path.write_text(json.dumps(record))
            result = self.run_script("stop.sh", success=False)
            self.assertIn("identity mismatch", result.stderr)
            self.assertIsNone(unrelated.poll())
        finally:
            path.write_bytes(original)
            if unrelated.poll() is None:
                unrelated.terminate()
                unrelated.wait(timeout=5)
        self.run_script("status.sh")

    def test_stop_restart_and_custom_ports(self):
        self.run_script("stop.sh")
        self.run_script("stop.sh")
        env = dict(os.environ, REFUNDOPS_BEFORE_PORT="18080", REFUNDOPS_AFTER_PORT="18081")
        try:
            self.run_script("start.sh", env=env)
            self.assertEqual({role: record["port"] for role, record in self.records().items()},
                             {"before": 18080, "after": 18081})
            self.run_script("status.sh", env=env)
        finally:
            # Stop reads the recorded ports, not the caller's current environment.
            self.run_script("stop.sh")
            self.run_script("start.sh")


if __name__ == "__main__":
    unittest.main()
