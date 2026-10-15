"""Semantic and byte-preservation tests for binary manifest branding, without game APK fixtures."""

import struct
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
import zlib

import check_apks as ca
import manifest_branding as mb

ATTRIBUTES = (("theme", mb.ATTR_THEME), ("label", mb.ATTR_LABEL), ("icon", mb.ATTR_ICON),
              ("name", mb.ATTR_NAME), ("exported", mb.ATTR_EXPORTED),
              ("excludeFromRecents", mb.ATTR_EXCLUDE_FROM_RECENTS), ("roundIcon", mb.ATTR_ROUND_ICON))
ICON_ID, ROUND_ID = 0x7F0C0000, 0x7F0C0001
FILES = {"res/mipmap-mdpi-v4/app_icon.png": "square", "res/mipmap-mdpi-v4/app_icon_round.png": "round"}
RESOURCES = """Package name=com.example.trucks id=7f
  type mipmap id=0c entryCount=3
    resource 0x7f0c0000 mipmap/app_icon
      (mdpi) (file) res/mipmap-mdpi-v4/app_icon.png type=PNG
    resource 0x7f0c0001 mipmap/app_icon_round
      (mdpi) (file) res/mipmap-mdpi-v4/app_icon_round.png type=PNG
    resource 0x7f0c0002 mipmap/unrelated
      (mdpi) (file) res/mipmap-mdpi-v4/other.png type=PNG
"""
UNKNOWN_CHUNK = struct.pack("<HHI4s", 0x7777, 8, 12, b"keep")


def manifest_fixture(launcher_label=False, utf8=False):
    strings = [a[0] for a in ATTRIBUTES] + [mb.ANDROID_NS, "manifest", "application", "activity", "uses-permission",
               "intent-filter", "action", "category", "android.permission.INTERNET", "com.example.UnityActivity",
               "android.intent.action.MAIN", "android.intent.category.LAUNCHER"]
    idx = {s: i for i, s in enumerate(strings)}
    pool = []
    offsets = []
    for text in strings:
        offsets.append(sum(map(len, pool)))
        raw = text.encode("utf-8" if utf8 else "utf-16-le")
        pool.append((bytes((len(text), len(raw))) + raw + b"\0") if utf8
                    else struct.pack("<H", len(text)) + raw + b"\0\0")
    data = b"".join(pool)
    data += b"\0" * (-len(data) % 4)
    start = 28 + 4 * len(offsets)
    sp = (struct.pack("<HHIIIIII", 1, 28, start + len(data), len(offsets), 0, 0x100 if utf8 else 0, start, 0)
          + struct.pack(f"<{len(offsets)}I", *offsets) + data)
    rm = struct.pack("<HHI", 0x180, 8, 8 + 4 * len(ATTRIBUTES)) + struct.pack(f"<{len(ATTRIBUTES)}I", *(a[1] for a in ATTRIBUTES))
    ns, no = idx[mb.ANDROID_NS], 0xFFFFFFFF

    def attr(name, dtype, value, raw=None):
        return mb.Attr(ns, idx[name], no if raw is None else raw, dtype, value)

    def text(name, value):
        i = idx[value]
        return attr(name, 3, i, i)

    chunks = [sp, rm, UNKNOWN_CHUNK, struct.pack("<HHIIIII", 0x100, 16, 24, 2, no, no, ns)]

    def start_tag(name, line, attrs=()):
        chunks.append(mb._start_tag(no, idx[name], line, list(attrs)))

    def end_tag(name, line):
        chunks.append(mb._end_tag(no, idx[name], line))

    start_tag("manifest", 2)
    start_tag("uses-permission", 7, [text("name", "android.permission.INTERNET")])
    end_tag("uses-permission", 7)
    start_tag("application", 10, [attr("label", 1, 0x7F0E0022), attr("icon", 1, ICON_ID), attr("roundIcon", 1, ROUND_ID)])
    attrs = [text("name", "com.example.UnityActivity"), attr("exported", 0x12, no)]
    if launcher_label:
        attrs.insert(0, attr("label", 1, 0x7F0E0023))
    start_tag("activity", 12, attrs)
    start_tag("intent-filter", 14)
    start_tag("category", 15, [text("name", "android.intent.category.LAUNCHER")])
    end_tag("category", 15)
    start_tag("action", 16, [text("name", "android.intent.action.MAIN")])
    end_tag("action", 16)
    end_tag("intent-filter", 14)
    end_tag("activity", 12)
    end_tag("application", 10)
    end_tag("manifest", 2)
    chunks.append(struct.pack("<HHIIIII", 0x101, 16, 24, 2, no, no, ns))
    body = b"".join(chunks)
    return struct.pack("<HHI", 3, 8, 8 + len(body)) + body


