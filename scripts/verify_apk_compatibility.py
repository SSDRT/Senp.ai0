"""Inspect native ABI/ELF/ZIP alignment in a built APK without executing its binaries.

Static 16 KB checks do not replace an actual 16 KB Android runtime test.
Usage: python scripts/verify_apk_compatibility.py APK --output report.json
"""
import argparse
import json
import struct
import zipfile
from pathlib import Path


def inspect(apk):
    rows = []
    with zipfile.ZipFile(apk) as archive, open(apk, "rb") as raw:
        for info in archive.infolist():
            if not (info.filename.startswith("lib/") and info.filename.endswith(".so")):
                continue
            binary = archive.read(info)
            if binary[:4] != b"\x7fELF":
                raise ValueError(f"Not an ELF library: {info.filename}")
            endian = "<" if binary[5] == 1 else ">"
            is64 = binary[4] == 2
            if is64:
                offset = struct.unpack_from(endian + "Q", binary, 32)[0]
                entry_size, count = struct.unpack_from(endian + "HH", binary, 54)
                fmt, alignment_index = "IIQQQQQQ", 7
            else:
                offset = struct.unpack_from(endian + "I", binary, 28)[0]
                entry_size, count = struct.unpack_from(endian + "HH", binary, 42)
                fmt, alignment_index = "IIIIIIII", 7
            load_alignments = []
            for index in range(count):
                fields = struct.unpack_from(endian + fmt, binary, offset + index * entry_size)
                if fields[0] == 1:
                    load_alignments.append(fields[alignment_index])
            raw.seek(info.header_offset)
            header = raw.read(30)
            name_length, extra_length = struct.unpack_from("<HH", header, 26)
            data_offset = info.header_offset + 30 + name_length + extra_length
            rows.append({
                "library": info.filename,
                "abi": info.filename.split("/")[1],
                "elf_bits": 64 if is64 else 32,
                "load_segment_alignments": load_alignments,
                "elf_16kb_aligned": bool(load_alignments) and min(load_alignments) >= 16384,
                "compressed": info.compress_type != zipfile.ZIP_STORED,
                "zip_data_offset": data_offset,
                "zip_16kb_aligned": info.compress_type != zipfile.ZIP_STORED or data_offset % 16384 == 0,
            })
    relevant = [row for row in rows if row["elf_bits"] == 64]
    return {
        "apk": str(Path(apk).resolve()),
        "abis": sorted({row["abi"] for row in rows}),
        "static_16kb_pass": bool(relevant) and all(row["elf_16kb_aligned"] and row["zip_16kb_aligned"] for row in relevant),
        "runtime_16kb_verified": False,
        "libraries": rows,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = inspect(args.apk)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "libraries"}, indent=2))
    raise SystemExit(0 if result["static_16kb_pass"] else 1)
