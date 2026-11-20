#!/usr/bin/env python3
"""Compute versioned release tag and categorized changelog for GiBot releases."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Optional

CONVENTIONAL_RE = re.compile(r"^([a-zA-Z]+)(?:\([^)]+\))?!?:\s*(.+)$")
CODEC_REF_RE = re.compile(r"Bedrock_v(\d+)\.CODEC")
MC_VERSION_RE = re.compile(r'\.minecraftVersion\(\s*"([^"]+)"\s*\)')
PROTOCOL_VERSION_RE = re.compile(r"\.protocolVersion\(\s*(\d+)\s*\)")


def normalize_release_base_version(mc_version: str) -> str:
    """Normalize a Bedrock version string (e.g. '1.26.60' -> '26.60', '26.70.3' -> '26.70')."""
    cleaned = (mc_version or "").strip()
    if not cleaned:
        return "0.0"
    if cleaned.startswith("1."):
        cleaned = cleaned[2:]
    parts = cleaned.split(".")
    if len(parts) >= 2:
        return f"{parts[0]}.{parts[1]}"
    return cleaned


def detect_codec_version(repo_root: Path) -> tuple[str, str, int]:
    """Read GiBot.java and its referenced CloudburstMC codec to detect (base, mc_version, protocol_version)."""
    gibot_java = repo_root / "src/main/java/com/ihsanharh/gibot/GiBot.java"
    if not gibot_java.is_file():
        raise FileNotFoundError(f"GiBot.java not found at {gibot_java}")

    gibot_src = gibot_java.read_text(encoding="utf-8")
    codec_match = CODEC_REF_RE.search(gibot_src)
    if not codec_match:
        raise ValueError(f"Could not find Bedrock_v<N>.CODEC reference in {gibot_java}")

    codec_num = codec_match.group(1)
    codec_file = (
        repo_root
        / f"protocol/bedrock-codec/src/main/java/org/cloudburstmc/protocol/bedrock/codec/v{codec_num}/Bedrock_v{codec_num}.java"
    )
    if not codec_file.is_file():
        matches = list(repo_root.glob(f"protocol/**/Bedrock_v{codec_num}.java"))
        if not matches:
            raise FileNotFoundError(f"Codec source Bedrock_v{codec_num}.java not found under {repo_root / 'protocol'}")
        codec_file = matches[0]

    codec_src = codec_file.read_text(encoding="utf-8")
    mc_match = MC_VERSION_RE.search(codec_src)
    proto_match = PROTOCOL_VERSION_RE.search(codec_src)
    if not mc_match or not proto_match:
        raise ValueError(f"Failed to parse minecraftVersion/protocolVersion in {codec_file}")

    mc_version = mc_match.group(1)
    protocol_version = int(proto_match.group(1))
    return normalize_release_base_version(mc_version), mc_version, protocol_version


def is_release_tag(tag: str) -> bool:
    return bool(re.match(r"^v?\d+\.\d+(?:\.\d+)?$", tag.strip()))


def compute_next_tag(base_version: str, existing_tags: list[str]) -> str:
    """Return `<base>` if no tags exist for `base`, else `<base>.<max_rev + 1>`."""
    cleaned_base = base_version.strip().lstrip("v")
    escaped = re.escape(cleaned_base)
    base_re = re.compile(rf"^v?{escaped}$")
    rev_re = re.compile(rf"^v?{escaped}\.(\d+)$")

    revisions: list[int] = []
    for raw_tag in existing_tags:
        tag = raw_tag.strip()
        if base_re.match(tag):
            revisions.append(0)
            continue
        match = rev_re.match(tag)
        if match:
            revisions.append(int(match.group(1)))

    if not revisions:
        return cleaned_base
    return f"{cleaned_base}.{max(revisions) + 1}"


def categorize_commit(subject: str) -> str:
    match = CONVENTIONAL_RE.match(subject.strip())
    if not match:
        return "other"
    commit_type = match.group(1).lower()
    if commit_type == "feat":
        return "features"
    if commit_type == "fix":
        return "fixes"
    if commit_type in ("perf", "refactor"):
        return "improvements"
    return "other"


def format_changelog(
    tag: str,
    mc_version: str,
    protocol_version: str | int,
    commit_sha: str,
    repo: str,
    prev_tag: Optional[str],
    commits: list[tuple[str, str]],
    sha256_hash: Optional[str] = None,
) -> str:
    short_sha = commit_sha[:7] if commit_sha else "unknown"
    lines: list[str] = [
        f"## GiBot {tag}",
        "",
        f"- **Minecraft Bedrock**: `{mc_version}` (Protocol `{protocol_version}`)",
        f"- **Commit**: `{short_sha}`",
    ]
    if sha256_hash:
        lines.append(f"- **Binary SHA-256**: `{sha256_hash}`")

    buckets: dict[str, list[str]] = {
        "features": [],
        "fixes": [],
        "improvements": [],
        "other": [],
    }
    for full_sha, subject in commits:
        clean_subject = subject.strip()
        if not clean_subject:
            continue
        bucket = categorize_commit(clean_subject)
        bullet_sha = full_sha.strip()[:7]
        buckets[bucket].append(f"- {clean_subject} (`{bullet_sha}`)")

    sections = [
        ("Features", buckets["features"]),
        ("Bug Fixes", buckets["fixes"]),
        ("Improvements", buckets["improvements"]),
        ("Other Changes", buckets["other"]),
    ]

    if any(items for _, items in sections):
        for title, items in sections:
            if not items:
                continue
            lines.extend(["", f"### {title}", ""])
            lines.extend(items)
    elif prev_tag:
        lines.extend(["", f"_No code changes since `{prev_tag}`._"])

    if prev_tag and repo:
        lines.extend(["", f"**Full Changelog**: https://github.com/{repo}/compare/{prev_tag}...{tag}"])

    return "\n".join(lines) + "\n"


def run_git(args: list[str], cwd: Optional[Path] = None) -> str:
    res = subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True)
    return res.stdout.strip()


def try_run_git(args: list[str], cwd: Optional[Path] = None) -> Optional[str]:
    res = subprocess.run(["git", *args], cwd=cwd, check=False, capture_output=True, text=True)
    if res.returncode != 0:
        return None
    out = res.stdout.strip()
    return out if out else None


def compute_file_sha256(file_path: Path) -> str:
    digest = hashlib.sha256()
    with file_path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_github_outputs(output_path: Path, values: dict[str, str]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("a", encoding="utf-8") as fh:
        for key, value in values.items():
            fh.write(f"{key}={value}\n")


def parse_commits(prev_tag: Optional[str], cwd: Optional[Path] = None) -> list[tuple[str, str]]:
    if prev_tag:
        raw = try_run_git(["log", f"{prev_tag}..HEAD", "--no-merges", "--pretty=format:%H%x1f%s"], cwd=cwd)
    else:
        raw = try_run_git(["log", "-n", "20", "--no-merges", "--pretty=format:%H%x1f%s"], cwd=cwd)
    if not raw:
        return []
    commits: list[tuple[str, str]] = []
    for line in raw.splitlines():
        if "\x1f" not in line:
            continue
        sha, subject = line.split("\x1f", 1)
        commits.append((sha.strip(), subject.strip()))
    return commits


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Prepare GiBot GitHub release tag and changelog.")
    parser.add_argument("--repo-root", default=".", help="Path to gibot repository root (default: .)")
    parser.add_argument("--base-version", default=None, help="Optional override for release base version")
    parser.add_argument("--mc-version", default=None, help="Optional override for Minecraft Bedrock version")
    parser.add_argument("--protocol-version", default=None, help="Optional override for Bedrock protocol version")
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "ihsanharh/gibot"))
    parser.add_argument("--binary", help="Path to compiled gibot binary for SHA-256 computation")
    parser.add_argument("--notes-out", required=True, help="Output markdown path for release notes")
    parser.add_argument("--sha256-out", help="Output path for `<sha256>  gibot` checksum file")
    parser.add_argument("--github-output", help="Path to $GITHUB_OUTPUT file")
    parser.add_argument("--force", action="store_true", help="Release even if HEAD already has a release tag")
    args = parser.parse_args(argv)

    repo_root = Path(args.repo_root).resolve()

    if args.base_version is None or args.mc_version is None or args.protocol_version is None:
        detected_base, detected_mc, detected_proto = detect_codec_version(repo_root)
    else:
        detected_base, detected_mc, detected_proto = args.base_version, args.mc_version, args.protocol_version

    base_version = args.base_version or detected_base
    mc_version = args.mc_version or detected_mc
    protocol_version = args.protocol_version or detected_proto

    head_tags_raw = try_run_git(["tag", "--points-at", "HEAD"], cwd=repo_root) or ""
    head_release_tags = [t for t in head_tags_raw.splitlines() if t.strip() and is_release_tag(t)]
    if head_release_tags and not args.force:
        print(f"HEAD already tagged with release tag(s): {', '.join(head_release_tags)}; skipping release.")
        if args.github_output:
            write_github_outputs(Path(args.github_output), {"should_release": "false"})
        return 0

    existing_tags_raw = try_run_git(["tag", "-l"], cwd=repo_root) or ""
    existing_tags = [t.strip() for t in existing_tags_raw.splitlines() if t.strip()]
    next_tag = compute_next_tag(base_version, existing_tags)
    prev_tag = try_run_git(["describe", "--tags", "--abbrev=0", "HEAD"], cwd=repo_root)
    commit_sha = run_git(["rev-parse", "HEAD"], cwd=repo_root)
    commits = parse_commits(prev_tag, cwd=repo_root)

    sha256_hash: Optional[str] = None
    if args.binary:
        binary_path = Path(args.binary)
        if not binary_path.is_file():
            print(f"Error: binary file not found at {binary_path}", file=sys.stderr)
            return 1
        sha256_hash = compute_file_sha256(binary_path)
        if args.sha256_out:
            sha_path = Path(args.sha256_out)
            sha_path.parent.mkdir(parents=True, exist_ok=True)
            sha_path.write_text(f"{sha256_hash}  {binary_path.name}\n", encoding="utf-8")

    changelog = format_changelog(
        tag=next_tag,
        mc_version=mc_version,
        protocol_version=protocol_version,
        commit_sha=commit_sha,
        repo=args.repo,
        prev_tag=prev_tag,
        commits=commits,
        sha256_hash=sha256_hash,
    )

    notes_path = Path(args.notes_out)
    notes_path.parent.mkdir(parents=True, exist_ok=True)
    notes_path.write_text(changelog, encoding="utf-8")

    print(
        f"Prepared release {next_tag} (mc={mc_version}, proto={protocol_version}, "
        f"prev={prev_tag or 'none'}, commits={len(commits)})"
    )

    if args.github_output:
        write_github_outputs(
            Path(args.github_output),
            {
                "should_release": "true",
                "tag": next_tag,
                "prev_tag": prev_tag or "",
            },
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
