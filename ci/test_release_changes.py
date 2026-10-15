"""Release notes and semantic version boundaries on temporary git repositories."""

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

    def tag(self, sha, name=None):
        name = name or f"toe3-v1.0-b10-mod-{sha[:7]}"
        self.git("tag", name, sha)
        return name


def manifest(tag, revision, name="1.0", code=10, **overrides):
    """update.json published with semantic release tag at revision, for game name / code."""
    return {"schema": 1, "repository": "owner/repo", "package": "com.example.game", "abi": "arm64-v8a",
            "tag": tag, "mod_build": 7, "mod_revision": revision, "version_code": code, "version_name": name,
            "required_apks": ["base.apk"], "base_apk": "base.apk", "signing_cert_sha256": "ab" * 32,
            "apks": [{"name": "base.apk", "size": 1, "sha256": "cd" * 32}], **overrides}


def version(tag):
    return {"build": True, "tag": tag, "mod_version": tag[1:]}


class RepoCase(unittest.TestCase):
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


class ChangesTests(RepoCase):
    def changes(self, head, releases, current=None):
        return rc.changes(self.repo.path, "owner/repo", head, current or f"toe3-v1.0-b10-mod-{head[:7]}", releases)

    def test_cli_embeds_only_this_releases_curated_changes_with_nested_headings(self):
        base = self.repo.commit("ci/a.py", "ci: original build")
        previous = self.repo.tag(base)
        head = self.repo.commit("work/mod/world.cpp", "fix: accept a weather selection during transitions")
        current = self.repo.tag(head)
        changelog = self.repo.path.parent / "CHANGELOG.md"
        changelog.write_text(
            f"# Changelog\n\n## [{current}] - 2026-10-15\n\n"
            "### Added\n\n- Read-only weather state viewer.\n\n"
            "### Fixed\n\n- Apply a weather selection during an active transition.\n\n"
            f"## [{previous}] - 2026-10-14\n\n### Added\n\n- Previous release only.\n")
        releases = self.repo.path.parent / "releases.json"
        releases.write_text(json.dumps([release(previous)]))
        output = self.repo.path.parent / "notes.md"
        done = subprocess.run(
            ["python3", str(Path(rc.__file__).resolve()), "--repository", "owner/repo",
             "--head", head, "--current-tag", current, "--releases", str(releases),
             "--changelog", str(changelog), "--output", str(output)],
            cwd=self.repo.path, capture_output=True, text=True, check=False)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        notes = output.read_text()
        self.assertIn("Read-only weather state viewer.", notes)
        self.assertIn("Apply a weather selection during an active transition.", notes)
        self.assertNotIn("Previous release only.", notes)
        self.assertRegex(notes, r"(?m)^#### Added$")
        self.assertRegex(notes, r"(?m)^#### Fixed$")

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
        self.assertCountEqual(listed(text), [head, icon, native])
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
            self.assertCountEqual(listed(text), [head, first])
            self.assertNotIn("/compare/", text)
        many = rc.render("owner/repo", head, None, [(f"{i:040x}", "s", "other") for i in range(5)], limit=3)
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
        for tag in ("v0.2", "v01.0.0", "v1.0.0-rc.1", "toe3-v1.0", "local-0123456789ab"):
            with self.subTest(tag), self.assertRaises(rc.ChangesError):
                rc.changes(self.repo.path, "owner/repo", head, tag, [])

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
            with patch("sys.stderr"):
                self.assertEqual(rc.main([*args, "--changelog", str(self.repo.path.parent / "absent.md")]), 1)
            self.assertFalse(output.exists())
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

    def test_mixed_legacy_and_semantic_history_uses_the_nearest_stable_semantic_release(self):
        legacy = self.repo.tag(self.repo.commit("ci/a.py", "ci: legacy build"))
        semantic = self.repo.commit("work/mod/a.cpp", "feat: first semantic build")
        self.repo.tag(semantic, "v0.1.0")
        unstable = self.repo.commit("work/mod/b.cpp", "fix: not a stable semantic release")
        invalid = ("v0.2", "v01.2.0", "v0.2.0-rc.1")
        for tag in invalid:
            self.repo.tag(unstable, tag)
        head = self.repo.commit("ci/c.py", "ci: head")
        releases = [release(legacy, published_at="2026-01-01T00:00:00Z"),
                    release("v0.1.0", published_at="2026-01-02T00:00:00Z"),
                    *(release(tag, published_at="2026-01-03T00:00:00Z") for tag in invalid)]
        self.assertEqual(set(rc.published_mod_tags(releases)), {legacy, "v0.1.0"})
        self.assertEqual(rc.previous_release(self.repo.path, head, rc.published_mod_tags(releases), None),
                         ("v0.1.0", semantic))
        text = self.changes(head, releases, "v0.2.0")
        self.assertIn("[`v0.1.0`](https://github.com/owner/repo/releases/tag/v0.1.0)", text)
        self.assertIn(f"/compare/{semantic}...{head}", text)
        self.assertCountEqual(listed(text), [head, unstable])

    def test_without_a_curated_entry_commits_are_grouped_by_conventional_type(self):
        self.repo.tag(self.repo.commit("ci/a.py", "ci: base"), "v0.1.0")
        fix = self.repo.commit("work/mod/a.cpp", "fix(weather): keep <rain> selected")
        feat = self.repo.commit("work/mod/b.cpp", "feat: add a viewer")
        breaking = self.repo.commit("work/mod/c.cpp", "refactor: rename settings\n\nBREAKING CHANGE: body-only detail")
        other = self.repo.commit("ci/d.py", "ci: cache tools")
        changelog = ("# Changelog\n\n## [v0.1.0] - 2026-10-14\n\n### Added\n\n- Older release only.\n\n"
                     "[v0.1.0]: https://example.com/v0.1.0\n")
        for notes in (changelog, None):
            with self.subTest(changelog=notes is not None):
                text = rc.changes(self.repo.path, "owner/repo", other, "v0.2.0", [release("v0.1.0")], notes)
                groups = re.findall(r"(?m)^#### (.+)\n\n((?:- .*\n?)+)", text)
                self.assertEqual([(title, listed(items)) for title, items in groups],
                                 [("Breaking changes", [breaking]), ("Added", [feat]), ("Fixed", [fix]),
                                  ("Changed", [other])])
                self.assertCountEqual(listed(text), [fix, feat, breaking, other])
                self.assertIn("keep \\<rain\\> selected", text)
                for leaked in ("Older release only.", "example.com", "body-only detail"):
                    self.assertNotIn(leaked, text)
                self.assertNotRegex(text, r"(?m)^ {0,3}#{1,3}(?:[ \t]|$)")

    def test_unchanged_mod_source_gets_an_explicit_note_with_or_without_curated_notes(self):
        built = self.repo.commit("work/mod/a.cpp", "feat: a")
        self.repo.tag(built, "v0.1.0")
        head = self.repo.commit("README.md", "feat!: readme only")
        curated = "## [v0.1.1] - 2026-10-16\n\n### Changed\n\n- Supports game 1.1.\n"
        for notes in (None, curated):
            with self.subTest(curated=notes is not None):
                text = rc.changes(self.repo.path, "owner/repo", head, "v0.1.1", [release("v0.1.0")], notes)
                self.assertEqual(listed(text), [])
                self.assertIn(f"/compare/{built}...{head}", text)
                self.assertIn("unchanged", text)
                self.assertEqual(notes is not None, "Supports game 1.1." in text)
                self.assertNotRegex(text, r"(?m)^ {0,3}#{1,3}(?:[ \t]|$)")


