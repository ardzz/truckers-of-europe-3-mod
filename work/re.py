#!/usr/bin/env python3
"""RE helper for Truckers of Europe 3 libil2cpp.so (arm64, Unity 2022.3, IL2CPP v31).

Address model (verified):
  RVA in the Cpp2IL dump == file offset in libil2cpp.so
  runtime/vaddr (what rizin shows)  == RVA + 0x4000   (code segment only)
All commands below take/print *vaddr* unless stated.

Usage:
  re.py find  <regex>                 # search methods "Class :: signature" -> vaddr, len
  re.py dis   <vaddr|Class::method> [n_insns]   # disassemble (default: whole method)
  re.py callers <vaddr|Class::method>  # direct BL callers
  re.py field <hex_offset> [regex_filter]  # ldr/str w/s/x and ldrb/strb at [xN, #off]
  re.py owner <vaddr>                 # which method contains vaddr
  re.py fields <ClassFileStem>        # print field list of class
  re.py f32 <vaddr>                   # read float constant at data vaddr (LOAD0: vaddr==offset)
"""
import bisect, json, os, re, struct, subprocess, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SO = os.path.join(HERE, 'lib/arm64-v8a/libil2cpp.so')  # ORIGINAL, unpatched
CS = os.path.join(HERE, 'cs/DiffableCs')
CACHE = os.path.join(HERE, 'methods.json')
SHIFT = 0x4000

ADDR_RE = re.compile(r'\[Address\(RVA = "(0x[0-9A-F]+)", Offset = "(0x[0-9A-F]+)", Length = "(0x[0-9A-F]+)"\)\]')


def build_index():
    if os.path.exists(CACHE):
        return json.load(open(CACHE))
    out = []
    for dp, _, fs in os.walk(CS):
        for fn in fs:
            if not fn.endswith('.cs'):
                continue
            p = os.path.join(dp, fn)
            rel = os.path.relpath(p, CS)[:-3]
            pending = None
            for line in open(p, errors='ignore'):
                m = ADDR_RE.search(line)
                if m:
                    pending = m.groups(); continue
                s = line.strip()
                if pending and '(' in s and not s.startswith('['):
                    out.append([int(pending[0], 16) + SHIFT, int(pending[2], 16), rel, s]); pending = None
    out.sort()
    json.dump(out, open(CACHE, 'w'))
    return out


M = build_index()
STARTS = [m[0] for m in M]
DATA = open(SO, 'rb').read()


def owner(va):
    i = bisect.bisect_right(STARTS, va) - 1
    if i >= 0 and va < M[i][0] + M[i][1]:
        return M[i]


def resolve(spec):
    if spec.startswith('0x'):
        va = int(spec, 16); o = owner(va)
        return va, (o[1] if o else 0x40)
    cls, meth = spec.split('::')
    hits = [m for m in M if (m[2].endswith('/' + cls) or m[2] == cls) and re.search(r'\b' + re.escape(meth) + r'\(', m[3])]
    if len(hits) != 1:
        for h in hits: print(hex(h[0]), h[2], h[3])
        sys.exit(f'{len(hits)} matches for {spec}; pass a vaddr')
    return hits[0][0], hits[0][1]


def rz(va, n):
    return subprocess.run(['rizin', '-q', '-e', 'scr.color=0', '-e', 'asm.bytes=true', '-e', 'asm.lines=false',
                           '-c', f'pd {n} @ {va}', SO], capture_output=True, text=True).stdout


_words = None


def words():
    global _words
    if _words is None:
        off, va, sz = 0x15bf54c, 0x15c354c, 0x22e07b4
        st = (off + 3) & ~3
        end = off + sz; end -= (end - st) % 4
        _words = (np.frombuffer(DATA[st:end], dtype='<u4'), st + SHIFT)
    return _words


def callers(va):
    w, va0 = words()
    isbl = (w >> 26) == 0b100101
    imm = (w & 0x03FFFFFF).astype(np.int64); imm = np.where(imm & 0x2000000, imm - 0x4000000, imm)
    tg = va0 + np.arange(len(w), dtype=np.int64) * 4 + imm * 4
    for h in np.nonzero(isbl & (tg == va))[0]:
        c = va0 + int(h) * 4; o = owner(c)
        print(hex(c), (o[2] + ' :: ' + o[3]) if o else '?')


def field(off, filt=None):
    w, va0 = words()
    encs = {}
    if off % 4 == 0:
        i = off >> 2
        encs.update({'ldr w': 0xB9400000 | i << 10, 'str w': 0xB9000000 | i << 10,
                     'ldr s': 0xBD400000 | i << 10, 'str s': 0xBD000000 | i << 10})
    if off % 8 == 0:
        i = off >> 3
        encs.update({'ldr x': 0xF9400000 | i << 10, 'str x': 0xF9000000 | i << 10})
    encs.update({'ldrb': 0x39400000 | off << 10, 'strb': 0x39000000 | off << 10})
    res = {}
    for k, e in encs.items():
        for h in np.nonzero((w & 0xFFFFFC00) == e)[0]:
            c = va0 + int(h) * 4; o = owner(c); key = (o[2] + ' :: ' + o[3]) if o else '?'
            if filt and not re.search(filt, key): continue
            res.setdefault(key, []).append(f'{k}@{hex(c)}')
    for k, v in sorted(res.items()): print(k, v)


def main():
    a = sys.argv[1:]
    if not a: print(__doc__); return
    cmd = a[0]
    if cmd == 'find':
        for m in M:
            if re.search(a[1], m[2] + ' :: ' + m[3]): print(hex(m[0]), hex(m[1]), m[2], '::', m[3])
    elif cmd == 'dis':
        va, ln = resolve(a[1]); n = int(a[2]) if len(a) > 2 else ln // 4
        o = owner(va); print('#', o[2] + ' :: ' + o[3] if o else '?'); print(rz(va, n))
    elif cmd == 'callers':
        callers(resolve(a[1])[0])
    elif cmd == 'field':
        field(int(a[1], 16), a[2] if len(a) > 2 else None)
    elif cmd == 'owner':
        print(owner(int(a[1], 16)))
    elif cmd == 'fields':
        p = [os.path.join(dp, f) for dp, _, fs in os.walk(CS) for f in fs if f == a[1] + '.cs']
        for pp in p:
            print('##', pp)
            for line in open(pp):
                if 'Field offset' in line or re.search(r'^\s*(public|private|internal).*\b(class|struct|enum)\b', line): print(line.rstrip())
    elif cmd == 'f32':
        va = int(a[1], 16); print(struct.unpack('<f', DATA[va:va + 4])[0])


if __name__ == '__main__':
    main()
