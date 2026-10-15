#!/usr/bin/env python3
"""Launcher branding and OTA manifest entries of the menu-injected base APK.

    python3 ci/manifest_branding.py apply  --original BASE.apk --decoded DIR [--build-tools PATH]
    python3 ci/manifest_branding.py verify --original BASE.apk --patched OUT.apk [--build-tools PATH]

apply: DIR is the `apktool d -r` tree of BASE.apk (binary AndroidManifest.xml, raw res/). Its manifest
must be byte-identical to the one in BASE.apk. The binary XML is edited in place, so every existing
string index, resource reference, attribute and unknown chunk keeps its bytes:
  * <application android:label> becomes the literal LABEL (the launcher activity must not carry its
    own label or icon, otherwise it would keep showing the official one);
  * <uses-permission android:name=INSTALL_PERMISSION/> follows the last <uses-permission>;
  * <activity android:name=UPDATE_ACTIVITY android:exported="false" (translucent, excluded from
    recents)/> becomes the last child of <application>.
The PNG files of the application icon and roundIcon resources (resolved with aapt2 dump resources) are
replaced, at their original pixel size, by ICON (the round one circle-cropped). Adaptive (XML) icons,
shared or nine-patch files, or any other structure this code does not handle are rejected instead of
leaving the official icon in place.

verify: aapt2 xmltree of the patched manifest is exactly the original one plus these edits, aapt2
badging differs only in the label and the added permission, and every icon file equals the rendering
of ICON.

Errors are check_apks.Incompatible (one-line reason; exit status 1 from the CLI).
"""

from __future__ import annotations

import argparse
import re
import struct
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_apks as ca  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
ICON = ROOT / "assets" / "artwork" / "launcher-icon.png"
MANIFEST = "AndroidManifest.xml"
LABEL = "TOE3 Stormline"
INSTALL_PERMISSION = "android.permission.REQUEST_INSTALL_PACKAGES"
UPDATE_ACTIVITY = "com.toemod.UpdateInstallActivity"
ANDROID_NS = "http://schemas.android.com/apk/res/android"

ATTR_THEME = 0x01010000
ATTR_LABEL = 0x01010001
ATTR_ICON = 0x01010002
ATTR_NAME = 0x01010003
ATTR_EXPORTED = 0x01010010
ATTR_EXCLUDE_FROM_RECENTS = 0x01010017
ATTR_ROUND_ICON = 0x0101052C
THEME_TRANSLUCENT_NO_TITLE_BAR = 0x01030010  # android.R.style.Theme_Translucent_NoTitleBar

_RES_XML = 0x0003
_STRING_POOL = 0x0001
_RESOURCE_MAP = 0x0180
_START_NS, _END_NS, _START_ELEMENT, _END_ELEMENT, _CDATA = 0x0100, 0x0101, 0x0102, 0x0103, 0x0104
_NO_INDEX = 0xFFFFFFFF
_TYPE_REFERENCE, _TYPE_STRING, _TYPE_BOOLEAN = 0x01, 0x03, 0x12
_UTF8, _SORTED = 0x100, 0x1
_LAUNCHER_OVERRIDES = (ATTR_LABEL, ATTR_ICON, ATTR_ROUND_ICON)


class BrandingError(ca.Incompatible):
    """The manifest, the icon resources or the artwork cannot be branded safely."""


def _bad(msg):
    raise BrandingError(msg)


# ---------------------------------------------------------------- binary XML (AXML)

