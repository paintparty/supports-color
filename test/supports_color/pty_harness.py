#!/usr/bin/env python3
"""PTY driver for the supports-color batch-0 test harness.

Spawns a child (the Clojure/Babashka scenario runner) either:

  * mode=pty   : as a controlling-terminal child of a fresh pty we own, so
                 `/dev/tty` inside the child resolves to a terminal this process
                 drives. We replay a scripted terminal reply, can force EOF by
                 closing the master, and read the child's ECHO state precisely
                 via termios.tcgetattr(master).

  * mode=notty : in its own session with NO controlling terminal, so any attempt
                 to open /dev/tty inside the child fails. Used for the tty-open
                 failure primitive; also fine for shim-record tests.

Everything in/out is via env vars and plain files (no JSON/EDN parsing here), so
the Clojure side needs no extra deps under either the JVM or bb.

Env (all optional unless noted):
  PTY_MODE            "pty" (default) or "notty"
  PTY_ECHO_FILE       write "1"/"0"/"err" — child ECHO at the DONE sync point
  PTY_STATUS_FILE     write EDN-ish lines: exit code, timed-out flag, elapsed ms
  PTY_DONE_FILE       path the child touches when its action completes
  PTY_GO_FILE         path we touch to release a child blocked on WAIT_GO
  PTY_RESPONSE_HEX    hex bytes to send to the child as the terminal reply
  PTY_WAIT_FOR_QUERY  "1" to read child output before sending the reply
  PTY_WAIT_FOR_HEX    send the reply only once the child's output contains these
                      bytes (hex), for example the query itself. Any earlier
                      output (a launcher's warning) is ignored.
  PTY_RESPONSE_DELAY_MS  wait this long once the reply is due before sending it,
                      as a terminal at the far end of a slow link would
  PTY_RESPONSE2_HEX   a second reply chunk, sent PTY_RESPONSE2_DELAY_MS after the
                      first, for a reply that arrives in two pieces
  PTY_CLOSE_AFTER     "eof" to close the master after replying (forces read -1)
  PTY_TIMEOUT_MS      overall budget before we give up waiting for DONE

The child command is argv after "--".
"""

import os
import sys
import time
import pty
import termios
import fcntl
import select
import signal

# _IO('t', 97) on BSD/macOS; the classic Linux constant otherwise.
TIOCSCTTY = 0x20007461 if sys.platform == "darwin" else 0x540E


def env(k, default=None):
    v = os.environ.get(k)
    return v if v is not None and v != "" else default


def child_argv():
    if "--" not in sys.argv:
        sys.exit("pty_harness: missing '--' before child command")
    return sys.argv[sys.argv.index("--") + 1:]


def read_echo(master_fd):
    """Return True/False for the slave's ECHO bit, or None if unreadable."""
    try:
        attrs = termios.tcgetattr(master_fd)
        return bool(attrs[3] & termios.ECHO)
    except Exception:
        return None


def write_line(path, s):
    if path:
        with open(path, "w") as f:
            f.write(s)


def apply_child_redirects(spec):
    """In the child, after the slave is wired to 0/1/2, repoint selected fds at a
    non-tty (a regular file or /dev/null) so `test -t <fd>` is false there while the
    remaining descriptors stay the controlling pty. `spec` is "fd=target,..." with
    target in {null, file}; used to test the per-descriptor tty predicates."""
    for token in (spec or "").split(","):
        token = token.strip()
        if not token:
            continue
        fd_s, _, target = token.partition("=")
        fd = int(fd_s)
        if target == "null":
            newfd = os.open(os.devnull, os.O_RDWR)
        else:  # "file": a regular file, which is never a terminal
            path = "/tmp/sc-redir-{}-{}".format(os.getpid(), fd)
            if fd == 0:
                open(path, "a").close()
                newfd = os.open(path, os.O_RDONLY)
            else:
                newfd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC)
        os.dup2(newfd, fd)
        if newfd != fd:
            os.close(newfd)


def spawn_child_pty(argv, redir=None):
    """Fork; child gets the slave as controlling tty, then execs argv.
    Returns (pid, master_fd)."""
    master_fd, slave_fd = pty.openpty()
    pid = os.fork()
    if pid == 0:
        # Child: new session, slave becomes controlling terminal, wire stdio.
        os.close(master_fd)
        if hasattr(os, "login_tty"):
            os.login_tty(slave_fd)  # setsid + TIOCSCTTY + dup to 0/1/2 (py>=3.11)
        else:
            # Same as login_tty for py<3.11: setsid, then make the *inherited*
            # slave the controlling terminal via ioctl and dup it to 0/1/2. We do
            # NOT close+reopen the slave — dropping the last slave fd flushes the
            # pending canonical input the parent may have already queued.
            os.setsid()
            fcntl.ioctl(slave_fd, TIOCSCTTY, 0)
            for target in (0, 1, 2):
                os.dup2(slave_fd, target)
            if slave_fd > 2:
                os.close(slave_fd)
        # The controlling terminal is now the slave regardless of what 0/1/2 point
        # at, so redirecting individual fds here only changes `test -t <fd>`.
        apply_child_redirects(redir)
        os.execvp(argv[0], argv)
        os._exit(127)
    os.close(slave_fd)
    return pid, master_fd


