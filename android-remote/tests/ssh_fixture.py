#!/usr/bin/env python3
"""Opt-in loopback SSH fixture for the Android client; never a user SSH server.

Mirrors crates/test-support/sandbox.rs: explicitly selected executable, cleared
HOME/XDG environment, short private directory, and exact-child cleanup. It maps
only the current repository's exact probe/bridge/list commands to fixed argv;
remote bytes are never executed as shell code. Its temporary credentials are
public test data, usable only for this disposable loopback fixture.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import signal
import socket
import subprocess
import tempfile
import threading
import time

import paramiko

REPO = Path(__file__).resolve().parents[2]
READY = b"\nherdr-remote-output-ready:1\n"
MAX_OUTPUT = 64 * 1024


def quote(value: str) -> str:
    return "'" + value.replace("'", "'\\''") + "'"


def allowed_commands(session: str) -> dict[bytes, str]:
    """Read literal client templates so the test cannot accept stale patterns."""
    ssh = (REPO / "crates/herdr-client/src/ssh.rs").read_text()
    sessions = (REPO / "crates/herdr-client/src/sessions.rs").read_text()
    candidate = re.search(r'const CANDIDATES: &str = r#"(.*?)"#;', ssh, re.S)
    if candidate is None:
        raise RuntimeError("client candidate template changed; review fixture")
    commands = {}
    for source, function, operation in (
        (ssh, "probe_command", "probe"),
        (ssh, "bridge_command", "bridge"),
        (sessions, "session_list_script", "sessions"),
    ):
        body = re.search(r"\nfn " + function + r"\([^)]*\) -> String \{(.*?)\n\}", source, re.S)
        literal = re.search(r'r#"(.*?)"#', body[1], re.S) if body else None
        if literal is None:
            raise RuntimeError(f"client {function} template changed; review fixture")
        script = literal[1].format(
            CANDIDATES=candidate[1], session=quote(session),
            PROBE_CANDIDATE="herdr-probe:candidate", PROBE_DONE="herdr-probe:done",
        )
        commands[("/bin/sh -c " + quote(script)).encode()] = operation
    return commands


class Sandbox:
    def __init__(self, binary: Path, session: str, parent: Path):
        if not binary.is_absolute() or not binary.is_file() or not os.access(binary, os.X_OK):
            raise ValueError("--herdr must be an explicit absolute executable")
        if session == "default" or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,23}", session):
            raise ValueError("use a short explicit non-default test session")
        if not parent.is_dir():
            raise ValueError("temporary parent must already exist")
        self.binary, self.session = binary.resolve(), session
        self.temp = tempfile.TemporaryDirectory(prefix="hssh-", dir=parent)
        self.root = Path(self.temp.name).resolve()
        self.socket = self.root / "config/herdr/sessions" / session / "herdr-client.sock"
        self.children: set[subprocess.Popen] = set()
        self.lock = threading.Lock()
        self.closed = False
        if len(os.fsencode(self.socket)) >= 104:
            self.temp.cleanup()
            raise ValueError("temporary path too long; use --tmpdir /tmp")
        os.chmod(self.root, 0o700)
        (self.root / "config.toml").write_text(
            'onboarding = false\n[terminal]\ndefault_shell = "/bin/sh"\nshell_mode = "non_login"\n'
        )
        self.env = {
            "PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "HOME": str(self.root),
            "XDG_CONFIG_HOME": str(self.root / "config"),
            "XDG_STATE_HOME": str(self.root / "state"),
            "XDG_DATA_HOME": str(self.root / "data"),
            "XDG_CACHE_HOME": str(self.root / "cache"),
            "XDG_RUNTIME_DIR": str(self.root), "TMPDIR": str(self.root),
            "HERDR_CONFIG_PATH": str(self.root / "config.toml"),
            "HERDR_SOCKET_PATH": str(self.root / "a.sock"),
            "HERDR_CLIENT_SOCKET_PATH": str(self.root / "a-client.sock"),
            "SHELL": "/bin/sh", "TERM": "xterm-256color", "PS1": "ANDROID-TEST> ",
        }

    def spawn(self, args: list[str], **streams) -> subprocess.Popen:
        with self.lock:
            if self.closed:
                raise RuntimeError("sandbox already closed")
            child = subprocess.Popen([str(self.binary), *args], env=self.env,
                                     cwd=self.root, **streams)
            self.children.add(child)
            return child

    def stop_child(self, child: subprocess.Popen):
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=2)
            except subprocess.TimeoutExpired:
                child.kill()
        child.wait(timeout=5)
        for stream in (child.stdin, child.stdout, child.stderr):
            if stream:
                stream.close()
        with self.lock:
            self.children.discard(child)

    def run(self, args: list[str]) -> bytes:
        child = self.spawn(args, stdin=subprocess.DEVNULL,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            stdout, stderr = child.communicate(timeout=10)
            if len(stdout) > MAX_OUTPUT or len(stderr) > MAX_OUTPUT:
                raise RuntimeError("fixture command exceeded output limit")
            if child.returncode:
                raise RuntimeError(f"Herdr {args[0]} failed with status {child.returncode}")
            return stdout.rstrip(b"\n")
        finally:
            self.stop_child(child)

    def start(self):
        with (self.root / "daemon.log").open("wb") as log:
            daemon = self.spawn(["--session", self.session, "server"],
                                stdin=subprocess.DEVNULL, stdout=log, stderr=log)
        deadline = time.monotonic() + 20
        while not self.socket.exists():
            if daemon.poll() is not None:
                raise RuntimeError("isolated Herdr exited: " + (self.root / "daemon.log").read_text()[-4096:])
            if time.monotonic() >= deadline:
                raise TimeoutError("isolated Herdr did not create its named client socket")
            time.sleep(0.02)
        status = json.loads(self.run(["status", "client", "--json"]))
        required = {"surface_interest", "presentation_effects_fence", "health_check"}
        if status.get("endpoint_protocol_generation") != 1 or not required.issubset(status.get("endpoint_capabilities", [])):
            raise RuntimeError("explicit Herdr is not generation-1 compatible")
        return status

    def close(self):
        with self.lock:
            self.closed = True
            children = list(self.children)
        for child in children:
            self.stop_child(child)
        self.temp.cleanup()


class Server(paramiko.ServerInterface):
    def __init__(self, fixture: "Fixture"):
        self.fixture = fixture
        self.operations: dict[int, str] = {}
        self.ready = threading.Condition()

    def get_allowed_auths(self, username):
        return "password"

    def check_auth_password(self, username, password):
        if username == "android-test" and secrets.compare_digest(password.encode(), self.fixture.password.encode()):
            return paramiko.AUTH_SUCCESSFUL
        return paramiko.AUTH_FAILED

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_subsystem_request(self, channel, name):
        return False

    def check_channel_exec_request(self, channel, command):
        operation = self.fixture.commands.get(command)
        with self.ready:
            if operation is None or channel.chanid in self.operations:
                return False
            self.operations[channel.chanid] = operation
            self.ready.notify_all()
        return True


class Fixture:
    def __init__(self, sandbox: Sandbox, port: int):
        self.sandbox = sandbox
        self.commands = allowed_commands(sandbox.session)
        self.password = "public-test-" + secrets.token_urlsafe(12)
        self.key = paramiko.ECDSAKey.generate(bits=256)
        digest = base64.b64encode(hashlib.sha256(self.key.asbytes()).digest()).decode().rstrip("=")
        self.fingerprint = "SHA256:" + digest
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.transports: set[paramiko.Transport] = set()
        self.threads: list[threading.Thread] = []
        self.slots = threading.BoundedSemaphore(16)
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", port))
        self.listener.listen(8)
        self.listener.settimeout(0.2)
        self.port = self.listener.getsockname()[1]

    def metadata(self):
        return {
            "host": "127.0.0.1", "android_host": "10.0.2.2", "port": self.port,
            "username": "android-test", "password": self.password,
            "expected_fingerprint": self.fingerprint, "session": self.sandbox.session,
            "sandbox": str(self.sandbox.root), "pid": os.getpid(),
            "config": {"transport": "tcp", "host": "10.0.2.2", "port": self.port,
                       "username": "android-test", "session": self.sandbox.session,
                       "expected_fingerprint": self.fingerprint},
        }

    def start_thread(self, target, *args):
        thread = threading.Thread(target=target, args=args, daemon=True)
        with self.lock:
            self.threads = [item for item in self.threads if item.is_alive()]
            self.threads.append(thread)
        thread.start()

    def serve(self):
        while not self.stop.is_set():
            try:
                connection, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            if not self.slots.acquire(blocking=False):
                connection.close()
                continue
            self.start_thread(self.connection, connection)

    def connection(self, connection):
        transport = paramiko.Transport(connection)
        transport.add_server_key(self.key)
        server = Server(self)
        with self.lock:
            self.transports.add(transport)
        try:
            transport.start_server(server=server)
            deadline = time.monotonic() + 900
            while transport.is_active() and not self.stop.is_set() and time.monotonic() < deadline:
                channel = transport.accept(timeout=0.2)
                if channel is None:
                    continue
                if not self.slots.acquire(blocking=False):
                    channel.close()
                    continue
                self.start_thread(self.execute, server, channel)
        except (EOFError, OSError, paramiko.SSHException):
            pass
        finally:
            transport.close()
            with self.lock:
                self.transports.discard(transport)
            self.slots.release()

    def execute(self, server, channel):
        try:
            with server.ready:
                server.ready.wait_for(lambda: channel.chanid in server.operations or self.stop.is_set(), timeout=5)
                operation = server.operations.get(channel.chanid)
            if operation is None or self.stop.is_set():
                return
            if operation == "probe":
                client = self.sandbox.run(["status", "client", "--json"])
                status = self.sandbox.run(["--session", self.sandbox.session, "status", "server", "--json"])
                channel.sendall(b"herdr-probe:candidate\n" + client + b"\n" + status + b"\nherdr-probe:done\n")
            elif operation == "sessions":
                channel.sendall(self.sandbox.run(["session", "list", "--json"]) + b"\n")
            elif operation == "bridge":
                self.bridge(channel)
            channel.send_exit_status(0)
        except (OSError, EOFError, RuntimeError, TimeoutError, subprocess.SubprocessError, paramiko.SSHException):
            try:
                channel.send_stderr(b"isolated fixture operation failed\n")
                channel.send_exit_status(1)
            except (OSError, EOFError):
                pass
        finally:
            channel.close()
            self.slots.release()

    def bridge(self, channel):
        channel.sendall(self.sandbox.run(["status", "client", "--json"]) + b"\n" + READY)
        channel.settimeout(10)
        choice = bytearray()
        while len(choice) <= 16:
            byte = channel.recv(1)
            if not byte:
                raise EOFError("bridge negotiation closed")
            choice.extend(byte)
            if byte == b"\n":
                break
        options = {b"accept\n": [], b"accept-idle\n": ["--idle-timeout-v1"]}
        if bytes(choice) not in options:
            raise RuntimeError("unexpected bridge negotiation")
        child = self.sandbox.spawn(
            ["--session", self.sandbox.session, "remote-client-bridge", *options[bytes(choice)]],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        selector = selectors.DefaultSelector()
        channel.settimeout(1)
        for stream in (child.stdout, child.stderr):
            os.set_blocking(stream.fileno(), False)
            selector.register(stream, selectors.EVENT_READ)
        os.set_blocking(child.stdin.fileno(), False)
        pending = bytearray()
        input_closed = False
        try:
            while not self.stop.is_set() and not channel.closed:
                if channel.recv_ready() and len(pending) < MAX_OUTPUT:
                    pending.extend(channel.recv(min(16384, MAX_OUTPUT - len(pending))))
                if pending:
                    try:
                        del pending[:os.write(child.stdin.fileno(), pending)]
                    except BlockingIOError:
                        pass
                if channel.eof_received and not pending and not input_closed:
                    child.stdin.close()
                    input_closed = True
                for key, _ in selector.select(timeout=0.02):
                    data = os.read(key.fd, 16384)
                    if not data:
                        selector.unregister(key.fileobj)
                    elif key.fileobj is child.stdout:
                        channel.sendall(data)
                    else:
                        channel.sendall_stderr(data)
                if child.poll() is not None and not selector.get_map():
                    if child.returncode:
                        raise RuntimeError("isolated Herdr bridge exited unsuccessfully")
                    break
        finally:
            selector.close()
            self.sandbox.stop_child(child)

    def close(self):
        self.stop.set()
        self.listener.close()
        with self.lock:
            transports = list(self.transports)
        for transport in transports:
            transport.close()
        with self.lock:
            threads = list(self.threads)
        for thread in threads:
            thread.join(timeout=3)
        self.sandbox.close()


def self_test(fixture: Fixture):
    connection = socket.create_connection(("127.0.0.1", fixture.port), timeout=5)
    transport = paramiko.Transport(connection)
    transport.connect(hostkey=fixture.key, username="android-test", password=fixture.password)
    try:
        channel = transport.open_session(timeout=5)
        try:
            channel.exec_command("/bin/sh -c 'touch SHOULD_NEVER_EXIST'")
            raise AssertionError("arbitrary exec was accepted")
        except paramiko.SSHException:
            pass
        for operation in ("probe", "sessions"):
            channel = transport.open_session(timeout=5)
            channel.settimeout(10)
            command = next(command for command, kind in fixture.commands.items() if kind == operation)
            channel.exec_command(command)
            output = channel.makefile("rb").read(MAX_OUTPUT)
            assert channel.recv_exit_status() == 0, output
            if operation == "probe":
                assert output.endswith(b"herdr-probe:done\n"), output
                assert b'"running":true' in output.replace(b" ", b""), output
            else:
                assert fixture.sandbox.session.encode() in output, output
            channel.close()
        channel = transport.open_session(timeout=5)
        channel.settimeout(10)
        channel.exec_command(next(command for command, kind in fixture.commands.items() if kind == "bridge"))
        ready = bytearray()
        while not ready.endswith(READY):
            byte = channel.recv(1)
            if not byte:
                raise EOFError("bridge closed before its ready marker")
            ready.extend(byte)
            assert len(ready) <= MAX_OUTPUT
        status = json.loads(bytes(ready[:-len(READY)]))
        assert status["endpoint_protocol_generation"] == 1
        channel.sendall(b"accept-idle\n")
        channel.shutdown_write()
        channel.close()
        assert not (fixture.sandbox.root / "SHOULD_NEVER_EXIST").exists()
        print("SELF_TEST_OK: pinned SSH auth, arbitrary exec refusal, real Herdr probe/list, bridge negotiation", flush=True)
    finally:
        transport.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--herdr", type=Path, required=True)
    parser.add_argument("--session", default="android-test")
    parser.add_argument("--tmpdir", type=Path, default=Path("/tmp"))
    parser.add_argument("--port", type=int, default=2222)
    parser.add_argument("--ready-json", type=Path)
    parser.add_argument("--duration", type=int, default=900)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if not 0 <= args.port <= 65535 or not 1 <= args.duration <= 3600:
        parser.error("port must be 0..65535 and duration must be 1..3600 seconds")
    sandbox = Sandbox(args.herdr, args.session, args.tmpdir)
    fixture = None
    try:
        status = sandbox.start()
        fixture = Fixture(sandbox, args.port)
        metadata = fixture.metadata() | {"herdr_status": status}
        if args.ready_json:
            args.ready_json.write_text(json.dumps(metadata, indent=2) + "\n")
            args.ready_json.chmod(0o600)
        print(json.dumps(metadata), flush=True)
        signal.signal(signal.SIGTERM, lambda *_: fixture.stop.set())
        signal.signal(signal.SIGINT, lambda *_: fixture.stop.set())
        fixture.start_thread(fixture.serve)
        if args.self_test:
            self_test(fixture)
        else:
            fixture.stop.wait(timeout=args.duration)
    finally:
        if fixture:
            fixture.close()
        else:
            sandbox.close()
    assert not sandbox.root.exists(), "sandbox cleanup failed"
    print("CLEANUP_OK: exact owned children reaped; sandbox removed", flush=True)


if __name__ == "__main__":
    main()