class StringPool:
    """String pool whose existing strings and offsets are never rewritten; new strings are appended."""

    def __init__(self, chunk):
        if len(chunk) < 28:
            _bad("manifest string pool header is truncated")
        _, header_size, size, count, styles, flags, start, styles_start = struct.unpack_from("<HHIIIIII", chunk)
        if header_size != 28 or styles or styles_start:
            _bad("manifest string pool has styles or an unknown header; not supported")
        if flags & ~(_UTF8 | _SORTED) or flags & _SORTED:
            _bad(f"manifest string pool flags 0x{flags:x} are not supported (sorted pools cannot be appended to)")
        if start != 28 + 4 * count or start > size:
            _bad("manifest string pool layout is not supported")
        self.utf8 = bool(flags & _UTF8)
        self.flags = flags
        self.data = bytes(chunk[start:size])
        self.offsets = list(struct.unpack_from(f"<{count}I", chunk, 28))
        self.strings = [self._decode(off) for off in self.offsets]
        self._added = []

    def _decode(self, off):
        d = self.data
        try:
            if self.utf8:
                p = off + (2 if d[off] & 0x80 else 1)
                n = d[p]
                if n & 0x80:
                    n, p = ((n & 0x7F) << 8) | d[p + 1], p + 2
                else:
                    p += 1
                if d[p + n] != 0:
                    raise IndexError
                return d[p:p + n].decode("utf-8", "replace")
            n, p = struct.unpack_from("<H", d, off)[0], off + 2
            if n & 0x8000:
                n, p = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", d, p)[0], p + 2
            if d[p + 2 * n:p + 2 * n + 2] != b"\0\0":
                raise IndexError
            return d[p:p + 2 * n].decode("utf-16-le", "surrogatepass")
        except (IndexError, struct.error):
            _bad(f"manifest string at offset {off} is malformed")

    def index(self, text):
        """Index of the first existing occurrence of text, otherwise of a newly appended copy."""
        if text in self.strings:
            return self.strings.index(text)
        if self.utf8:
            raw, units = text.encode("utf-8"), len(text.encode("utf-16-le")) // 2
            if max(len(raw), units) > 0x7FFF:
                _bad("string too long")
            enc = b"".join(bytes([n]) if n < 0x80 else bytes([0x80 | n >> 8, n & 0xFF]) for n in (units, len(raw)))
            encoded = enc + raw + b"\0"
        else:
            raw = text.encode("utf-16-le")
            n = len(raw) // 2
            enc = struct.pack("<H", n) if n < 0x8000 else struct.pack("<HH", 0x8000 | n >> 16, n & 0xFFFF)
            encoded = enc + raw + b"\0\0"
        self.offsets.append(len(self.data) + sum(len(e) for e in self._added))
        self._added.append(encoded)
        self.strings.append(text)
        return len(self.strings) - 1

    def chunk(self):
        data = self.data + b"".join(self._added)
        data += b"\0" * (-len(data) % 4)
        start = 28 + 4 * len(self.offsets)
        header = struct.pack("<HHIIIIII", _STRING_POOL, 28, start + len(data), len(self.offsets), 0,
                             self.flags, start, 0)
        return header + struct.pack(f"<{len(self.offsets)}I", *self.offsets) + data


@dataclass
class Attr:
    ns: int
    name: int
    raw: int
    dtype: int
    data: int
    offset: int = -1  # inside the element chunk; -1 for new attributes

    def pack(self):
        return struct.pack("<IIIHBBI", self.ns, self.name, self.raw, 8, 0, self.dtype, self.data)


@dataclass
class Element:
    start: int  # chunk index of the start tag
    ns: int
    name_idx: int
    name: str
    line: int
    attrs: list
    parent: "Element | None"
    children: list = field(default_factory=list)
    end: int = -1  # chunk index of the end tag


