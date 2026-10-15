"""Socket-free checks of the opt-in SSH fixture's isolation and refusals."""
import os
from pathlib import Path
import subprocess
from types import SimpleNamespace
import unittest

import paramiko
import ssh_fixture as fixture


class SandboxTests(unittest.TestCase):
    def test_isolated_environment_and_private_short_paths(self):
        original_home = os.environ.get("HOME")
        sandbox = fixture.Sandbox(Path("/bin/true"), "android-test", Path("/tmp"))
        root = sandbox.root
        try:
            self.assertEqual(sandbox.env["HOME"], str(root))
            self.assertEqual(sandbox.env["HERDR_CONFIG_PATH"], str(root / "config.toml"))
            self.assertEqual(set(sandbox.env), {
                "PATH", "HOME", "XDG_CONFIG_HOME", "XDG_STATE_HOME", "XDG_DATA_HOME",
                "XDG_CACHE_HOME", "XDG_RUNTIME_DIR", "TMPDIR", "HERDR_CONFIG_PATH",
                "HERDR_SOCKET_PATH", "HERDR_CLIENT_SOCKET_PATH", "SHELL", "TERM", "PS1",
            })
            for key, value in sandbox.env.items():
                if key.startswith(("XDG_", "HERDR_")) or key in ("HOME", "TMPDIR"):
                    self.assertTrue(Path(value).is_relative_to(root))
            self.assertEqual(root.stat().st_mode & 0o777, 0o700)
            self.assertLess(len(os.fsencode(sandbox.socket)), 104)
            self.assertEqual(os.environ.get("HOME"), original_home)
        finally:
            sandbox.close()
        self.assertFalse(root.exists())

    def test_no_default_or_unsafe_session_or_implicit_binary(self):
        for name in ("default", "", "../other", "a;b", "a\nb", "a" * 25):
            with self.subTest(name=name), self.assertRaises(ValueError):
                fixture.Sandbox(Path("/bin/true"), name, Path("/tmp"))
        with self.assertRaises(ValueError):
            fixture.Sandbox(Path("herdr"), "android-test", Path("/tmp"))

    def test_cleanup_reaps_exact_owned_child(self):
        sandbox = fixture.Sandbox(Path("/bin/sleep"), "android-test", Path("/tmp"))
        child = sandbox.spawn(["60"], stdin=subprocess.DEVNULL,
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            self.assertIsNone(child.poll())
        finally:
            sandbox.close()
        self.assertIsNotNone(child.returncode)
        self.assertFalse(sandbox.children)
        self.assertFalse(sandbox.root.exists())
        with self.assertRaises(RuntimeError):
            sandbox.spawn(["1"])


class RequestTests(unittest.TestCase):
    def setUp(self):
        self.commands = fixture.allowed_commands("android-test")
        self.server = fixture.Server(SimpleNamespace(commands=self.commands, password="public-test"))

    def test_exact_commands_only_and_single_exec_per_channel(self):
        self.assertEqual(sorted(self.commands.values()), ["bridge", "probe", "sessions"])
        for index, command in enumerate(self.commands):
            channel = SimpleNamespace(chanid=index)
            self.assertFalse(self.server.check_channel_exec_request(channel, command + b";touch escape"))
            self.assertTrue(self.server.check_channel_exec_request(channel, command))
            self.assertFalse(self.server.check_channel_exec_request(channel, command))
        for command in (b"sh", b"herdr server", b"/bin/sh -c 'echo harmless'", b"sftp"):
            self.assertFalse(self.server.check_channel_exec_request(SimpleNamespace(chanid=99), command))

    def test_other_session_probe_and_bridge_are_refused(self):
        for command, kind in fixture.allowed_commands("other-test").items():
            if kind != "sessions":
                self.assertFalse(self.server.check_channel_exec_request(SimpleNamespace(chanid=99), command))

    def test_no_shell_pty_environment_subsystem_or_forwarding(self):
        channel = SimpleNamespace(chanid=1)
        self.assertEqual(self.server.check_channel_request("session", 1), paramiko.OPEN_SUCCEEDED)
        self.assertNotEqual(self.server.check_channel_request("direct-tcpip", 1), paramiko.OPEN_SUCCEEDED)
        self.assertFalse(self.server.check_channel_shell_request(channel))
        self.assertFalse(self.server.check_channel_pty_request(channel, "xterm", 80, 24, 0, 0, b""))
        self.assertFalse(self.server.check_channel_env_request(channel, b"HOME", b"/tmp/escape"))
        self.assertFalse(self.server.check_channel_subsystem_request(channel, "sftp"))
        self.assertFalse(self.server.check_port_forward_request("127.0.0.1", 8080))
        self.assertNotEqual(self.server.check_channel_direct_tcpip_request(1, ("x", 1), ("y", 2)), paramiko.OPEN_SUCCEEDED)

    def test_only_ephemeral_test_password_auth_is_accepted(self):
        self.assertEqual(self.server.get_allowed_auths("android-test"), "password")
        self.assertEqual(self.server.check_auth_password("android-test", "public-test"), paramiko.AUTH_SUCCESSFUL)
        for username, password in (("other", "public-test"), ("android-test", "wrong"), ("ユーザー", "秘密")):
            self.assertEqual(self.server.check_auth_password(username, password), paramiko.AUTH_FAILED)
        self.assertEqual(self.server.check_auth_none("android-test"), paramiko.AUTH_FAILED)
        self.assertEqual(self.server.check_auth_publickey("android-test", None), paramiko.AUTH_FAILED)


if __name__ == "__main__":
    unittest.main()
