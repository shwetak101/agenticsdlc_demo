#!/usr/bin/env python3
"""Linux-only process identity, private health checks and runtime state."""

import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time
import urllib.request

REVISIONS = {
    "before": "fb497a78441bfe9d112415ceaf20e3328b6fe09f",
    "after": "2dbef84d5c937d447a97492b9565ec0df36c9b41",
}
COOKIES = {"before": "JSESSIONID", "after": "REFUNDOPS_APPROVAL_SESSION"}
REPO = Path(os.environ.get("REFUNDOPS_REPO", "/workspaces/agenticsdlc_demo"))
ROOT = Path(os.environ.get("REFUNDOPS_RUNTIME_ROOT", "/workspaces/.refundops-runtime"))
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def safe_path(path):
    require(path.is_absolute() and path.resolve() == path, f"Refusing noncanonical/symlink path: {path}")


def state_file(name):
    path = ROOT / "state" / name
    safe_path(path)
    return path


def write_json(path, data):
    safe_path(path)
    staging = path.with_suffix(path.suffix + ".new")
    safe_path(staging)
    with staging.open("w", encoding="utf-8") as stream:
        json.dump(data, stream)
    staging.replace(path)


def layout():
    safe_path(REPO)
    safe_path(ROOT)
    require(ROOT != REPO and ROOT not in REPO.parents and REPO not in ROOT.parents,
            "Runtime root must be outside the primary clone.")
    top = subprocess.check_output(["git", "-C", str(REPO), "rev-parse", "--show-toplevel"], text=True).strip()
    require(top == str(REPO), "REFUNDOPS_REPO must identify the repository root.")
    common = subprocess.check_output(
        ["git", "-C", str(REPO), "rev-parse", "--path-format=absolute", "--git-common-dir"], text=True).strip()
    owner = {"repository": str(REPO), "git_common_dir": common}
    if not ROOT.exists():
        ROOT.mkdir(mode=0o700)
        write_json(ROOT / "owner.json", owner)
        (ROOT / ".gitignore").write_text("/state/\n", encoding="utf-8")
    safe_path(ROOT / "owner.json")
    require((ROOT / "owner.json").is_file(), "Unowned runtime directory; refusing to reuse it.")
    require(json.loads((ROOT / "owner.json").read_text()) == owner,
            "Runtime directory belongs to another clone; nothing was changed.")
    safe_path(ROOT / "state")
    (ROOT / "state").mkdir(mode=0o700, exist_ok=True)
    for name in ("lifecycle.lock", "before-build.log", "after-build.log", "before.log", "after.log"):
        state_file(name)


def jar_path(role):
    path = ROOT / role / "target" / "refundops-1.0.0.jar"
    safe_path(path)
    return path


def fingerprint(role):
    jar = jar_path(role)
    require(jar.is_file(), f"Missing application JAR: {jar}")
    return {"revision": REVISIONS[role], "sha256": hashlib.sha256(jar.read_bytes()).hexdigest()}


def build_valid(role):
    path = state_file(f"{role}-build.json")
    return path.exists() and json.loads(path.read_text()) == fingerprint(role)


def ports():
    result = {}
    for role, default in (("before", "8080"), ("after", "8081")):
        value = os.environ.get(f"REFUNDOPS_{role.upper()}_PORT", default)
        require(value.isascii() and value.isdigit() and 1024 <= int(value) <= 65535,
                f"Invalid {role} port; use an integer from 1024 to 65535.")
        result[role] = int(value)
    require(result["before"] != result["after"], "Before and after ports must be distinct.")
    return result


def process_info(pid):
    try:
        proc = Path(f"/proc/{pid}")
        fields = (proc / "stat").read_text().rsplit(")", 1)[1].split()
        if fields[0] == "Z":
            return None
        return {
            "ticks": fields[19],
            "args": (proc / "cmdline").read_bytes().split(b"\0")[:-1],
            "executable": (proc / "exe").resolve().name,
        }
    except (FileNotFoundError, ProcessLookupError):
        return None


def expected_args(role, port):
    return [
        "-Dfile.encoding=UTF-8", f"-Drefundops.runtime.role={role}", "-jar", str(jar_path(role)),
        f"--server.port={port}", "--server.address=127.0.0.1",
        f"--server.servlet.session.cookie.name={COOKIES[role]}",
    ]


def identity_matches(role, record, info):
    return bool(info and info["executable"] == "java" and info["ticks"] == record["ticks"]
                and info["args"][1:] == [part.encode() for part in expected_args(role, record["port"])])


def read_record(role):
    path = state_file(f"{role}.pid.json")
    if not path.exists():
        return None
    record = json.loads(path.read_text())
    require(type(record.get("pid")) is int and record["pid"] > 1
            and type(record.get("port")) is int and 1024 <= record["port"] <= 65535
            and isinstance(record.get("ticks"), str) and record["ticks"].isdigit(),
            f"Invalid PID record for {role}; no process was touched.")
    info = process_info(record["pid"])
    if info is None:
        return None
    require(identity_matches(role, record, info),
            f"{role}: PID identity mismatch; refusing to signal or reuse an unrelated process.")
    return record


def owns_listener(record):
    try:
        inodes = set()
        for fd in Path(f"/proc/{record['pid']}/fd").iterdir():
            try:
                target = os.readlink(fd)
                if target.startswith("socket:["):
                    inodes.add(target[8:-1])
            except FileNotFoundError:
                continue
        for table in ("/proc/net/tcp", "/proc/net/tcp6"):
            for line in Path(table).read_text().splitlines()[1:]:
                fields = line.split()
                if fields[3] == "0A" and int(fields[1].split(":")[1], 16) == record["port"] and fields[9] in inodes:
                    return True
    except (FileNotFoundError, ProcessLookupError):
        pass
    return False


