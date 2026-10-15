"""Release change-list boundaries on temporary git repositories."""

import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import release_changes as rc

SPECIALS = set("\\`*_[]<>!|~&@#")


def release(tag, draft=False, prerelease=False, published_at="2026-01-01T00:00:00Z"):
    return {"tag_name": tag, "draft": draft, "prerelease": prerelease,
            "published_at": None if draft else published_at}


def listed(text):
    return re.findall(r"/commit/([0-9a-f]{40})\)", text)


class Repo:
    def __init__(self, path):
        self.path = path
        self.git("init", "-q", "-b", "main")

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.path), *args], check=True, capture_output=True,
                              text=True).stdout.strip()

    def commit(self, path, message):
        file = self.path / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(message)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def tag(self, sha):
        name = f"toe3-v1.0-b10-mod-{sha[:7]}"
        self.git("tag", name, sha)
        return name


class ChangesTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        config = Path(tmp.name) / "gitconfig"
        config.write_text("")
        env = patch.dict(os.environ, {"GIT_CONFIG_GLOBAL": str(config), "GIT_CONFIG_NOSYSTEM": "1",
                                      "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.com",
                                      "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.com"})
        env.start()
        self.addCleanup(env.stop)
        (Path(tmp.name) / "repo").mkdir()
        self.repo = Repo(Path(tmp.name) / "repo")

    def changes(self, head, releases, current=None):
        return rc.changes(self.repo.path, "owner/repo", head, current or f"toe3-v1.0-b10-mod-{head[:7]}", releases)

    def test_same_upstream_rebuild_lists_only_build_input_commits_since_previous_release(self):
        base = self.repo.commit("ci/build.py", "ci: first build")
        previous = self.repo.tag(base)
        self.repo.commit("README.md", "docs: readme only")
        native = self.repo.commit("work/mod/src/x.cpp", "feat: native change")
        self.repo.commit("CHANGELOG.md", "docs: changelog only")
        self.repo.commit("assets/artwork/mod-hero.webp", "art: promotional hero only")
        icon = self.repo.commit("assets/artwork/launcher-icon.png", "art: launcher icon")
        head = self.repo.commit("ci/release.py", "ci: release script")
        current = f"toe3-v1.0-b10-mod-{head[:7]}"
        text = self.changes(head, [[release(previous), release(current, draft=True)]], current)
        self.assertEqual(listed(text), [head, icon, native])
        self.assertIn(f"/compare/{base}...{head}", text)

    def test_nearest_published_stable_ancestor_is_the_base(self):
        first = self.repo.commit("ci/a.py", "one")
        newer = self.repo.commit("ci/b.py", "two")
        drafted = self.repo.commit("ci/c.py", "three")
        pre = self.repo.commit("ci/d.py", "four")
        self.repo.git("checkout", "-q", "-b", "rewritten", first)
        orphaned = self.repo.commit("ci/side.py", "side branch")
        self.repo.git("checkout", "-q", "main")
        head = self.repo.commit("ci/e.py", "five")
        self.repo.git("tag", "v9", pre)
        current = self.repo.tag(head)
        releases = [[release(self.repo.tag(first), published_at="2026-01-01T00:00:00Z"),
                     release(self.repo.tag(newer), published_at="2026-01-02T00:00:00Z"),
                     release(self.repo.tag(drafted), draft=True),
                     release(self.repo.tag(pre), prerelease=True, published_at="2026-01-04T00:00:00Z"),
                     release("v9", published_at="2026-01-05T00:00:00Z")],
                    [release(self.repo.tag(orphaned), published_at="2026-01-06T00:00:00Z"),
                     release(current, published_at="2026-01-07T00:00:00Z")]]
        text = self.changes(head, releases, current)
        self.assertEqual(listed(text), [head, pre, drafted])
        self.assertIn(f"/compare/{newer}...{head}", text)

    def test_new_upstream_version_without_source_changes_lists_nothing(self):
        built = self.repo.commit("work/mod/a.cpp", "feat: a")
        previous = self.repo.tag(built)
        head = self.repo.commit("README.md", "docs: after release")
        text = self.changes(head, [release(previous)], f"toe3-v1.1-b11-mod-{built[:7]}")
        self.assertEqual(listed(text), [])
        self.assertIn(f"/compare/{built}...{head}", text)

    def test_without_an_earlier_published_release_lists_relevant_history(self):
        first = self.repo.commit("work/mod/a.cpp", "feat: a")
        self.repo.commit("README.md", "docs")
        head = self.repo.commit("ci/b.py", "ci: b")
        for releases in ([], [release(self.repo.tag(first), draft=True)]):
            text = self.changes(head, releases)
            self.assertEqual(listed(text), [head, first])
            self.assertNotIn("/compare/", text)
        many = rc.render("owner/repo", head, None, [(f"{i:040x}", "s") for i in range(5)], limit=3)
        self.assertEqual(len(listed(many)), 3)
        self.assertIn(f"/commits/{head}", many)

    def test_unknown_history_or_metadata_is_an_error_not_an_empty_list(self):
        head = self.repo.commit("ci/a.py", "a")
        missing = f"toe3-v1.0-b10-mod-{'f' * 7}"
        cases = {
            "missing tag": (head, [release(missing)]),
            "unknown head": ("e" * 40, []),
            "option-like head": ("--output=x", []),
            "symbolic head": ("HEAD", []),
            "malformed release": (head, [{"tag_name": missing, "draft": "false", "prerelease": False}]),
            "not a list": (head, {"tag_name": missing}),
        }
        for name, (sha, releases) in cases.items():
            with self.subTest(name), self.assertRaises(rc.ChangesError):
                rc.changes(self.repo.path, "owner/repo", sha, "toe3-v1.0-b10-mod-1234567", releases)
        for repository in ("owner", "owner/repo/x", "../repo"):
            with self.subTest(repository), self.assertRaises(rc.ChangesError):
                rc.changes(self.repo.path, repository, head, "toe3-v1.0-b10-mod-1234567", [])

        output = self.repo.path.parent / "changes.md"
        releases = self.repo.path.parent / "releases.json"
        releases.write_text(json.dumps([release(missing)]))
        args = ["--repository", "owner/repo", "--head", head, "--current-tag", "toe3-v1.0-b10-mod-1234567",
                "--releases", str(releases), "--output", str(output)]
        cwd = os.getcwd()
        os.chdir(self.repo.path)
        try:
            with patch("sys.stderr"):
                self.assertEqual(rc.main(args), 1)
            self.assertFalse(output.exists())
            releases.write_text("[]")
            self.assertEqual(rc.main(args), 0)
        finally:
            os.chdir(cwd)
        self.assertEqual(listed(output.read_text()), [head])

    def test_commit_subjects_render_as_literal_text(self):
        subject = "<img src=x onerror=alert(1)> [a](javascript:x) ![i](y) **b** _c_ `d` | e | ~~f~~ @org/team #1 \\ &amp;"
        head = self.repo.commit("ci/a.py", subject)
        line = next(l for l in self.changes(head, []).splitlines() if head in l)
        rendered = line.split(") ", 1)[1]
        plain, i = [], 0
        while i < len(rendered):
            if rendered[i] == "\\":
                plain.append(rendered[i + 1])
                i += 2
                continue
            self.assertNotIn(rendered[i], SPECIALS, rendered)
            plain.append(rendered[i])
            i += 1
        self.assertEqual("".join(plain), subject)


if __name__ == "__main__":
    unittest.main()
