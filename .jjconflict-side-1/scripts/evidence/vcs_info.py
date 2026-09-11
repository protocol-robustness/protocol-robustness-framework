"""VCS-agnostic helpers supporting jj (Jujutsu) and Git.
   Tries jj first, falls back to git."""

import subprocess
import shutil


def _run(cmd, *args, **kwargs):
    try:
        return subprocess.check_output([cmd] + list(args),
                                       text=True,
                                       stderr=subprocess.DEVNULL,
                                       **kwargs).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None


def commit_sha():
    """Full commit SHA from jj or git HEAD."""
    return (_run("jj", "log", "-r", "@", "--no-graph", "-T", "commit_id")
            or _run("git", "rev-parse", "HEAD"))


def short_sha(length=12):
    """Short commit SHA from jj or git HEAD."""
    s = (_run("jj", "log", "-r", "@", "--no-graph", "-T", f"commit_id.shortest({length})")
         or _run("git", "rev-parse", "--short", "HEAD"))
    if not s:
        return None
    return s.splitlines()[-1].strip() or None


def commit_message():
    """First line of current commit message from jj or git."""
    desc = _run("jj", "log", "-r", "@", "--no-graph", "-T", "description")
    if desc and desc.strip():
        return desc.splitlines()[0].strip()
    return _run("git", "log", "-1", "--format=%s")


def branch():
    """Active bookmark (jj) or branch (git) name.
    For jj, returns bookmarks on the current change; falls back
    to the change ID prefix, then git branch."""
    bm = _run("jj", "bookmark", "list", "-r", "@", "-T", "name")
    if bm:
        for line in bm.splitlines():
            line = line.strip()
            if line:
                return line
    cid = _run("jj", "log", "-r", "@", "--no-graph", "-T", "change_id.shortest(8)")
    if cid:
        return cid.strip().splitlines()[-1].strip() or None
    return _run("git", "rev-parse", "--abbrev-ref", "HEAD")


def root():
    """Repository root directory."""
    return (_run("jj", "root")
            or _run("git", "rev-parse", "--show-toplevel"))


def repo_is_dirty():
    """True when the working copy has uncommitted changes."""
    s = (_run("jj", "status", "--color", "never")
         or _run("git", "status", "--short"))
    return bool(s) and "The working copy is clean" not in (s or "")