def spawn_child_notty(argv):
    """Fork; child gets a new session with NO controlling tty, stdio -> /dev/null.
    Returns (pid, None)."""
    pid = os.fork()
    if pid == 0:
        os.setsid()  # new session, no controlling terminal
        devnull = os.open(os.devnull, os.O_RDWR)
        for fd in (0, 1, 2):
            os.dup2(devnull, fd)
        os.execvp(argv[0], argv)
        os._exit(127)
    return pid, None


def drain(master_fd):
    """Non-blocking read of whatever the child has written to the tty."""
    try:
        r, _, _ = select.select([master_fd], [], [], 0)
        if r:
            return os.read(master_fd, 4096)
    except OSError:
        pass
    return b""


def main():
    if env("PTY_DEBUG"):
        import faulthandler
        faulthandler.dump_traceback_later(int(env("PTY_DEBUG")), exit=True)
    argv = child_argv()
    mode = env("PTY_MODE", "pty")
    done_file = env("PTY_DONE_FILE")
    go_file = env("PTY_GO_FILE")
    echo_file = env("PTY_ECHO_FILE")
    status_file = env("PTY_STATUS_FILE")
    timeout_ms = int(env("PTY_TIMEOUT_MS", "8000"))
    response = bytes.fromhex(env("PTY_RESPONSE_HEX", "") or "")
    wait_for_query = env("PTY_WAIT_FOR_QUERY") == "1"
    close_after = env("PTY_CLOSE_AFTER")
    wait_for_bytes = bytes.fromhex(env("PTY_WAIT_FOR_HEX", "") or "")
    response_delay = int(env("PTY_RESPONSE_DELAY_MS", "0") or "0") / 1000.0
    response2 = bytes.fromhex(env("PTY_RESPONSE2_HEX", "") or "")
    response2_delay = int(env("PTY_RESPONSE2_DELAY_MS", "0") or "0") / 1000.0
    due_at = None
    sent_at = None
    sent2 = False
    seen = b""

    start = time.time()
    if mode == "notty":
        pid, master_fd = spawn_child_notty(argv)
    else:
        pid, master_fd = spawn_child_pty(argv, env("PTY_REDIR"))

    sent = False
    echo = None
    timed_out = False
    exit_code = None
    deadline = start + timeout_ms / 1000.0

    def reap_nohang():
        try:
            wpid, status = os.waitpid(pid, os.WNOHANG)
        except ChildProcessError:
            return True  # already reaped
        if wpid == pid:
            nonlocal exit_code
            exit_code = (os.waitstatus_to_exitcode(status)
                         if hasattr(os, "waitstatus_to_exitcode") else (status >> 8))
            return True
        return False

    while True:
        # Has the child signalled completion, or exited on its own?
        if done_file and os.path.exists(done_file):
            break
        if reap_nohang():
            break
        if time.time() > deadline:
            timed_out = True
            break

        if master_fd is not None:
            data = drain(master_fd)
            seen += data
            ready = (wait_for_bytes in seen) if wait_for_bytes else (data or not wait_for_query)
            if ready and due_at is None:
                due_at = time.time() + response_delay
            if (not sent) and response and due_at is not None and time.time() >= due_at:
                # Writing to the master before the child has (re)opened the slave
                # fails with EIO; keep retrying until the open lands, so a byte is
                # never dropped just because the child booted slowly.
                try:
                    os.write(master_fd, response)
                    sent = True
                    sent_at = time.time()
                except OSError:
                    pass
                if sent and close_after == "eof":
                    time.sleep(0.05)  # let the slave buffer the bytes first
                    try:
                        os.close(master_fd)
                    except OSError:
                        pass
                    master_fd = None
            if (sent and response2 and not sent2 and master_fd is not None
                    and time.time() >= sent_at + response2_delay):
                try:
                    os.write(master_fd, response2)
                    sent2 = True
                except OSError:
                    pass
        time.sleep(0.01)

    # A partial frame with no response can still need an EOF to unblock a reader.
    if (not sent) and close_after == "eof" and master_fd is not None:
        try:
            os.close(master_fd)
        except OSError:
            pass
        master_fd = None

    # Snapshot ECHO while the child (and pty slave) is still alive.
    if master_fd is not None:
        echo = read_echo(master_fd)
    write_line(echo_file, "1" if echo else ("err" if echo is None else "0"))

    # Release a child blocked on WAIT_GO so it can finish and exit.
    if go_file:
        try:
            open(go_file, "w").close()
        except OSError:
            pass

    # Reap while STILL draining the master: a child that flushes more stdout than
    # the pty buffer holds would otherwise block writing to the tty forever, and
    # a blocking waitpid here would deadlock against it.
    def drain_and_reap():
        if master_fd is not None:
            drain(master_fd)
        return reap_nohang()

    reap_deadline = time.time() + 3
    while exit_code is None and time.time() < reap_deadline:
        if drain_and_reap():
            break
        time.sleep(0.01)
    if exit_code is None:
        try:
            os.kill(pid, signal.SIGKILL)
        except OSError:
            pass
        for _ in range(300):  # keep draining while it dies; never block in waitpid
            if drain_and_reap():
                break
            time.sleep(0.01)
        if exit_code is None:
            exit_code = -9

    elapsed_ms = int((time.time() - start) * 1000)
    write_line(status_file,
               ":exit {}\n:timed-out {}\n:elapsed-ms {}\n".format(
                   exit_code, "true" if timed_out else "false", elapsed_ms))
    print("pty_harness done: mode={} exit={} echo={} timed_out={} elapsed_ms={}".format(
        mode, exit_code, echo, timed_out, elapsed_ms), file=sys.stderr)


if __name__ == "__main__":
    main()