class Axml:
    """Chunk-level view of a binary XML document; chunks this code does not edit are kept verbatim."""

    def __init__(self, data):
        if len(data) < 8:
            _bad("manifest is not a binary XML document")
        kind, header_size, size = struct.unpack_from("<HHI", data)
        if kind != _RES_XML or header_size != 8 or size != len(data):
            _bad("manifest is not a binary XML document")
        self.chunks, off = [], 8
        while off < len(data):
            if off + 8 > len(data):
                _bad("manifest chunk header is truncated")
            _, chs, csz = struct.unpack_from("<HHI", data, off)
            if chs < 8 or csz < chs or csz % 4 or off + csz > len(data):
                _bad(f"manifest chunk at offset {off} is malformed")
            self.chunks.append(bytes(data[off:off + csz]))
            off += csz
        if not self.chunks or self._type(0) != _STRING_POOL:
            _bad("manifest does not start with a string pool")
        self.pool = StringPool(self.chunks[0])
        self.resmap = ()
        if len(self.chunks) > 1 and self._type(1) == _RESOURCE_MAP:
            c = self.chunks[1]
            if struct.unpack_from("<H", c, 2)[0] != 8:
                _bad("manifest resource map header is not supported")
            self.resmap = struct.unpack_from(f"<{(len(c) - 8) // 4}I", c, 8)
        self.elements = self._parse_elements()

    def _type(self, i):
        return struct.unpack_from("<H", self.chunks[i])[0]

    def string(self, idx):
        if idx >= len(self.pool.strings):
            _bad(f"manifest string index {idx} is out of range")
        return self.pool.strings[idx]

    def _parse_elements(self):
        elements, stack = [], []
        for i, chunk in enumerate(self.chunks):
            kind = self._type(i)
            if not _START_NS <= kind <= _CDATA:
                continue
            if struct.unpack_from("<H", chunk, 2)[0] != 16 or len(chunk) < 24:
                _bad("manifest XML node header is not supported")
            if kind == _START_ELEMENT:
                if len(chunk) < 36:
                    _bad("manifest start tag is truncated")
                line = struct.unpack_from("<I", chunk, 8)[0]
                ns, name, a_start, a_size, count = struct.unpack_from("<IIHHH", chunk, 16)
                if a_size != 20 or 16 + a_start + 20 * count > len(chunk):
                    _bad("manifest attribute layout is not supported")
                attrs = []
                for k in range(count):
                    off = 16 + a_start + 20 * k
                    a_ns, a_name, raw, vsize, _, dtype, data = struct.unpack_from("<IIIHBBI", chunk, off)
                    if vsize != 8:
                        _bad("manifest attribute value size is not supported")
                    attrs.append(Attr(a_ns, a_name, raw, dtype, data, off))
                parent = stack[-1] if stack else None
                element = Element(i, ns, name, self.string(name), line, attrs, parent)
                if parent is not None:
                    parent.children.append(element)
                elements.append(element)
                stack.append(element)
            elif kind == _END_ELEMENT:
                name = struct.unpack_from("<I", chunk, 20)[0]
                if not stack or stack[-1].name_idx != name:
                    _bad("manifest end tag does not match its start tag")
                stack.pop().end = i
        if stack:
            _bad("manifest has an unclosed element")
        roots = [e for e in elements if e.parent is None]
        if len(roots) != 1 or roots[0].name != "manifest":
            _bad("manifest root element is not a single <manifest>")
        return elements

    def attr_id(self, attr):
        return self.resmap[attr.name] if attr.name < len(self.resmap) else None

    def attr(self, element, res_id):
        found = [a for a in element.attrs if self.attr_id(a) == res_id]
        if len(found) > 1:
            _bad(f"<{element.name}> repeats attribute 0x{res_id:08x}")
        return found[0] if found else None

    def name_of(self, element):
        a = self.attr(element, ATTR_NAME)
        return self.string(a.data) if a is not None and a.dtype == _TYPE_STRING else None

    def attr_name_index(self, res_id):
        for i, rid in enumerate(self.resmap):
            if rid == res_id:
                return i
        _bad(f"manifest resource map lacks attribute 0x{res_id:08x}; adding it would renumber every string")

    def application(self):
        manifest = self.elements[0]
        apps = [e for e in manifest.children if e.name == "application"]
        if len(apps) != 1:
            _bad(f"expected one <application>, found {len(apps)}")
        return manifest, apps[0]

    def serialize(self):
        self.chunks[0] = self.pool.chunk()
        body = b"".join(self.chunks)
        return struct.pack("<HHI", _RES_XML, 8, 8 + len(body)) + body


