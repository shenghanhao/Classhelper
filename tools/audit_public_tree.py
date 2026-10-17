#!/usr/bin/env python3
"""Read-only checks for the exact Git snapshot intended for public release.

Examples:
    python3 tools/audit_public_tree.py --ref publish --require-root
    python3 tools/audit_public_tree.py --index

Only Git-tracked blobs in the selected snapshot are inspected. The index mode
reads staged blobs, not unstaged working-tree files. A clean result is a useful
release gate, not proof that all personal or copyrighted material is absent.
Binary contents, other refs, commit messages, author identities, embedded image
metadata, and remote URLs need separate human review. Findings never include
matched content, secret values, or absolute repository paths.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


MAX_GIT_FILE_BYTES = 100 * 1024 * 1024

# Deliberately avoid banning natural-language text, public URLs, author email
# addresses, and third-party copyright notices.
CONTENT_RULES = (
    ("private-key-content", re.compile(
        r"-----BEGIN (?:RSA |DSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"
    )),
    ("github-token", re.compile(
        r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"
    )),
    ("api-secret-token", re.compile(
        r"\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}\b"
    )),
    ("slack-token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{20,}\b")),
    ("cloud-access-key", re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    ("google-api-key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("private-chat-identifier", re.compile(r"\boc_[a-fA-F0-9]{32}\b")),
    ("local-user-absolute-path", re.compile(
        r"/" + r"(?:Users|home)/[A-Za-z0-9._-]+(?:/|\b)"
        r"|[A-Za-z]:\\" + r"Users\\[A-Za-z0-9._-]+(?:\\|\b)"
        r"|/" + r"(?:private/)?var/folders/[A-Za-z0-9_/.-]+"
    )),
    ("credential-assignment", re.compile(
        r"(?im)^\s*(?:export\s+)?[\"']?"
        r"[A-Za-z0-9_]*(?:TOKEN|SECRET|PASSWORD|API_KEY|ACCESS_KEY)[A-Za-z0-9_]*"
        r"[\"']?\s*[:=]\s*[\"']?[A-Za-z0-9_+/=.-]{24,}"
    )),
)

LOCAL_DIRECTORIES = {
    ".git", ".gradle", ".kotlin", ".idea", ".vscode", ".asr-models",
    "__pycache__", "node_modules", "build", "out",
}
PRIVATE_DATA_DIRECTORIES = {
    "backup", "backups", "device-backups", "recordings", "audio-recordings",
    "classroom-recordings", "exports", "benchmark-results", "board-photos",
    "transcripts",
}
PRIVATE_FILENAMES = {
    "agents.md", ".ds_store", "local.properties", "signing.properties",
    "keystore.properties", "secrets.properties", "id_rsa", "id_dsa",
    "id_ecdsa", "id_ed25519", "debug.keystore", ".netrc", ".pypirc",
}
AUDIO_VIDEO_SUFFIXES = {
    ".wav", ".m4a", ".aac", ".mp3", ".ogg", ".opus", ".flac", ".pcm",
    ".amr", ".mp4", ".m4v", ".mov", ".3gp", ".mkv", ".avi",
}
DATABASE_SUFFIXES = {".db", ".sqlite", ".sqlite3", ".db-wal", ".db-shm"}
KEY_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"}
BUILD_SUFFIXES = {".apk", ".aab", ".apks", ".iml", ".pyc"}


class AuditError(Exception):
    """Operational failure whose message does not contain Git error output."""


@dataclass(frozen=True)
class Entry:
    path: str
    object_id: str
    object_type: str
    mode: str
    size: int


def git(repo: Path, *arguments: str, input_bytes: bytes | None = None) -> bytes:
    try:
        result = subprocess.run(
            ["git", "-C", str(repo), *arguments],
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except OSError as error:
        raise AuditError("Git could not be executed.") from error
    if result.returncode:
        # Git errors can contain local paths, private ref names, or URLs.
        raise AuditError("A Git read operation failed; no Git error text was printed.")
    return result.stdout


def tree_entries(repo: Path, commit: str) -> list[Entry]:
    entries = []
    for record in git(repo, "ls-tree", "-r", "-l", "-z", commit).split(b"\0"):
        if not record:
            continue
        metadata, raw_path = record.split(b"\t", 1)
        mode, object_type, object_id, size = metadata.decode("ascii").split()
        entries.append(Entry(
            raw_path.decode("utf-8", "surrogateescape"), object_id, object_type,
            mode, int(size) if size != "-" else 0,
        ))
    return entries


def index_entries(repo: Path) -> tuple[list[Entry], set[str]]:
    staged = []
    conflicts = set()
    for record in git(repo, "ls-files", "--stage", "-z").split(b"\0"):
        if not record:
            continue
        metadata, raw_path = record.split(b"\t", 1)
        mode, object_id, stage = metadata.decode("ascii").split()
        path = raw_path.decode("utf-8", "surrogateescape")
        if stage != "0":
            conflicts.add(path)
        else:
            staged.append((path, mode, object_id))

    # Gitlinks may refer to commits absent from the enclosing repository.
    blob_ids = sorted({oid for _, mode, oid in staged if mode != "160000"})
    sizes = {}
    if blob_ids:
        metadata = git(
            repo, "cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)",
            input_bytes=("\n".join(blob_ids) + "\n").encode("ascii"),
        )
        for record in metadata.decode("ascii").splitlines():
            columns = record.split()
            if len(columns) != 3 or columns[1] != "blob":
                raise AuditError("A staged Git blob could not be inspected.")
            sizes[columns[0]] = int(columns[2])

    return [
        Entry(path, oid, "commit" if mode == "160000" else "blob", mode, sizes.get(oid, 0))
        for path, mode, oid in staged
    ], conflicts


def path_categories(path: str) -> set[str]:
    normalized = PurePosixPath(path)
    parts = tuple(part.lower() for part in normalized.parts)
    name = normalized.name.lower()
    suffix = normalized.suffix.lower()
    categories = set()
    if name in PRIVATE_FILENAMES or name.startswith("._"):
        categories.add("private-or-machine-file")
    if any(part in LOCAL_DIRECTORIES for part in parts[:-1]):
        categories.add("local-build-or-ide-directory")
    if any(part in PRIVATE_DATA_DIRECTORIES for part in parts[:-1]):
        categories.add("private-data-or-export-directory")
    if (name == ".env" or name.startswith(".env.")) and name != ".env.example":
        categories.add("environment-secrets-file")
    if suffix in AUDIO_VIDEO_SUFFIXES:
        categories.add("audio-or-video-data")
    if any(name.endswith(ending) for ending in DATABASE_SUFFIXES):
        categories.add("database-data")
    if suffix in KEY_SUFFIXES:
        categories.add("key-or-signing-material")
    if suffix in BUILD_SUFFIXES:
        categories.add("local-build-artifact")
    if suffix == ".ipynb":
        # Notebooks can contain embedded classroom pictures and output text.
        categories.add("notebook-data-needs-explicit-review")
    if name.startswith(("codex-clipboard-", "chatgpt 图像")):
        categories.add("personal-clipboard-image")
    return categories


def audit(repo: Path, entries: list[Entry]) -> tuple[dict[str, set[str]], int]:
    findings = {}
    binary_count = 0
    for entry in entries:
        categories = path_categories(entry.path)
        if entry.object_type != "blob":
            categories.add("submodule-needs-separate-audit")
        elif entry.size > MAX_GIT_FILE_BYTES:
            categories.add("exceeds-github-100-mib-file-limit")
        else:
            try:
                content = git(repo, "cat-file", "blob", entry.object_id)
            except AuditError:
                categories.add("unreadable-git-blob")
            else:
                if b"\0" in content:
                    binary_count += 1
                else:
                    try:
                        text = content.decode("utf-8")
                    except UnicodeDecodeError:
                        binary_count += 1
                    else:
                        for category, pattern in CONTENT_RULES:
                            if pattern.search(text):
                                categories.add(category)
        if categories:
            findings[entry.path] = categories
    return findings, binary_count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--ref", default=None, help="Commit/ref to audit (default: publish).")
    selection.add_argument("--index", action="store_true", help="Audit staged Git blobs, not working files.")
    parser.add_argument("--repo", type=Path, default=Path.cwd(), help="Git repository location.")
    parser.add_argument("--require-root", action="store_true", help="Require a single commit with no parent history.")
    args = parser.parse_args()
    if args.index and args.require_root:
        parser.error("--require-root requires a committed --ref snapshot, not --index.")

    try:
        repo = Path(git(args.repo, "rev-parse", "--show-toplevel").decode().strip())
        history_findings = {}
        if args.index:
            entries, conflicts = index_entries(repo)
            history_findings.update({path: {"unresolved-index-conflict"} for path in conflicts})
        else:
            try:
                commit = git(
                    repo, "rev-parse", "--verify", "--end-of-options",
                    (args.ref or "publish") + "^{commit}",
                ).decode("ascii").strip()
            except AuditError as error:
                raise AuditError("Cannot resolve the release commit; choose --ref or use --index.") from error
            if args.require_root:
                parents = git(repo, "rev-list", "--parents", "-n", "1", commit).split()
                if len(parents) != 1:
                    history_findings["<history>"] = {"release-commit-has-parent-history"}
            entries = tree_entries(repo, commit)

        findings, binary_count = audit(repo, entries)
        for path, categories in history_findings.items():
            findings.setdefault(path, set()).update(categories)
        for path in sorted(findings):
            # JSON-quoted filenames cannot inject control characters into logs.
            print(json.dumps({"path": path, "categories": sorted(findings[path])}, ensure_ascii=True))
        print(json.dumps({
            "result": "FAIL" if findings else "PASS",
            "tracked_entries_checked": len(entries),
            "binary_contents_skipped": binary_count,
            "paths_with_findings": len(findings),
        }))
        return 1 if findings else 0
    except (AuditError, ValueError, UnicodeError) as error:
        # Parsing errors also avoid printing raw Git output or file contents.
        message = str(error) if isinstance(error, AuditError) else "Git snapshot metadata could not be parsed."
        print(json.dumps({"result": "ERROR", "message": message}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
