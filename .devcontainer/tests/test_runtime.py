import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import unittest
from unittest.mock import patch
import uuid

PROJECT = Path(__file__).resolve().parents[2]
SCRIPTS = PROJECT / ".devcontainer" / "scripts"
spec = importlib.util.spec_from_file_location("runtime", SCRIPTS / "runtime.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class ConfigurationTests(unittest.TestCase):
    def test_private_port_scope_and_lifecycle(self):
        config = json.loads((PROJECT / ".devcontainer" / "devcontainer.json").read_text())
        self.assertEqual(config["forwardPorts"], [8080, 8081])
        self.assertEqual(set(config["portsAttributes"]), {"8080", "8081"})
        self.assertEqual(config["portsAttributes"]["8080"]["label"], "Before")
        self.assertEqual(config["portsAttributes"]["8081"]["label"], "After")
        self.assertEqual(config["otherPortsAttributes"]["onAutoForward"], "ignore")
        self.assertEqual(config["hostRequirements"], {"cpus": 4, "memory": "8gb"})
        self.assertEqual(config["workspaceFolder"], "/workspaces/agenticsdlc_demo")
        self.assertTrue(config["postCreateCommand"].endswith("/prepare.sh"))
        self.assertTrue(config["postStartCommand"].endswith("/start.sh"))
        self.assertEqual(config["waitFor"], "postStartCommand")

    def test_java_and_maven_are_in_image(self):
        dockerfile = (PROJECT / ".devcontainer" / "Dockerfile").read_text()
        self.assertIn("FROM maven:3.9.9-eclipse-temurin-21", dockerfile)
        self.assertIn("python3", dockerfile)
        self.assertIn("USER vscode", dockerfile)

    def test_immutable_revisions_agree(self):
        common = (SCRIPTS / "common.sh").read_text()
        self.assertEqual(runtime.REVISIONS, {
            "before": "fb497a78441bfe9d112415ceaf20e3328b6fe09f",
            "after": "2dbef84d5c937d447a97492b9565ec0df36c9b41",
        })
        for revision in runtime.REVISIONS.values():
            self.assertIn(revision, common)

    def test_shell_files_have_unix_line_endings(self):
        for script in SCRIPTS.glob("*.sh"):
            self.assertNotIn(b"\r", script.read_bytes(), str(script))

    @unittest.skipUnless(sys.platform == "linux", "Linux lifecycle")
    def test_bash_syntax(self):
        for script in SCRIPTS.glob("*.sh"):
            subprocess.run(["bash", "-n", str(script)], check=True)


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        # Scratch data stays inside this checkout, never in a system temporary directory.
        self.scratch = PROJECT / ".runtime" / ("unit-" + uuid.uuid4().hex)
        self.scratch.mkdir(parents=True)
        (self.scratch / "state").mkdir()
        self.root_patch = patch.object(runtime, "ROOT", self.scratch)
        self.root_patch.start()

    def tearDown(self):
        self.root_patch.stop()
        shutil.rmtree(self.scratch)

    def record(self):
        return {"pid": 12345, "ticks": "6789", "port": 8080}

    def info(self, role="before"):
        return {"ticks": "6789", "executable": "java",
                "args": [b"java", *[part.encode() for part in runtime.expected_args(role, 8080)]]}

    def test_default_and_override_ports(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(runtime.ports(), {"before": 8080, "after": 8081})
        with patch.dict(os.environ, {"REFUNDOPS_BEFORE_PORT": "18080", "REFUNDOPS_AFTER_PORT": "18081"}):
            self.assertEqual(runtime.ports(), {"before": 18080, "after": 18081})

    def test_invalid_and_duplicate_ports(self):
        for value in ("0", "1023", "65536", "abc", "8081", " 8080", "-1", "８０８０"):
            with patch.dict(os.environ, {"REFUNDOPS_BEFORE_PORT": value, "REFUNDOPS_AFTER_PORT": "8081"}):
                with self.assertRaises(RuntimeError):
                    runtime.ports()

    def test_command_fixes_bind_cookie_and_absolute_jar_without_passwords(self):
        for role in runtime.REVISIONS:
            args = runtime.expected_args(role, 18080)
            self.assertIn("--server.address=127.0.0.1", args)
            self.assertIn(f"--server.servlet.session.cookie.name={runtime.COOKIES[role]}", args)
            self.assertEqual(args[args.index("-jar") + 1], str(runtime.jar_path(role)))
            self.assertFalse(any("PASSWORD" in arg for arg in args))

    def test_pid_reuse_or_wrong_jar_is_not_owned(self):
        self.assertTrue(runtime.identity_matches("before", self.record(), self.info()))
        changed = self.info()
        changed["ticks"] = "99999"
        self.assertFalse(runtime.identity_matches("before", self.record(), changed))
        self.assertFalse(runtime.identity_matches("before", self.record(), self.info("after")))
        changed = self.info()
        changed["executable"] = "python3"
        self.assertFalse(runtime.identity_matches("before", self.record(), changed))

    def test_unrelated_pid_is_never_signalled(self):
        runtime.write_json(runtime.state_file("before.pid.json"), self.record())
        with patch.object(runtime, "process_info", return_value=self.info("after")):
            with patch.object(runtime.signal, "pidfd_send_signal", create=True) as send:
                with self.assertRaisesRegex(RuntimeError, "identity mismatch"):
                    runtime.stop_one("before")
                send.assert_not_called()
        self.assertTrue(runtime.state_file("before.pid.json").exists())

    def test_stale_record_can_be_cleared_without_signal(self):
        runtime.write_json(runtime.state_file("before.pid.json"), self.record())
        with patch.object(runtime, "process_info", return_value=None):
            with patch.object(runtime.signal, "pidfd_send_signal", create=True) as send:
                runtime.stop_one("before")
                send.assert_not_called()
        self.assertFalse(runtime.state_file("before.pid.json").exists())

    def test_pid_identity_is_rechecked_after_opening_pidfd(self):
        runtime.write_json(runtime.state_file("before.pid.json"), self.record())
        with patch.object(runtime, "process_info", side_effect=[self.info(), self.info("after")]):
            with patch.object(runtime.os, "pidfd_open", create=True, return_value=42):
                with patch.object(runtime.os, "close"), patch.object(runtime.signal, "pidfd_send_signal", create=True) as send:
                    with self.assertRaisesRegex(RuntimeError, "PID changed"):
                        runtime.stop_one("before")
                    send.assert_not_called()

    def test_existing_listener_is_not_reused(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            with self.assertRaisesRegex(RuntimeError, "occupied"):
                runtime.available(listener.getsockname()[1])

    def test_readiness_requires_our_listener(self):
        with patch.object(runtime, "process_info", return_value=self.info()):
            with patch.object(runtime, "owns_listener", return_value=False):
                with patch.object(runtime.HTTP, "open") as request:
                    self.assertFalse(runtime.healthy("before", self.record()))
                    request.assert_not_called()

    def test_jar_digest_detects_changed_build(self):
        jar = runtime.jar_path("before")
        jar.parent.mkdir(parents=True)
        jar.write_bytes(b"initial jar")
        runtime.write_json(runtime.state_file("before-build.json"), runtime.fingerprint("before"))
        self.assertTrue(runtime.build_valid("before"))
        jar.write_bytes(b"changed jar")
        self.assertFalse(runtime.build_valid("before"))

    def test_blank_credentials_fail_without_echoing_values(self):
        with patch.dict(os.environ, {"REFUNDS_DAHNESH_PASSWORD": " ", "REFUNDS_SHWETA_PASSWORD": "private-value"}):
            with self.assertRaisesRegex(RuntimeError, "REFUNDS_DAHNESH_PASSWORD must not be blank"):
                runtime.preflight()

    @unittest.skipUnless(sys.platform == "linux", "Linux symlink permissions")
    def test_symlinked_state_is_refused(self):
        (self.scratch / "state" / "before.pid.json").symlink_to(self.scratch / "elsewhere")
        with self.assertRaisesRegex(RuntimeError, "symlink"):
            runtime.state_file("before.pid.json")


if __name__ == "__main__":
    unittest.main()