def dump_manifest(data):
    """Test decoder/printer of node values, deliberately separate from the expected-dump transformer."""
    doc = mb.Axml(data)
    lines = [f"N: android={mb.ANDROID_NS} (line=2)"]

    def element(e, indent):
        lines.append(" " * indent + f"E: {e.name} (line={e.line})")
        for a in e.attrs:
            rid = doc.attr_id(a)
            name = doc.string(a.name)
            prefix = f"A: {mb.ANDROID_NS}:{name}(0x{rid:08x})="
            if a.dtype == 3:
                val = doc.string(a.data)
                suffix = f'"{val}" (Raw: "{val}")'
            elif a.dtype == 1:
                suffix = f"@0x{a.data:08x}"
            elif a.dtype == 0x12:
                suffix = "true" if a.data else "false"
            else:
                raise AssertionError(f"unsupported test attribute type {a.dtype}")
            lines.append(" " * (indent + 2) + prefix + suffix)
        for child in e.children:
            element(child, indent + 4)

    element(doc.elements[0], 2)
    return "\n".join(lines) + "\n"


def badging(patched=False):
    label = mb.LABEL if patched else "Truckers of Europe 3"
    perm = f"uses-permission: name='{mb.INSTALL_PERMISSION}'\n" if patched else ""
    return ("package: name='com.example.trucks' versionCode='10' versionName='1.0'\n"
            "minSdkVersion:'23'\ntargetSdkVersion:'35'\n"
            "uses-permission: name='android.permission.INTERNET'\n" + perm
            + f"application-label:'{label}'\napplication-label-ja:'{label}'\n"
            + f"application: label='{label}' icon='res/mipmap-mdpi-v4/app_icon.png'\n"
            + "launchable-activity: name='com.example.UnityActivity'  label='' icon=''\nmain\nother-activities\n")


def solid_png(width, height, color):
    """A minimal unfiltered true-colour fixture; production image handling belongs to ImageIO."""
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    rows = (b"\0" + bytes(color) * width) * height
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows))
            + chunk(b"IEND", b""))


def probe_images(images):
    """Use an independent ImageIO consumer to inspect dimensions and corner/centre ARGB pixels."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        source = root / "ImageProbe.java"
        source.write_text("""