def _launcher_components(doc, app):
    for comp in app.children:
        if comp.name not in ("activity", "activity-alias"):
            continue
        for intent_filter in comp.children:
            if intent_filter.name != "intent-filter":
                continue
            names = {(c.name, doc.name_of(c)) for c in intent_filter.children}
            if {("action", "android.intent.action.MAIN"), ("category", "android.intent.category.LAUNCHER")} <= names:
                yield comp, intent_filter


def _start_tag(ns, name_idx, line, attrs):
    body = b"".join(a.pack() for a in attrs)
    return (struct.pack("<HHIII", _START_ELEMENT, 16, 36 + len(body), line, _NO_INDEX)
            + struct.pack("<IIHHHHHH", ns, name_idx, 20, 20, len(attrs), 0, 0, 0) + body)


def _end_tag(ns, name_idx, line):
    return struct.pack("<HHIIIII", _END_ELEMENT, 16, 24, line, _NO_INDEX, ns, name_idx)


def icon_resources(manifest) -> dict:
    """{resource id: "square" | "round"} of the application icon and roundIcon."""
    doc = Axml(manifest)
    _, app = doc.application()
    kinds = {}
    for res_id, kind in ((ATTR_ROUND_ICON, "round"), (ATTR_ICON, "square")):
        a = doc.attr(app, res_id)
        if a is None:
            if res_id == ATTR_ICON:
                _bad("<application> has no android:icon")
            continue
        if a.dtype != _TYPE_REFERENCE or a.data >> 24 != 0x7F:
            _bad(f"<application> icon attribute 0x{res_id:08x} is not a reference to an app resource")
        kinds[a.data] = kind
    return kinds


def patch_manifest(manifest: bytes) -> bytes:
    """The manifest with the launcher label, the install permission and the installer activity."""
    doc = Axml(manifest)
    root, app = doc.application()
    for e in doc.elements:
        if doc.name_of(e) in (INSTALL_PERMISSION, UPDATE_ACTIVITY):
            _bad(f"manifest already declares {doc.name_of(e)}")
    for comp, intent_filter in _launcher_components(doc, app):
        for e in (comp, intent_filter):
            if any(doc.attr(e, rid) is not None for rid in _LAUNCHER_OVERRIDES):
                _bad(f"launcher <{e.name}> of {doc.name_of(comp)} sets its own label or icon; not supported")
    perms = [e for e in root.children if e.name == "uses-permission"]
    if not perms:
        _bad("manifest has no <uses-permission> to place the install permission after")
    label = doc.attr(app, ATTR_LABEL)
    if label is None:
        _bad("<application> has no android:label")
    if doc.string(label.ns) != ANDROID_NS:
        _bad("<application> android:label is not in the Android namespace")
    ns = label.ns
    for rid in (ATTR_THEME, ATTR_NAME, ATTR_EXPORTED, ATTR_EXCLUDE_FROM_RECENTS):
        doc.attr_name_index(rid)

    text = doc.pool.index(LABEL)
    chunk = bytearray(doc.chunks[app.start])
    chunk[label.offset:label.offset + 20] = Attr(ns, label.name, text, _TYPE_STRING, text).pack()
    doc.chunks[app.start] = bytes(chunk)

    last = perms[-1]
    perm = doc.pool.index(INSTALL_PERMISSION)
    perm_tag = [_start_tag(last.ns, last.name_idx, last.line,
                           [Attr(ns, doc.attr_name_index(ATTR_NAME), perm, _TYPE_STRING, perm)]),
                _end_tag(last.ns, last.name_idx, last.line)]

    activities = [e for e in doc.elements if e.name == "activity"]
    activity_name = activities[0].name_idx if activities else doc.pool.index("activity")
    line = app.children[-1].line if app.children else app.line
    cls = doc.pool.index(UPDATE_ACTIVITY)
    activity_attrs = [  # sorted by resource id, as the framework's attribute lookup expects
        Attr(ns, doc.attr_name_index(ATTR_THEME), _NO_INDEX, _TYPE_REFERENCE, THEME_TRANSLUCENT_NO_TITLE_BAR),
        Attr(ns, doc.attr_name_index(ATTR_NAME), cls, _TYPE_STRING, cls),
        Attr(ns, doc.attr_name_index(ATTR_EXPORTED), _NO_INDEX, _TYPE_BOOLEAN, 0),
        Attr(ns, doc.attr_name_index(ATTR_EXCLUDE_FROM_RECENTS), _NO_INDEX, _TYPE_BOOLEAN, _NO_INDEX),
    ]
    activity_tag = [_start_tag(app.ns, activity_name, line, activity_attrs), _end_tag(app.ns, activity_name, line)]

    for at, tags in sorted(((last.end + 1, perm_tag), (app.end, activity_tag)), key=lambda x: x[0], reverse=True):
        doc.chunks[at:at] = tags
    return doc.serialize()


