# Isolated Android SSH fixture

`ssh_fixture.py` is test-only. It uses Paramiko, an explicitly selected Herdr
executable, and the isolation policy in `crates/test-support/sandbox.rs`. No
production source or Herdr implementation is modified.

```sh
python ssh_fixture.py --herdr /absolute/path/to/herdr --port 0 --self-test
```

The socket-free isolation/refusal checks do not start Herdr:

```sh
python -m unittest discover -s android-remote/tests -p 'test_*.py' -v
```

For an emulator session, start the fixture in the **same persistent shell and
network namespace as the emulator**:

```sh
python ssh_fixture.py --herdr /absolute/path/to/herdr \
  --session android-test --port 2222 --duration 900 \
  --ready-json /absolute/task/path/fixture-ready.json &
fixture_pid=$!
# Start the emulator and run Android checks here.
# Stop only this exact fixture process when the test has finished:
kill -TERM "$fixture_pid"
wait "$fixture_pid"
```

Read `fixture-ready.json` for the fresh password and pinned host fingerprint.
The output includes an Android configuration using `10.0.2.2:2222`; the listener
itself binds only `127.0.0.1`. The username is `android-test`. These are temporary
public test credentials, never user credentials. Do not commit generated ready
files or copy their password into a real host profile. The default maximum
fixture lifetime is 15 minutes; SIGTERM and SIGINT also run cleanup.

The fixture starts one explicit non-default named Herdr session inside a fresh
private short `/tmp` directory. It clears inherited environment variables and
sets HOME, XDG, config, socket, temporary, and shell locations to that sandbox.
It neither discovers nor connects to a user's existing daemon or SSH host.

Only exact probe, bridge, and session-list commands produced by the current
client templates are recognized. Their incoming text is never passed to a shell:
each maps to fixed argv for the explicit binary. Unknown exec requests, shell,
PTY, environment, and forwarding requests are refused. The bridge relays the
real generation-1 endpoint bytes and does not emulate Herdr.

All subprocesses are tracked by their `Popen` handles, terminated, and reaped
before sandbox removal. Cleanup never uses daemon-stop discovery, process-name
matching, or process-group killing. The named daemon may spawn its own terminal
children as normal; the fixture first gives that exact daemon SIGTERM so it can
clean them up.

The host must permit filesystem Unix-domain sockets. A runtime that rejects
`socket(AF_UNIX)` with EPERM cannot run this fixture or a real Herdr daemon; use
the environment's normal approved test execution route. Loopback TCP alone is
insufficient. This directory is not an OS-level security sandbox, and terminal
input is only for disposable tests.

## Verification in the cloud workspace, 2026-10-07

- Python byte-compilation passes
- Seven socket-free fixture tests pass, including exact-child termination/reap
- The real-Herdr self-test was attempted but blocked before daemon startup by
  `socket(AF_UNIX)` returning EPERM. A separate normally approved escalation
  probe reported the same host restriction. The fixture therefore has **not**
  passed live SSH/Herdr or Android end-to-end verification here
- No emulator was started as part of this tooling stage