class VersionTests(RepoCase):
    def resolve(self, head, releases, name="1.0", code=10, published=None):
        previous = rc.previous_release(self.repo.path, head, rc.published_mod_tags(releases), None)
        return rc.resolve_version(self.repo.path, head, previous, name, code, published)

    def test_first_semantic_version_comes_from_legacy_or_no_history_once(self):
        first = self.repo.commit("work/mod/a.cpp", "feat!: first")
        self.assertEqual(self.resolve(first, []), version("v0.1.0"))
        legacy = self.repo.tag(first, f"toe3-v2.5.9-b10-mod-{first[:7]}")
        docs = self.repo.commit("README.md", "docs: after the legacy release")
        self.assertEqual(self.resolve(docs, [release(legacy)], "2.5.9", 10), version("v0.1.0"))
        head = self.repo.commit("work/mod/b.cpp", "feat!: breaking after the legacy release")
        self.assertEqual(self.resolve(head, [release(legacy)], "2.6.0", 11), version("v0.1.0"))
        with self.assertRaises(rc.ChangesError):
            self.resolve(head, [release(legacy)], published=manifest(legacy, first))
        self.repo.tag(head, "v0.1.0")
        releases = [release(legacy), release("v0.1.0", published_at="2026-02-01T00:00:00Z")]
        self.assertEqual(self.resolve(head, releases, "2.6.0", 11, manifest("v0.1.0", head, "2.6.0", 11)),
                         {"build": False, "tag": "v0.1.0", "mod_version": "0.1.0"})

    def test_same_inputs_build_nothing_and_a_new_game_alone_is_a_patch(self):
        base = self.repo.commit("ci/a.py", "feat: base")
        self.repo.tag(base, "v1.2.3")
        published = manifest("v1.2.3", base)
        self.repo.commit("README.md", "feat!: readme only")
        self.repo.commit("CHANGELOG.md", "feat: changelog only")
        head = self.repo.commit("assets/artwork/mod-hero.webp", "feat: promotional hero only")
        releases = [release("v1.2.3")]
        for _ in range(2):
            self.assertEqual(self.resolve(head, releases, "1.0", 10, published),
                             {"build": False, "tag": "v1.2.3", "mod_version": "1.2.3"})
        for name, code in (("1.1", 11), ("1.0", 11), ("1.0.1", 10)):
            with self.subTest(name=name, code=code):
                self.assertEqual(self.resolve(head, releases, name, code, published), version("v1.2.4"))

    def test_conventional_commits_pick_the_highest_bump_and_reset_lower_parts(self):
        base = self.repo.commit("ci/a.py", "ci: base")
        self.repo.tag(base, "v1.2.3")
        cases = [
            ([("ci/x.py", "ci: tidy")], "v1.2.4"),
            ([("work/mod/x.cpp", "Fix a crash without a type")], "v1.2.4"),
            ([("work/mod/x.cpp", "fix(weather): apply the selection")], "v1.2.4"),
            ([("work/mod/x.cpp", "fix: mentions it\n\nNot a BREAKING CHANGE: here\nbreaking change: lower case")],
             "v1.2.4"),
            ([("README.md", "feat!: documentation only"), ("work/mod/x.cpp", "fix: real")], "v1.2.4"),
            ([("work/mod/x.cpp", "fix: a"), ("work/mod/y.cpp", "feat(menu): b"), ("ci/z.py", "chore: c")], "v1.3.0"),
            ([("work/mod/x.cpp", "FEAT: upper-case type")], "v1.3.0"),
            ([("work/mod/x.cpp", "feat: a"), ("work/mod/y.cpp", "refactor(api)!: drop old settings")], "v2.0.0"),
            ([("work/mod/x.cpp", "fix: a\n\nBREAKING CHANGE: settings are reset")], "v2.0.0"),
            ([("ci/x.py", "chore: a\n\nBody.\n\nBREAKING-CHANGE: new key format")], "v2.0.0"),
        ]
        for i, (commits, expected) in enumerate(cases):
            with self.subTest(commits=commits):
                self.repo.git("checkout", "-q", "-B", f"case{i}", base)
                head = [self.repo.commit(path, message) for path, message in commits][-1]
                for name, code in (("1.0", 10), ("1.1", 11)):
                    self.assertEqual(self.resolve(head, [release("v1.2.3")], name, code, manifest("v1.2.3", base)),
                                     version(expected))

    def test_previous_semantic_release_needs_its_own_valid_update_json(self):
        base = self.repo.commit("ci/a.py", "ci: a")
        self.repo.tag(base, "v0.1.0")
        other = self.repo.commit("ci/b.py", "ci: b")
        head = self.repo.commit("work/mod/c.cpp", "fix: c")
        good = manifest("v0.1.0", base)
        self.assertEqual(self.resolve(head, [release("v0.1.0")], published=good), version("v0.1.1"))
        bad = {
            "missing": None,
            "not an object": [good],
            "extra key": {**good, "mod_version": "0.1.0"},
            "missing key": {k: v for k, v in good.items() if k != "apks"},
            "other tag": {**good, "tag": "v0.1.1"},
            "legacy tag": {**good, "tag": f"toe3-v1.0-b10-mod-{base[:7]}"},
            "other revision": {**good, "mod_revision": other},
            "local build": {**good, "mod_build": 0},
            "schema 2": {**good, "schema": 2},
            "string game code": {**good, "version_code": "10"},
            "boolean game code": {**good, "version_code": True},
            "bad repository": {**good, "repository": "owner"},
        }
        for name, doc in bad.items():
            with self.subTest(name), self.assertRaises(rc.ChangesError):
                self.resolve(head, [release("v0.1.0")], published=doc)

    def test_invalid_inputs_or_inconsistent_history_are_errors(self):
        base = self.repo.commit("ci/a.py", "ci: a")
        self.repo.tag(base, "v0.1.0")
        self.repo.git("checkout", "-q", "-b", "side", base)
        side = self.repo.commit("ci/side.py", "ci: side")
        self.repo.tag(side, "v0.2.0")
        self.repo.git("checkout", "-q", "main")
        head = self.repo.commit("ci/b.py", "fix: b")
        previous, good = ("v0.1.0", base), manifest("v0.1.0", base)
        self.assertEqual(rc.resolve_version(self.repo.path, head, previous, "1.0", 10, good), version("v0.1.1"))
        cases = {
            "unknown head": ("e" * 40, previous, "1.0", 10, good),
            "symbolic head": ("HEAD", previous, "1.0", 10, good),
            "empty game name": (head, previous, "", 10, good),
            "numeric game name": (head, previous, 1.0, 10, good),
            "zero game code": (head, previous, "1.0", 0, good),
            "boolean game code": (head, previous, "1.0", True, good),
            "string game code": (head, previous, "1.0", "10", good),
            "not a pair": (head, "v0.1.0", "1.0", 10, good),
            "not a mod tag": (head, ("v0.1", base), "1.0", 10, good),
            "abbreviated commit": (head, ("v0.1.0", base[:7]), "1.0", 10, good),
            "tag elsewhere": (head, ("v0.1.0", side), "1.0", 10, manifest("v0.1.0", side)),
            "not an ancestor": (head, ("v0.2.0", side), "1.0", 10, manifest("v0.2.0", side)),
        }
        for name, args in cases.items():
            with self.subTest(name), self.assertRaises(rc.ChangesError):
                rc.resolve_version(self.repo.path, *args)

    def test_next_version_must_fit_a_release_tag(self):
        longest = "v0.0." + "9" * 123
        self.assertEqual(len(longest), 128)
        base = self.repo.commit("ci/a.py", "ci: a")
        self.repo.tag(base, longest)
        head = self.repo.commit("ci/b.py", "fix: b")
        with self.assertRaises(rc.ChangesError):
            self.resolve(head, [release(longest)], published=manifest(longest, base))
        self.repo.commit("work/mod/a.cpp", "feat: c")
        self.assertEqual(self.resolve(self.repo.git("rev-parse", "HEAD"), [release(longest)],
                                      published=manifest(longest, base)), version("v0.1.0"))


