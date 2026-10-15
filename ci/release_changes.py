#!/usr/bin/env python3
"""Release notes of a mod release: its CHANGELOG.md entry and the commits since the previous published release.

    gh api --paginate --slurp repos/OWNER/REPO/releases > releases.json
    python3 ci/release_changes.py --repository OWNER/REPO --head SHA --current-tag TAG \\
        --releases releases.json [--changelog CHANGELOG.md] --output changes.md

Run inside a clone with full history and tags. Mod tags are the semantic vMAJOR.MINOR.PATCH tags of new
releases and the legacy toe3-v<version>-b<code>-mod-<hex> tags of earlier ones. The previous release is
the published release (not draft, not prerelease) with a mod tag, other than --current-tag, whose tag
commit in this clone is the nearest ancestor of --head; the API's target_commitish is never used.
Releases of the same or another upstream version count alike. The commits are the non-merge commits in
previous..head that change RELEASE_PATHS (the inputs of a release build); without a previous release,
those reachable from head. A published mod release whose tag is missing from this clone is an error,
never a reason to pick an older base.

The notes go below the release body's "### Changes in this build" heading, so their headings are level
4 or deeper. With a --changelog section for --current-tag (curated_notes) they are that section followed
by the commit list; otherwise the commits grouped by Conventional Commit type. Without commits they say
that the mod source is unchanged.

resolve_version() gives the release workflow the next semantic version. The published releases, their
update.json and the commits since the previous one decide it; there is no version file in the source.

Errors: one line on stderr, exit status 1; nothing is written.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

import check_apks as ca
import update_manifest as um

RELEASE_PATHS = ("work/mod", "ci", "assets/artwork/launcher-icon.png", ".github/workflows/mod-release.yml")
MAX_LISTED = 50
FIRST_VERSION = (0, 1, 0)

_REPOSITORY = re.compile(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}/(?!\.\.?$)[A-Za-z0-9._-]{1,100}")
_SHA = re.compile(r"[0-9a-f]{40}")
_LEGACY_TAG = re.compile(r"toe3-v[0-9]+(?:\.[0-9A-Za-z_-]+)*-b[0-9]+-mod-[0-9a-f]{7,40}")
_MARKDOWN = re.compile(r"([\\`*_\[\]<>!|~&@#])")
_CONTROL = re.compile("[\x00-\x1f\x7f-\x9f\u200e\u200f\u202a-\u202e\u2066-\u2069]")
_LINE_BREAK = re.compile(r"\r\n|\r|\n")
_HEADING = re.compile(r" {0,3}(#{1,6})(?=[ \t]|$)")
_REFERENCE = re.compile(r" {0,3}\[[^\]]+\]:(?:[ \t]|$)")
_CONVENTIONAL = re.compile(r"([A-Za-z]+)(?:\([^()\r\n]*\))?(!)?: [ \t]*\S")
_BREAKING_FOOTER = re.compile(r"(?m)^BREAKING[ -]CHANGE:")
_MANIFEST_KEYS = {*um.IDENTITY_KEYS, "base_apk", "signing_cert_sha256", "apks"}
_GROUPS = (("breaking", "Breaking changes"), ("feat", "Added"), ("fix", "Fixed"), ("other", "Changed"))


class ChangesError(Exception):
    """Release metadata or git history cannot give a trustworthy change list or version."""


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


def _require_commit(repo, sha):
    if not (isinstance(sha, str) and _SHA.fullmatch(sha)):
        raise ChangesError(f"head must be a full lowercase commit SHA, got {sha!r}")
    if _git(repo, ["rev-parse", "--verify", "--quiet", f"{sha}^{{commit}}"], ok=(0, 1)).stdout.strip() != sha:
        raise ChangesError(f"commit {sha} is not in this clone")


def _tag_commit(repo, tag):
    found = _git(repo, ["rev-parse", "--verify", "--quiet", f"refs/tags/{tag}^{{commit}}"], ok=(0, 1))
    commit = found.stdout.strip()
    return commit if found.returncode == 0 and _SHA.fullmatch(commit) else None


def _is_ancestor(repo, commit, head):
    return _git(repo, ["merge-base", "--is-ancestor", commit, head], ok=(0, 1)).returncode == 0


def is_mod_tag(tag) -> bool:
    """tag is a semantic release tag or a legacy toe3-v<version>-b<code>-mod-<hex> release tag."""
    return isinstance(tag, str) and (um.semantic_version(tag) is not None or _LEGACY_TAG.fullmatch(tag) is not None)


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
        if r["draft"] or r["prerelease"] or not is_mod_tag(r["tag_name"]):
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
        commit = _tag_commit(repo, tag)
        if commit is None:
            raise ChangesError(f"published release {tag} has no tag in this clone; fetch all tags")
        if not _is_ancestor(repo, commit, head):
            continue
        distance = int(_git(repo, ["rev-list", "--count", f"{commit}..{head}"]).stdout)
        if best is None or distance < best[2] or (distance == best[2] and published > best[3]):
            best = (tag, commit, distance, published)
    return best[:2] if best else None


def commit_kind(message) -> str:
    """Conventional Commits kind of a full commit message: "breaking", "feat", "fix" or "other".

    Breaking is a "!" before the header's colon, or a BREAKING CHANGE: / BREAKING-CHANGE: line in the body."""
    header, _, body = message.partition("\n")
    match = _CONVENTIONAL.match(header)
    if (match and match.group(2)) or _BREAKING_FOOTER.search(body):
        return "breaking"
    kind = match.group(1).lower() if match else ""
    return kind if kind in ("feat", "fix") else "other"


