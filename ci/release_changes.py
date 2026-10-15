#!/usr/bin/env python3
"""Markdown list of the commits a mod release adds since the previous published mod release.

    gh api --paginate --slurp repos/OWNER/REPO/releases > releases.json
    python3 ci/release_changes.py --repository OWNER/REPO --head SHA --current-tag TAG \\
        --releases releases.json --output changes.md

Run inside a clone with full history and tags. The previous release is the published release (not
draft, not prerelease) with a mod tag (toe3-v<version>-b<code>-mod-<hex>), other than --current-tag,
whose tag commit in this clone is the nearest ancestor of --head; the API's target_commitish is never
used. Releases of the same or another upstream version count alike. The list holds the non-merge
commits in previous..head that change RELEASE_PATHS (the inputs of a release build, which also name
its tag); without a previous release, those reachable from head. A published mod release whose tag is
missing from this clone is an error, never a reason to pick an older base.

Errors: one line on stderr, exit status 1; nothing is written.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

RELEASE_PATHS = ("work/mod", "ci", "assets/artwork/launcher-icon.png", ".github/workflows/mod-release.yml")
MAX_LISTED = 50

_REPOSITORY = re.compile(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}/(?!\.\.?$)[A-Za-z0-9._-]{1,100}")
_SHA = re.compile(r"[0-9a-f]{40}")
_MOD_TAG = re.compile(r"toe3-v[0-9]+(?:\.[0-9A-Za-z_-]+)*-b[0-9]+-mod-[0-9a-f]{7,40}")
_MARKDOWN = re.compile(r"([\\`*_\[\]<>!|~&@#])")
_CONTROL = re.compile("[\x00-\x1f\x7f-\x9f\u200e\u200f\u202a-\u202e\u2066-\u2069]")


class ChangesError(Exception):
    """Release metadata or git history cannot give a trustworthy change list."""


def _git(repo, args, ok=(0,)):
    try:
        done = subprocess.run(["git", "-C", str(repo), "-c", "log.showSignature=false", *args],
                              capture_output=True, text=True, encoding="utf-8", errors="replace")
    except OSError as e:
        raise ChangesError(f"cannot run git: {e}") from None
    if done.returncode not in ok:
        detail = done.stderr.strip().splitlines()
        raise ChangesError(f"git {args[0]} failed: {detail[-1] if detail else f'exit status {done.returncode}'}")
    return done


def published_mod_tags(releases) -> dict:
    """tag -> published_at of every published stable release with a mod tag.

    releases is the GitHub list-releases response: a list of releases, or of pages of releases."""
    if not isinstance(releases, list):
        raise ChangesError("releases must be a JSON list")
    items = [r for entry in releases for r in (entry if isinstance(entry, list) else [entry])]
    tags = {}
    for r in items:
        if not (isinstance(r, dict) and isinstance(r.get("tag_name"), str)
                and isinstance(r.get("draft"), bool) and isinstance(r.get("prerelease"), bool)):
            raise ChangesError("every release needs a string tag_name and boolean draft and prerelease")
        if r["draft"] or r["prerelease"] or not _MOD_TAG.fullmatch(r["tag_name"]):
            continue
        if not isinstance(r.get("published_at"), str):
            raise ChangesError(f"published release {r['tag_name']} has no published_at")
        tags[r["tag_name"]] = r["published_at"]
    return tags


def previous_release(repo, head, tags, current_tag):
    """(tag, commit) of the published mod release nearest below head, or None."""
    best = None
    for tag, published in tags.items():
        if tag == current_tag:
            continue
        found = _git(repo, ["rev-parse", "--verify", "--quiet", f"refs/tags/{tag}^{{commit}}"], ok=(0, 1))
        commit = found.stdout.strip()
        if found.returncode or not _SHA.fullmatch(commit):
            raise ChangesError(f"published release {tag} has no tag in this clone; fetch all tags")
        if _git(repo, ["merge-base", "--is-ancestor", commit, head], ok=(0, 1)).returncode:
            continue
        distance = int(_git(repo, ["rev-list", "--count", f"{commit}..{head}"]).stdout)
        if best is None or distance < best[2] or (distance == best[2] and published > best[3]):
            best = (tag, commit, distance, published)
    return best[:2] if best else None


def relevant_commits(repo, base, head) -> list:
    """(sha, subject) of each non-merge commit in base..head (or reachable from head) that changes RELEASE_PATHS."""
    out = _git(repo, ["log", "--no-merges", "-z", "--format=%H%n%s", f"{base}..{head}" if base else head,
                      "--", *RELEASE_PATHS]).stdout
    commits = []
    for record in filter(None, out.split("\0")):
        sha, _, subject = record.partition("\n")
        if not _SHA.fullmatch(sha):
            raise ChangesError(f"unexpected git log record {record[:60]!r}")
        commits.append((sha, subject))
    return commits


def escape(text) -> str:
    """text as literal inline Markdown: no markup, HTML, mentions or issue links, no control characters."""
    return _MARKDOWN.sub(r"\\\1", _CONTROL.sub(" ", text))


def render(repository, head, previous, commits, limit=MAX_LISTED) -> str:
    site = f"https://github.com/{repository}"
    if previous:
        tag, base = previous
        more = f"{site}/compare/{base}...{head}"
        since = f" since [`{tag}`]({site}/releases/tag/{tag}) ([compare]({more}))"
    else:
        more = f"{site}/commits/{head}"
        since = ""
        if commits:
            since = " (no earlier published mod release is in this build's history)"
    if not commits:
        return f"No commits changed the mod build inputs{since}.\n"
    lines = [f"Commits that change the mod build inputs{since}:", ""]
    lines += [f"- [`{sha[:7]}`]({site}/commit/{sha}) {escape(subject) or '(no subject)'}"
              for sha, subject in commits[:limit]]
    if len(commits) > limit:
        lines.append(f"- … {len(commits) - limit} older commits: [full list]({more})")
    return "\n".join(lines) + "\n"


def changes(repo, repository, head, current_tag, releases) -> str:
    if not _REPOSITORY.fullmatch(repository):
        raise ChangesError(f"invalid repository {repository!r}")
    if not _SHA.fullmatch(head):
        raise ChangesError(f"head must be a full lowercase commit SHA, got {head!r}")
    if not _MOD_TAG.fullmatch(current_tag):
        raise ChangesError(f"invalid mod release tag {current_tag!r}")
    found = _git(repo, ["rev-parse", "--verify", "--quiet", f"{head}^{{commit}}"], ok=(0, 1)).stdout.strip()
    if found != head:
        raise ChangesError(f"commit {head} is not in this clone")
    previous = previous_release(repo, head, published_mod_tags(releases), current_tag)
    return render(repository, head, previous, relevant_commits(repo, previous and previous[1], head))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repository", required=True, help="OWNER/REPO used in links")
    parser.add_argument("--head", required=True, help="full SHA of the commit being released")
    parser.add_argument("--current-tag", required=True, help="tag of the release being built (never the base)")
    parser.add_argument("--releases", required=True, type=Path, help="GitHub list-releases JSON")
    parser.add_argument("--output", required=True, type=Path, help="Markdown file to write")
    args = parser.parse_args(argv)
    try:
        try:
            releases = json.loads(args.releases.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            raise ChangesError(f"cannot read releases {args.releases}: {e}") from None
        text = changes(Path.cwd(), args.repository, args.head, args.current_tag, releases)
        args.output.write_text(text, encoding="utf-8")
    except (ChangesError, OSError) as e:
        print(f"release_changes: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