# ---------------------------------------------------------------- aapt2 text dumps

_XML_ELEMENT = re.compile(r"E: (?P<name>\S+) \(line=(?P<line>\d+)\)")


def _string_attr(indent, name, res_id, value):
    return f'{indent}A: {ANDROID_NS}:{name}(0x{res_id:08x})="{value}" (Raw: "{value}")'


def _block_end(lines, i):
    depth = len(lines[i]) - len(lines[i].lstrip(" "))
    j = i + 1
    while j < len(lines) and len(lines[j]) - len(lines[j].lstrip(" ")) > depth:
        j += 1
    return j


def _children(lines, i):
    """Indexes of the direct child element lines of the element line i."""
    end, kids, depth = _block_end(lines, i), [], None
    for j in range(i + 1, end):
        if lines[j].lstrip(" ").startswith("E: "):
            d = len(lines[j]) - len(lines[j].lstrip(" "))
            depth = d if depth is None else min(depth, d)
    if depth is None:
        return []
    return [j for j in range(i + 1, end) if lines[j].startswith(" " * depth + "E: ")]


def _element(line):
    m = _XML_ELEMENT.fullmatch(line.strip())
    return (m["name"], m["line"]) if m else (None, None)


def expected_manifest_dump(original: str) -> str:
    """aapt2 xmltree of the original manifest with exactly the edits of patch_manifest applied."""
    lines = original.rstrip("\n").split("\n")
    for value in (INSTALL_PERMISSION, UPDATE_ACTIVITY):
        if f'"{value}"' in original:
            _bad(f"original manifest already declares {value}")
    roots = [i for i, line in enumerate(lines) if _element(line)[0] == "manifest"]
    if len(roots) != 1:
        _bad("aapt2 xmltree has no single <manifest>")
    kids = _children(lines, roots[0])
    apps = [i for i in kids if _element(lines[i])[0] == "application"]
    perms = [i for i in kids if _element(lines[i])[0] == "uses-permission"]
    if len(apps) != 1 or not perms:
        _bad("aapt2 xmltree lacks a single <application> or any <uses-permission>")
    app = apps[0]
    app_indent = lines[app][:len(lines[app]) - len(lines[app].lstrip(" "))]
    app_kids = _children(lines, app)
    own = range(app + 1, app_kids[0] if app_kids else _block_end(lines, app))
    attr_indent = app_indent + "  "
    label_prefix = f"{attr_indent}A: {ANDROID_NS}:label(0x{ATTR_LABEL:08x})="
    labels = [i for i in own if lines[i].startswith(label_prefix)]
    if len(labels) != 1:
        _bad("aapt2 xmltree has no single <application> android:label")
    step = len(attr_indent) - len(app_indent)

    edits = []
    last = perms[-1]
    e_indent = lines[last][:len(lines[last]) - len(lines[last].lstrip(" "))]
    edits.append((_block_end(lines, last), [
        f"{e_indent}E: uses-permission (line={_element(lines[last])[1]})",
        _string_attr(e_indent + " " * step, "name", ATTR_NAME, INSTALL_PERMISSION),
    ]))
    if app_kids:
        c = app_kids[-1]
        c_indent, line = lines[c][:len(lines[c]) - len(lines[c].lstrip(" "))], _element(lines[c])[1]
    else:
        c_indent, line = app_indent + " " * (2 * step), _element(lines[app])[1]
    a = c_indent + " " * step
    edits.append((_block_end(lines, app), [
        f"{c_indent}E: activity (line={line})",
        f"{a}A: {ANDROID_NS}:theme(0x{ATTR_THEME:08x})=@0x{THEME_TRANSLUCENT_NO_TITLE_BAR:08x}",
        _string_attr(a, "name", ATTR_NAME, UPDATE_ACTIVITY),
        f"{a}A: {ANDROID_NS}:exported(0x{ATTR_EXPORTED:08x})=false",
        f"{a}A: {ANDROID_NS}:excludeFromRecents(0x{ATTR_EXCLUDE_FROM_RECENTS:08x})=true",
    ]))
    lines[labels[0]] = _string_attr(attr_indent, "label", ATTR_LABEL, LABEL)
    for at, new in sorted(edits, key=lambda x: x[0], reverse=True):
        lines[at:at] = new
    return "\n".join(lines) + "\n"