class CuratedNotesTests(unittest.TestCase):
    CHANGELOG = ("# Changelog\n\nIntro.\n\n## [Unreleased]\n\n### Added\n\n- Unreleased only.\n\n"
                 "## [v0.2.0] - 2026-10-16\n\nSummary, see [v0.2.0].\n\n### Added\n\n- New.\n\n"
                 "#### Detail\n\n###### Deepest\n\n"
                 "## v0.1.0 - 2026-10-15\n\n### Fixed\n\n- Old fix.\n\n"
                 "## [v0.1.0-beta]\n\n- Prefix only.\n\n"
                 "## [toe3-v1.0-b10-mod-0123456] - 2026-10-14\n\n### Added\n\n- Legacy release.\n\n"
                 "## [v0.3.0]\n\n"
                 "[v0.2.0]: https://example.com/v0.2.0\n[v0.3.0]: https://example.com/v0.3.0\n")

    def test_only_the_exact_section_with_nested_headings_and_without_link_definitions(self):
        expected = {
            "v0.2.0": "Summary, see [v0.2.0].\n\n#### Added\n\n- New.\n\n##### Detail\n\n###### Deepest\n",
            "v0.1.0": "#### Fixed\n\n- Old fix.\n",
            "toe3-v1.0-b10-mod-0123456": "#### Added\n\n- Legacy release.\n",
            "v0.3.0": "",
            "v0.4.0": "",
            "v0.2": "",
            "0.2.0": "",
        }
        for text in (self.CHANGELOG, self.CHANGELOG.replace("\n", "\r\n")):
            for tag, notes in expected.items():
                with self.subTest(tag=tag, crlf="\r" in text):
                    self.assertEqual(rc.curated_notes(text, tag), notes)

    def test_duplicate_section_is_an_error(self):
        with self.assertRaises(rc.ChangesError):
            rc.curated_notes(self.CHANGELOG + "\n## [v0.2.0]\n\n- Again.\n", "v0.2.0")


if __name__ == "__main__":
    unittest.main()