def relevant_commits(repo, base, head) -> list:
    """(sha, subject, kind) of each non-merge commit in base..head (or reachable from head) that changes
    RELEASE_PATHS, newest first; kind is the commit_kind of its full message."""
    out = _git(repo, ["log", "--no-merges", "-z", "--format=%H%n%s%n%B", f"{base}..{head}" if base else head,
                      "--", *RELEASE_PATHS]).stdout
    commits = []
    for record in filter(None, out.split("\0")):
        sha, _, rest = record.partition("\n")
        subject, _, message = rest.partition("\n")
        if not _SHA.fullmatch(sha):
            raise ChangesError(f"unexpected git log record {record[:60]!r}")
        commits.append((sha, subject, commit_kind(message)))
    return commits


def _previous(repo, head, previous):
    if not (isinstance(previous, (tuple, list)) and len(previous) == 2 and is_mod_tag(previous[0])
            and isinstance(previous[1], str) and _SHA.fullmatch(previous[1])):
        raise ChangesError(f"previous release must be (mod tag, commit SHA), got {previous!r}")
    tag, commit = previous
    if _tag_commit(repo, tag) != commit or not _is_ancestor(repo, commit, head):
        raise ChangesError(f"tag {tag} is not commit {commit} or that is not an ancestor of {head}")
    return tag, commit


def _previous_identity(manifest, tag, commit) -> dict:
    """Build identity of the update.json published with the semantic release tag at commit."""
    if not isinstance(manifest, dict) or set(manifest) != _MANIFEST_KEYS:
        raise ChangesError(f"update.json of {tag} is missing or lacks the exact schema {um.SCHEMA} keys")
    try:
        identity = um.validate_identity({key: manifest[key] for key in um.IDENTITY_KEYS})
    except um.MetadataError as e:
        raise ChangesError(f"update.json of {tag}: {e}") from None
    if identity["tag"] != tag or identity["mod_revision"] != commit:
        raise ChangesError(f"update.json of {tag} is for {identity['tag']!r} at {identity['mod_revision']}, "
                           f"not {tag} at {commit}")
    return identity


def _release(version) -> dict:
    tag = "v%d.%d.%d" % version
    if um.semantic_version(tag) != version:
        raise ChangesError(f"the next version ({len(tag)} characters) does not fit a release tag")
    return {"build": True, "tag": tag, "mod_version": tag[1:]}


def resolve_version(repo, head, previous, game_name, game_code, previous_manifest) -> dict:
    """{"build", "tag", "mod_version"} of the mod release of head for upstream game_name / game_code.

    previous is previous_release(repo, head, tags, None); previous_manifest is the decoded update.json
    published with it when its tag is semantic, else None. Without a semantic previous release the
    release is FIRST_VERSION. After one, the commits since it that change RELEASE_PATHS increment the
    major (any breaking), minor (any feat) or patch (otherwise) part and reset the lower ones; without such
    commits another game version increments the patch part, and the same game version builds nothing
    (build False with the previous tag)."""
    _require_commit(repo, head)
    if not (isinstance(game_name, str) and um._VERSION_NAME.fullmatch(game_name)):
        raise ChangesError(f"invalid game version name {game_name!r}")
    if not (ca._is_int(game_code) and 0 < game_code <= um.MAX_BUILD):
        raise ChangesError(f"invalid game version code {game_code!r}")
    version = None
    if previous is not None:
        tag, commit = _previous(repo, head, previous)
        version = um.semantic_version(tag)
    if version is None:
        if previous_manifest is not None:
            raise ChangesError("previous_manifest belongs only to a previous release with a semantic tag")
        return _release(FIRST_VERSION)
    identity = _previous_identity(previous_manifest, tag, commit)
    kinds = {kind for _, _, kind in relevant_commits(repo, commit, head)}
    if kinds:
        part = 0 if "breaking" in kinds else 1 if "feat" in kinds else 2
    elif (identity["version_name"], identity["version_code"]) != (game_name, game_code):
        part = 2
    else:
        return {"build": False, "tag": tag, "mod_version": tag[1:]}
    return _release(version[:part] + (version[part] + 1,) + (0,) * (2 - part))