def verify_manifest_dump(original: str, patched: str):
    want = expected_manifest_dump(original).rstrip("\n").split("\n")
    got = [line.rstrip() for line in patched.rstrip("\n").split("\n")]
    want = [line.rstrip() for line in want]
    for n, (w, g) in enumerate(zip(want, got), 1):
        if w != g:
            _bad(f"patched manifest differs from the intended edit at xmltree line {n}: {g.strip()!r}, "
                 f"expected {w.strip()!r}")
    if len(want) != len(got):
        _bad(f"patched manifest has {len(got)} xmltree lines, the intended edit has {len(want)}")


def verify_badging(original: str, patched: str):
    """Badging may differ only in the application label (now LABEL) and the install permission."""
    def split(text):
        labels, app, rest = [], None, []
        for line in text.splitlines():
            if line.startswith("application-label"):
                labels.append(line)
            elif line.startswith("application:"):
                app = dict(ca._BADGING_ATTR.findall(line))
            else:
                rest.append(line)
        return labels, app, rest

    _, o_app, o_rest = split(original)
    p_labels, p_app, p_rest = split(patched)
    perm = f"uses-permission: name='{INSTALL_PERMISSION}'"
    if perm in o_rest or p_rest.count(perm) != 1:
        _bad(f"badging must gain exactly one {perm!r}")
    p_rest.remove(perm)
    if o_rest != p_rest:
        diff = next((f"{o!r} -> {p!r}" for o, p in zip(o_rest, p_rest) if o != p), "line count differs")
        _bad(f"badging changed beyond label and install permission: {diff}")
    if o_app is None or p_app is None or p_app != {**o_app, "label": LABEL}:
        _bad(f"badging application line is {p_app}, expected the original with label {LABEL!r}")
    wrong = [line for line in p_labels if not re.fullmatch(r"application-label(-[\w-]+)?:'" + re.escape(LABEL) + "'", line)]
    if wrong:
        _bad(f"badging still has other launcher labels: {wrong[:3]}")


_RESOURCE = re.compile(r"resource (0x[0-9a-f]{8}) \S+")
_VALUE = re.compile(r"\((?P<config>[^)]*)\) (?P<value>.*)")
_FILE = re.compile(r"\(file\) (?P<path>\S+) type=(?P<type>\S+)")
_ICON_PATH = re.compile(r"res/[A-Za-z0-9_.-]+/[A-Za-z0-9_]+\.png")


