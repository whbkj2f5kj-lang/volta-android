#!/usr/bin/env python3
"""Check the German catalog against the actual patched application sources."""
import argparse
from collections import Counter
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET


def messages(path):
    tree = ET.parse(path)
    result = {}
    for context in tree.findall("context"):
        for message in context.findall("message"):
            translation = message.find("translation")
            if translation is not None and translation.get("type") in ("vanished", "obsolete"):
                continue
            key = (context.findtext("name"), message.findtext("source", ""), message.findtext("comment", ""))
            result[key] = translation
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--lupdate", default="lupdate")
    args = parser.parse_args()
    port = Path(__file__).resolve().parent.parent
    sources = [args.upstream / "qml", args.upstream / "src", port / "portqml", port / "qmlstubs"]
    sources += [port / name for name in ("android_limits.cpp", "android_tpms.cpp", "android_update.cpp")]
    with tempfile.TemporaryDirectory(prefix="volta-de-") as tmp:
        extracted = Path(tmp) / "extracted.ts"
        subprocess.run([args.lupdate, *map(str, sources), "-no-obsolete", "-locations", "none",
                        "-source-language", "en", "-target-language", "de_DE", "-ts", str(extracted)], check=True)
        required = messages(extracted)
    catalog = messages(port / "i18n/volta_de.ts")
    errors = []
    placeholders = re.compile(r"%(?:L?[1-9][0-9]*|L?n)")
    for key in required:
        translation = catalog.get(key)
        target = "" if translation is None else "".join(translation.itertext())
        if translation is None or translation.get("type") == "unfinished" or not target.strip():
            errors.append(f"Missing German translation: {key}")
            continue
        source = key[1]
        if Counter(placeholders.findall(source)) != Counter(placeholders.findall(target)):
            errors.append(f"Changed placeholders: {key}")
        if source.count("\n") != target.count("\n"):
            errors.append(f"Changed line breaks: {key}")
        # These are data accidentally marked qsTr in upstream. Translating them
        # would break drawing or executable paths rather than localize text.
        if (source.startswith(("M5 ", "M7 ", "M67.", "rgba(", "/.var/")) or " || " in source) and source != target:
            errors.append(f"Translated technical data: {key}")
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"German catalog: {len(required)} active messages covered; placeholders and technical data preserved.")


if __name__ == "__main__":
    main()