def curated_notes(changelog_text, tag) -> str:
    """The changelog section "## [tag] ..." (or "## tag ...") without its header and link reference
    definitions, every heading one level deeper (### becomes ####, at most ######); "" without one.

    Headings are ATX lines recognised like the in-game reader does (code fences are not special); the
    section ends at the next heading of level 1 or 2."""
    header = re.compile(rf" {{0,3}}##[ \t]+(?:\[{re.escape(tag)}\]|{re.escape(tag)})(?:[ \t].*)?")
    sections, body = [], None
    for line in _LINE_BREAK.split(changelog_text):
        heading = _HEADING.match(line)
        if heading and len(heading.group(1)) <= 2:
            body = [] if header.fullmatch(line) else None
            if body is not None:
                sections.append(body)
        elif body is not None and not _REFERENCE.match(line):
            if heading:
                level = min(len(heading.group(1)) + 1, 6)
                line = line[:heading.start(1)] + "#" * level + line[heading.end(1):]
            body.append(line)
    if len(sections) > 1:
        raise ChangesError(f"the changelog has {len(sections)} sections for {tag}")
    lines = sections[0] if sections else []
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    return "\n".join(lines) + "\n" if lines else ""


def escape(text) -> str:
    """text as literal inline Markdown: no markup, HTML, mentions or issue links, no control characters."""
    return _MARKDOWN.sub(r"\\\1", _CONTROL.sub(" ", text))


def render(repository, head, previous, commits, curated="", limit=MAX_LISTED) -> str:
    """Notes from curated (curated_notes) and commits ((sha, subject, kind), newest first): the curated
    notes followed by the commit list, or without them the commits grouped by kind."""
    site = f"https://github.com/{repository}"
    if previous:
        tag, base = previous
        more = f"{site}/compare/{base}...{head}"
        since = f" since [`{tag}`]({site}/releases/tag/{tag}) ([compare]({more}))"
    else:
        more = f"{site}/commits/{head}"
        since = " (no earlier published mod release is in this build's history)"
    lines = [curated.strip("\n"), "", "#### Source commits", ""] if curated else []
    if not commits:
        lines.append(f"The mod source is unchanged{since}: no commits changed the mod build inputs, so this build "
                     "repackages the same mod source." if previous else "No commits changed the mod build inputs.")
        return "\n".join(lines) + "\n"

    def item(sha, subject):
        return f"- [`{sha[:7]}`]({site}/commit/{sha}) {escape(subject) or '(no subject)'}"

    shown = commits[:limit]
    if curated:
        lines += [f"Commits that change the mod build inputs{since}:", ""]
        lines += [item(sha, subject) for sha, subject, _ in shown]
    else:
        lines.append(f"Generated from the commits that change the mod build inputs{since}.")
        for kind, title in _GROUPS:
            group = [item(sha, subject) for sha, subject, k in shown if k == kind]
            if group:
                lines += ["", f"#### {title}", "", *group]
    if len(commits) > limit:
        lines += ["", f"{len(commits) - limit} older commits are not listed: [full list]({more})"]
    return "\n".join(lines) + "\n"


def changes(repo, repository, head, current_tag, releases, changelog=None) -> str:
    """Notes of the release current_tag at head; changelog is the CHANGELOG.md text, if any."""
    if not _REPOSITORY.fullmatch(repository):
        raise ChangesError(f"invalid repository {repository!r}")
    if not is_mod_tag(current_tag):
        raise ChangesError(f"invalid mod release tag {current_tag!r}")
    _require_commit(repo, head)
    previous = previous_release(repo, head, published_mod_tags(releases), current_tag)
    curated = curated_notes(changelog, current_tag) if changelog is not None else ""
    return render(repository, head, previous, relevant_commits(repo, previous and previous[1], head), curated)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repository", required=True, help="OWNER/REPO used in links")
    parser.add_argument("--head", required=True, help="full SHA of the commit being released")
    parser.add_argument("--current-tag", required=True, help="tag of the release being built (never the base)")
    parser.add_argument("--releases", required=True, type=Path, help="GitHub list-releases JSON")
    parser.add_argument("--changelog", type=Path, help="CHANGELOG.md; its --current-tag section leads the notes")
    parser.add_argument("--output", required=True, type=Path, help="Markdown file to write")
    args = parser.parse_args(argv)
    try:
        try:
            releases = json.loads(args.releases.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            raise ChangesError(f"cannot read releases {args.releases}: {e}") from None
        changelog = None
        if args.changelog is not None:
            try:
                changelog = args.changelog.read_text(encoding="utf-8")
            except (OSError, ValueError) as e:
                raise ChangesError(f"cannot read changelog {args.changelog}: {e}") from None
        text = changes(Path.cwd(), args.repository, args.head, args.current_tag, releases, changelog)
        args.output.write_text(text, encoding="utf-8")
    except (ChangesError, OSError) as e:
        print(f"release_changes: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