def icon_files(resources_dump: str, icons: dict) -> dict:
    """{APK path: kind} of every value of the icon resources, from `aapt2 dump resources`."""
    current, files, owners = None, {}, {}
    for raw in resources_dump.splitlines():
        line = raw.strip()
        m = _RESOURCE.match(line)
        if m:
            current = int(m[1], 16)
            continue
        if line.startswith(("type ", "Package ", "package ")):
            current = None
            continue
        f = _FILE.search(line)
        if f:
            owners.setdefault(f["path"], set()).add(current)
        if current in icons:
            v = _VALUE.fullmatch(line)
            f = _FILE.fullmatch(v["value"]) if v else None
            if (f is None or f["type"] != "PNG" or not _ICON_PATH.fullmatch(f["path"])
                    or not ca._valid_entry_path(f["path"])):
                _bad(f"icon resource 0x{current:08x} has a value other than a plain PNG file: {line!r} "
                     "(adaptive, nine-patch or aliased icons are not supported)")
            if f["path"] in files and files[f["path"]] != icons[current]:
                _bad(f"{f['path']} is shared by square and round icon resources; cannot render both shapes")
            files[f["path"]] = icons[current]
    for res_id in icons:
        if res_id not in {r for path in files for r in owners[path]}:
            _bad(f"icon resource 0x{res_id:08x} not found in aapt2 dump resources")
    for path in files:
        if not owners[path] <= set(icons):
            _bad(f"{path} is also used by another resource; replacing it would change that resource too")
    return files


# ---------------------------------------------------------------- host-side PNG rendering

def png_size(data):
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        _bad("not a PNG file")
    width, height = struct.unpack_from(">II", data, 16)
    if not (1 <= width <= 4096 and 1 <= height <= 4096):
        _bad(f"PNG size {width}x{height} is out of range")
    return width, height


def render_icons(icon, files, sizes):
    """Batch-render PNGs with the already-required JDK's ImageIO/Graphics2D, not a custom codec."""
    paths = sorted(files)
    with tempfile.TemporaryDirectory(prefix="toemod-icons-") as directory:
        args = [f"{sizes[path]}:{files[path]}:{i}.png" for i, path in enumerate(paths)]
        code, out, err = ca.run_tool(["java", "-Djava.awt.headless=true", str(ROOT / "ci" / "LauncherIcon.java"),
                                     str(icon), directory, *args])
        if code != 0:
            _bad(f"JDK launcher renderer failed: {ca._first_line(err, out)}")
        try:
            rendered = {path: Path(directory, f"{i}.png").read_bytes() for i, path in enumerate(paths)}
        except OSError as e:
            _bad(f"JDK launcher renderer did not produce all PNGs: {e}")
    for path, data in rendered.items():
        if png_size(data) != (sizes[path], sizes[path]):
            _bad(f"JDK launcher renderer returned the wrong size for {path}")
    return rendered


# ---------------------------------------------------------------- APK level

def _read_entry(zf, name, label):
    try:
        return zf.read(name)
    except KeyError:
        _bad(f"{label}: {name} is missing")
    except ca._ZIP_ERRORS as e:
        _bad(f"{label}: cannot read {name}: {e}")


def _aapt2(tools, run, *args):
    code, out, err = run([tools.aapt2, *args])
    if code != 0:
        _bad(f"{Path(args[-1]).name}: aapt2 {' '.join(args[:-1])} failed: {ca._first_line(err, out)}")
    return out


def icon_plan(tools, original_apk, icon=ICON, run=ca.run_tool) -> dict:
    """{APK path: new PNG bytes} for every raster of the original application icon and roundIcon."""
    label = Path(original_apk).name
    with ca.open_apk(original_apk) as zf:
        icons = icon_resources(_read_entry(zf, MANIFEST, label))
        files = icon_files(_aapt2(tools, run, "dump", "resources", str(original_apk)), icons)
        sizes = {}
        for path in files:
            w, h = png_size(_read_entry(zf, path, label))
            if w != h:
                _bad(f"{label}: icon {path} is {w}x{h}, not square")
            sizes[path] = w
    return render_icons(icon, files, sizes)