def healthy(role, record):
    if not identity_matches(role, record, process_info(record["pid"])) or not owns_listener(record):
        return False
    try:
        base = f"http://127.0.0.1:{record['port']}"
        with HTTP.open(base + "/api/session", timeout=2) as response:
            data = json.load(response)
            cookies = response.headers.get_all("Set-Cookie", [])
            valid_cookie = any(cookie.startswith(COOKIES[role] + "=") for cookie in cookies)
            if response.status != 200 or not valid_cookie or data.get("authenticated") is not False:
                return False
            if not data.get("csrf", {}).get("token"):
                return False
        with HTTP.open(base + "/", timeout=2) as response:
            return response.status == 200 and b"refundops" in response.read().lower()
    except (OSError, ValueError, KeyError):
        return False


def available(port):
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", port))
    except OSError as error:
        raise RuntimeError(f"Port {port} is occupied; no existing listener will be killed.") from error


def preflight():
    selected = ports()
    timeout()
    for key in ("REFUNDS_DAHNESH_PASSWORD", "REFUNDS_SHWETA_PASSWORD"):
        require(bool(os.environ.get(key, "").strip()), f"{key} must not be blank.")
    for role in REVISIONS:
        record = read_record(role)
        if record:
            require(record["port"] == selected[role], f"Stop {role} before changing its port.")
        else:
            available(selected[role])
    return selected


def timeout():
    value = os.environ.get("REFUNDOPS_START_TIMEOUT", "120")
    require(value.isascii() and value.isdigit() and 1 <= int(value) <= 600,
            "REFUNDOPS_START_TIMEOUT must be 1 to 600 seconds.")
    return int(value)


def stop_one(role):
    record = read_record(role)
    if not record:
        state_file(f"{role}.pid.json").unlink(missing_ok=True)
        return
    # A pidfd pins the original process even if its numeric PID is subsequently reused.
    require(hasattr(os, "pidfd_open") and hasattr(signal, "pidfd_send_signal"),
            "Safe stopping requires Linux pidfd support; no signal was sent.")
    try:
        fd = os.pidfd_open(record["pid"])
    except ProcessLookupError:
        state_file(f"{role}.pid.json").unlink(missing_ok=True)
        return
    try:
        info = process_info(record["pid"])
        if info is not None:
            require(identity_matches(role, record, info), f"{role}: PID changed; refusing to signal.")
            try:
                signal.pidfd_send_signal(fd, signal.SIGTERM)
            except ProcessLookupError:
                pass
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            info = process_info(record["pid"])
            if info is None or info["ticks"] != record["ticks"]:
                state_file(f"{role}.pid.json").unlink(missing_ok=True)
                return
            time.sleep(0.2)
        raise RuntimeError(f"{role} did not stop within 30 seconds; PID record retained. No forced kill.")
    finally:
        os.close(fd)


def start():
    selected = preflight()
    launched = []
    try:
        for role in REVISIONS:
            require(build_valid(role), f"{role}: build is missing or changed; run prepare.sh.")
            record = read_record(role)
            if not record:
                available(selected[role])
                with state_file(f"{role}.log").open("ab", buffering=0) as log:
                    process = subprocess.Popen(
                        ["nohup", "java", *expected_args(role, selected[role])],
                        cwd=ROOT / role, stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                        start_new_session=True, close_fds=True,
                    )
                info = process_info(process.pid)
                require(info is not None, f"{role} exited immediately; inspect its runtime log.")
                record = {"pid": process.pid, "ticks": info["ticks"], "port": selected[role]}
                write_json(state_file(f"{role}.pid.json"), record)
                launched.append(role)
            deadline = time.monotonic() + timeout()
            while time.monotonic() < deadline:
                require(process_info(record["pid"]) is not None,
                        f"{role} exited before readiness; inspect {ROOT}/state/{role}.log")
                if healthy(role, record):
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError(f"{role} readiness timed out; inspect {ROOT}/state/{role}.log")
            print(f"{role.title()} ready: http://127.0.0.1:{record['port']} (private forwarding only)", flush=True)
    except BaseException:
        for role in reversed(launched):
            try:
                stop_one(role)
            except (RuntimeError, OSError) as error:
                print(f"Cleanup: {error}", file=sys.stderr)
        raise


def main():
    action = sys.argv[1]
    role = sys.argv[2] if len(sys.argv) > 2 else None
    if role is not None:
        require(role in REVISIONS, "Unknown runtime role.")
    if action == "layout":
        layout()
    elif action == "tree-path":
        safe_path(ROOT / role)
        safe_path(ROOT / role / ".git")
        safe_path(ROOT / role / "target")
    elif action == "build-valid":
        sys.exit(0 if build_valid(role) else 1)
    elif action == "mark-build":
        write_json(state_file(f"{role}-build.json"), fingerprint(role))
    elif action == "require-stopped":
        require(read_record(role) is None, f"Stop {role} before rebuilding its JAR.")
    elif action == "preflight":
        preflight()
    elif action == "start":
        start()
    elif action == "stop":
        # Inspect both identities before signalling either process.
        for item in REVISIONS:
            read_record(item)
        for item in reversed(REVISIONS):
            stop_one(item)
            print(f"{item.title()} stopped.")
    elif action == "status":
        ok = True
        for item in REVISIONS:
            record = read_record(item)
            ready = bool(record and healthy(item, record))
            print(f"{item.title()}: {'ready' if ready else 'stopped or unhealthy'}")
            ok = ok and ready
        sys.exit(0 if ok else 1)
    else:
        raise RuntimeError("Unknown runtime action.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError) as exc:
        print(f"RefundOps runtime: {exc}", file=sys.stderr)
        sys.exit(1)