import java.io.File;
import javax.imageio.ImageIO;
class ImageProbe {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            var image = ImageIO.read(new File(path));
            System.out.printf("%d %d %s %s%n", image.getWidth(), image.getHeight(),
                Integer.toUnsignedString(image.getRGB(0, 0), 16),
                Integer.toUnsignedString(image.getRGB(image.getWidth()/2, image.getHeight()/2), 16));
        }
    }
}
""")
        paths = []
        for i, data in enumerate(images):
            path = root / f"{i}.png"
            path.write_bytes(data)
            paths.append(str(path))
        out = subprocess.run(["java", "-Djava.awt.headless=true", str(source), *paths],
                             check=True, capture_output=True, text=True, timeout=60).stdout
    return [(int(w), int(h), int(corner, 16), int(centre, 16))
            for w, h, corner, centre in (line.split() for line in out.splitlines())]


class BinaryManifestTests(unittest.TestCase):
    def test_patch_keeps_original_indices_chunks_and_resource_ids_utf16_and_utf8(self):
        for utf8 in (False, True):
            with self.subTest(utf8=utf8):
                original = manifest_fixture(utf8=utf8)
                patched = mb.patch_manifest(original)
                old, new = mb.Axml(original), mb.Axml(patched)
                self.assertEqual(new.pool.strings[:len(old.pool.strings)], old.pool.strings)
                self.assertEqual(new.pool.offsets[:len(old.pool.offsets)], old.pool.offsets)
                self.assertTrue(new.pool.data.startswith(old.pool.data))
                self.assertEqual(new.resmap, old.resmap)
                self.assertIn(UNKNOWN_CHUNK, new.chunks)
                self.assertEqual(mb.icon_resources(patched), {ICON_ID: "square", ROUND_ID: "round"})
                # Every existing node is untouched, except the application's label attribute.
                _, app = old.application()
                for i, chunk in enumerate(old.chunks[1:], 1):
                    if i != app.start:
                        self.assertIn(chunk, new.chunks)
                root, app = new.application()
                label = new.attr(app, mb.ATTR_LABEL)
                self.assertEqual(label.dtype, 3)
                self.assertEqual(new.string(label.data), mb.LABEL)
                perm = [e for e in root.children if new.name_of(e) == mb.INSTALL_PERMISSION]
                installer = [e for e in app.children if new.name_of(e) == mb.UPDATE_ACTIVITY]
                self.assertEqual(len(perm), 1)
                self.assertEqual(len(installer), 1)
                self.assertEqual(installer[0].children, [])
                self.assertEqual(new.attr(installer[0], mb.ATTR_EXPORTED).data, 0)
                self.assertEqual(new.attr(installer[0], mb.ATTR_THEME).data, mb.THEME_TRANSLUCENT_NO_TITLE_BAR)
                self.assertEqual(new.attr(installer[0], mb.ATTR_EXCLUDE_FROM_RECENTS).dtype, 0x12)

    def test_binary_transform_and_independent_dump_transform_agree(self):
        original = manifest_fixture()
        actual = dump_manifest(mb.patch_manifest(original))
        self.assertEqual(actual, mb.expected_manifest_dump(dump_manifest(original)))
        mb.verify_manifest_dump(dump_manifest(original), actual)
        for old, bad in (("=false", "=true"), ("@0x7f0c0000", "@0x7f0c0002"),
                         ("android.permission.INTERNET", "android.permission.READ_SMS"),
                         (mb.UPDATE_ACTIVITY, "com.example.ExportedActivity")):
            with self.subTest(change=(old, bad)), self.assertRaises(mb.BrandingError):
                mb.verify_manifest_dump(dump_manifest(original), actual.replace(old, bad))
        with self.assertRaises(mb.BrandingError):
            mb.verify_manifest_dump(dump_manifest(original), actual + "unexpected line\n")

    def test_already_patched_launcher_overrides_and_unknown_layout_fail(self):
        with self.assertRaises(mb.BrandingError):
            mb.patch_manifest(mb.patch_manifest(manifest_fixture()))
        with self.assertRaises(mb.BrandingError):
            mb.patch_manifest(manifest_fixture(launcher_label=True))
        for replacement in (b"plain text", manifest_fixture()[:-1]):
            with self.subTest(data=replacement[:8]), self.assertRaises(mb.BrandingError):
                mb.patch_manifest(replacement)
        sorted_pool = bytearray(manifest_fixture())
        struct.pack_into("<I", sorted_pool, 8 + 16, 1)
        with self.assertRaises(mb.BrandingError):
            mb.patch_manifest(bytes(sorted_pool))

    def test_badging_changes_only_label_and_install_permission(self):
        mb.verify_badging(badging(), badging(True))
        for old, value in (("versionCode='10'", "versionCode='11'"), ("targetSdkVersion:'35'", "targetSdkVersion:'34'"),
                           ("com.example.UnityActivity", "com.example.OtherActivity"), (mb.LABEL, "Old title"),
                           ("REQUEST_INSTALL_PACKAGES", "READ_CONTACTS")):
            with self.subTest(change=(old, value)), self.assertRaises(mb.BrandingError):
                mb.verify_badging(badging(), badging(True).replace(old, value))


class IconTests(unittest.TestCase):
    def test_resolves_all_rasters_and_rejects_unsupported_or_shared_resources(self):
        icons = {ICON_ID: "square", ROUND_ID: "round"}
        self.assertEqual(mb.icon_files(RESOURCES, icons), FILES)
        for modified in (
            RESOURCES.replace("app_icon.png type=PNG", "app_icon.xml type=XML"),
            RESOURCES.replace("app_icon.png type=PNG", "app_icon.9.png type=PNG"),
            RESOURCES.replace("(file) res/mipmap-mdpi-v4/app_icon.png type=PNG", "@0x7f0c0002"),
            RESOURCES.replace("other.png", "app_icon.png"),
            RESOURCES.replace("app_icon_round.png", "app_icon.png"),
            RESOURCES.replace("0x7f0c0000", "0x7f0c0003"),
        ):
            with self.subTest(dump=modified), self.assertRaises(mb.BrandingError):
                mb.icon_files(modified, icons)

    def test_jdk_renderer_outputs_correct_size_colour_and_round_transparency(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "icon.png"
            source.write_bytes(solid_png(16, 16, (20, 90, 200)))
            rendered = mb.render_icons(source, {"square.png": "square", "round.png": "round"},
                                       {"square.png": 8, "round.png": 8})
            square, rounded = probe_images([rendered["square.png"], rendered["round.png"]])
            self.assertEqual(square, (8, 8, 0xFF145AC8, 0xFF145AC8))
            self.assertEqual(rounded, (8, 8, 0, 0xFF145AC8))

    def test_renderer_rejects_non_square_corrupt_source_and_upscale(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "icon.png"
            for raw, size in ((solid_png(8, 4, (1, 2, 3)), 4), (b"not an image", 4),
                              (solid_png(4, 4, (1, 2, 3)), 8)):
                source.write_bytes(raw)
                with self.subTest(size=size), self.assertRaises(mb.BrandingError):
                    mb.render_icons(source, {"0.png": "square"}, {"0.png": size})
        for data in (b"not PNG", solid_png(4, 4, (1, 2, 3))[:20], solid_png(0, 4, (1, 2, 3))):
            with self.subTest(data=data[:20]), self.assertRaises(mb.BrandingError):
                mb.png_size(data)

    def test_actual_archive_icon_plan_and_apply_preserve_unrelated_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            original, decoded, art = root / "original.apk", root / "decoded", root / "icon.png"
            decoded.mkdir()
            manifest = manifest_fixture()
            entries = {mb.MANIFEST: manifest, "resources.arsc": b"unchanged resource table", "unrelated.bin": b"game bytes"}
            for path in FILES:
                entries[path] = solid_png(4, 4, (255, 0, 0))
            with zipfile.ZipFile(original, "w") as zf:
                for path, data in entries.items():
                    zf.writestr(path, data)
                    dest = decoded / path
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(data)
            art.write_bytes(solid_png(8, 8, (0, 255, 0)))
            tools = ca.Tools("aapt2", "apksigner")
            run = lambda argv: (0, RESOURCES, "") if argv[1:3] == ["dump", "resources"] else self.fail(str(argv))
            plan = mb.icon_plan(tools, original, art, run)
            self.assertEqual(set(plan), set(FILES))
            square = next(p for p, kind in FILES.items() if kind == "square")
            self.assertEqual(probe_images([plan[square]]), [(4, 4, 0xFF00FF00, 0xFF00FF00)])
            self.assertEqual(set(mb.apply(tools, original, decoded, art, run)), {mb.MANIFEST, *FILES})
            self.assertEqual((decoded / "unrelated.bin").read_bytes(), entries["unrelated.bin"])
            self.assertEqual((decoded / "resources.arsc").read_bytes(), entries["resources.arsc"])
            mb.verify_manifest_dump(dump_manifest(manifest), dump_manifest((decoded / mb.MANIFEST).read_bytes()))
            for path in plan:
                self.assertEqual((decoded / path).read_bytes(), plan[path])
            with self.assertRaises(mb.BrandingError):
                mb.apply(tools, original, decoded, art, run)


if __name__ == "__main__":
    unittest.main()
