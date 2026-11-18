#!/usr/bin/env python3
"""Check a real Full Release R8 mapping against the bundled Sherpa AAR.

Usage (Python 3.9+, no third-party dependencies):
  python scripts/check_sherpa_jni_mapping.py
Run :app:minifyFullReleaseWithR8 first. Checks the matching mapping AND DEX
outputs: R8 may omit unchanged fields/native methods from mapping.txt. Custom
--mapping and --dex-dir must come from the same build. This checks class/member
names (not signatures or runtime behavior), not model-loading or microphone
recognition. Lite intentionally has no Sherpa.
"""
import argparse
import io
from pathlib import Path
import re
import struct
import sys
import zipfile

PREFIX = "com.k2fsa.sherpa.onnx."
ROOT = Path(__file__).resolve().parents[1]


def class_contract(data):
    """Read names from the class file, not a hardcoded list of config fields."""
    stream = io.BytesIO(data)

    def u2():
        return struct.unpack(">H", stream.read(2))[0]

    def skip_attributes():
        for _ in range(u2()):
            u2()
            size = struct.unpack(">I", stream.read(4))[0]
            stream.read(size)

    if stream.read(4) != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid Java class magic")
    stream.read(4)  # minor, major
    pool = [None] * u2()
    index = 1
    while index < len(pool):
        tag = stream.read(1)[0]
        if tag == 1:
            pool[index] = stream.read(u2()).decode("utf-8", errors="replace")
        elif tag == 7:
            pool[index] = u2()
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            stream.read(4)
        elif tag in (5, 6):
            stream.read(8)
            index += 1
        elif tag in (8, 16, 19, 20):
            stream.read(2)
        elif tag == 15:
            stream.read(3)
        else:
            raise ValueError(f"Unknown constant pool tag {tag}")
        index += 1
    u2()  # access flags
    name = pool[pool[u2()]].replace("/", ".")
    u2()  # superclass
    stream.read(2 * u2())  # interfaces
    fields, natives = set(), set()
    for members, native_only in ((fields, False), (natives, True)):
        for _ in range(u2()):
            access, member_name = u2(), pool[u2()]
            u2()  # descriptor
            if not native_only or access & 0x0100:
                members.add(member_name)
            skip_attributes()
    return name, fields, natives


def aar_contract(path):
    with zipfile.ZipFile(path) as aar:
        with zipfile.ZipFile(io.BytesIO(aar.read("classes.jar"))) as jar:
            contracts = [class_contract(jar.read(n)) for n in jar.namelist()
                         if n.startswith(PREFIX.replace(".", "/")) and n.endswith(".class")]
    if not contracts:
        raise ValueError("AAR contains no Sherpa classes; refusing a vacuous pass")
    return contracts


def parse_mapping(text):
    classes = {}
    current = None
    for line in text.splitlines():
        if not line or line.lstrip().startswith("#"):
            continue
        if not line[0].isspace():
            match = re.fullmatch(r"(\S+) -> (\S+):", line)
            current = None
            if match:
                original, renamed = match.groups()
                current = (renamed, {}, {})
                classes[original] = current
        elif current and " -> " in line:
            original, renamed = line.strip().rsplit(" -> ", 1)
            # R8 method mappings may have line-number ranges before/after signatures.
            if "(" in original:
                match = re.search(r"([^\s:]+)\([^)]*\)", original)
                if match:
                    current[2].setdefault(match[1], set()).add(renamed)
            else:
                current[1][original.rsplit(" ", 1)[-1]] = renamed
    return classes


def dex_contract(data):
    """Read defined fields/native methods, including identities R8 omits in mapping."""
    if not data.startswith(b"dex\n"):
        raise ValueError("Invalid DEX magic")

    def u4(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def uleb(offset):
        value, shift = 0, 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, offset
            shift += 7
            if shift > 35:
                raise ValueError("Invalid DEX ULEB128")

    strings = []
    for i in range(u4(56)):
        offset = u4(u4(60) + i * 4)
        _, offset = uleb(offset)
        strings.append(data[offset:data.index(b"\0", offset)].decode("utf-8", errors="replace"))
    types = [strings[u4(u4(68) + i * 4)] for i in range(u4(64))]
    result = {}
    for i in range(u4(96)):
        offset = u4(100) + i * 32
        name = types[u4(offset)][1:-1].replace("/", ".")
        if not name.startswith(PREFIX):
            continue
        fields, natives = set(), set()
        result[name] = (name, fields, natives)
        offset = u4(offset + 24)
        if not offset:
            continue
        counts = []
        for _ in range(4):
            count, offset = uleb(offset)
            counts.append(count)
        for kind, count in enumerate(counts):
            index = 0
            for _ in range(count):
                delta, offset = uleb(offset)
                index += delta
                access, offset = uleb(offset)
                if kind < 2:
                    fields.add(strings[u4(u4(84) + index * 8 + 4)])
                else:
                    _, offset = uleb(offset)  # code offset
                    if access & 0x0100:
                        natives.add(strings[u4(u4(92) + index * 8 + 4)])
    return result


def check_contract(contracts, mapping, definitions=None):
    errors = []
    for name, fields, natives in contracts:
        entry = mapping.get(name)
        if entry is None:
            errors.append(f"Missing class: {name}")
            continue
        renamed, mapped_fields, mapped_methods = entry
        if definitions is not None and name not in definitions:
            errors.append(f"Missing DEX class: {name}")
        if renamed != name:
            errors.append(f"Renamed class: {name} -> {renamed}")
        for field in sorted(fields):
            # Identity field/native entries are legitimately omitted by R8.
            actual = mapped_fields.get(field, field)
            if actual == field and definitions is not None and field not in definitions.get(name, (name, set(), set()))[1]:
                actual = None
            if actual != field:
                errors.append(f"Field: {name}.{field} -> {actual or '<missing>'}")
        for method in sorted(natives):
            actual = mapped_methods.get(method, {method})
            if definitions is not None and method not in definitions.get(name, (name, set(), set()))[2]:
                actual = set()
            if actual != {method}:
                errors.append(f"Native method: {name}.{method} -> {sorted(actual) or '<missing>'}")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=Path, default=ROOT / "app/libs/sherpa-onnx-1.13.0.aar")
    parser.add_argument("--mapping", type=Path, default=ROOT / "app/build/outputs/mapping/fullRelease/mapping.txt")
    parser.add_argument("--dex-dir", type=Path, default=ROOT / "app/build/intermediates/dex/fullRelease/minifyFullReleaseWithR8",
                        help="Matching R8 DEX directory; checks actual definitions when mapping omits identities")
    args = parser.parse_args()
    try:
        contracts = aar_contract(args.aar)
        dex_files = sorted(args.dex_dir.glob("classes*.dex"))
        if not dex_files:
            raise ValueError(f"No R8 DEX files in {args.dex_dir}")
        definitions = {}
        for dex in dex_files:
            definitions.update(dex_contract(dex.read_bytes()))
        errors = check_contract(contracts, parse_mapping(args.mapping.read_text(encoding="utf-8")), definitions)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, struct.error, IndexError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    if errors:
        print("FAIL: Sherpa JNI names were removed or renamed:", file=sys.stderr)
        for error in errors:
            print(f"  {error}", file=sys.stderr)
        return 1
    print(f"PASS: {len(contracts)} Sherpa classes, "
          f"{sum(len(c[1]) for c in contracts)} fields and "
          f"{sum(len(c[2]) for c in contracts)} native method names retain their JNI ABI")
    return 0


if __name__ == "__main__":
    sys.exit(main())