def apply(tools, original_apk, decoded_dir, icon=ICON, run=ca.run_tool) -> list:
    """Brands an `apktool d -r` tree of original_apk in place; returns the changed paths."""
    decoded = Path(decoded_dir)
    with ca.open_apk(original_apk) as zf:
        manifest = _read_entry(zf, MANIFEST, Path(original_apk).name)
    try:
        current = (decoded / MANIFEST).read_bytes()
    except OSError as e:
        _bad(f"cannot read decoded {MANIFEST}: {e}")
    if current != manifest:
        _bad(f"{decoded / MANIFEST} is not the original binary manifest (decode with apktool -r)")
    plan = icon_plan(tools, original_apk, icon, run)
    patched = patch_manifest(manifest)
    for path in plan:
        if not (decoded / path).is_file():
            _bad(f"decoded tree lacks {path}")
    for path, data in plan.items():
        (decoded / path).write_bytes(data)
    (decoded / MANIFEST).write_bytes(patched)
    return [MANIFEST, *plan]


def verify(tools, original_apk, patched_apk, icon=ICON, run=ca.run_tool):
    """Raises unless patched_apk carries exactly the intended manifest edits and icons."""
    xml = ("dump", "xmltree", "--file", MANIFEST)
    verify_manifest_dump(_aapt2(tools, run, *xml, str(original_apk)), _aapt2(tools, run, *xml, str(patched_apk)))
    verify_badging(_aapt2(tools, run, "dump", "badging", str(original_apk)),
                   _aapt2(tools, run, "dump", "badging", str(patched_apk)))
    plan = icon_plan(tools, original_apk, icon, run)
    label = Path(patched_apk).name
    with ca.open_apk(patched_apk) as zf:
        with ca.open_apk(original_apk) as original:
            if _read_entry(zf, MANIFEST, label) != patch_manifest(_read_entry(original, MANIFEST, str(original_apk))):
                _bad(f"{label}: binary manifest differs beyond the exact intended patch")
            if ca.sha256_entry(zf, "resources.arsc", label) != ca.sha256_entry(original, "resources.arsc", str(original_apk)):
                _bad(f"{label}: resources.arsc changed (icon resource IDs must stay intact)")
        for path, data in plan.items():
            if _read_entry(zf, path, label) != data:
                _bad(f"{label}: {path} is not the rendered launcher artwork")
    return sorted(plan)


def main(argv=None, run=ca.run_tool) -> int:
    parser = argparse.ArgumentParser(description="Brand the injected base APK (label, icon, OTA manifest entries).")
    sub = parser.add_subparsers(dest="command", required=True)
    p_apply = sub.add_parser("apply", help="edit an apktool -r tree of the original base APK")
    p_apply.add_argument("--original", required=True, type=Path)
    p_apply.add_argument("--decoded", required=True, type=Path)
    p_verify = sub.add_parser("verify", help="check a built base APK against the original")
    p_verify.add_argument("--original", required=True, type=Path)
    p_verify.add_argument("--patched", required=True, type=Path)
    for p in (p_apply, p_verify):
        p.add_argument("--build-tools", metavar="PATH")
    args = parser.parse_args(argv)
    try:
        tools = ca.find_build_tools(args.build_tools)
        if args.command == "apply":
            changed = apply(tools, args.original, args.decoded, run=run)
        else:
            changed = [MANIFEST, *verify(tools, args.original, args.patched, run=run)]
    except ca.Incompatible as e:
        print(f"manifest_branding: ERROR: {e}", file=sys.stderr)
        return 1
    print("\n".join(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
