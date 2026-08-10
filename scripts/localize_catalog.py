#!/usr/bin/env python3
"""Prepare Limbus Localize JSON for translation work.

The extractor reads either a Localize directory or a zip produced by the Android
debug snapshot exporter. It emits JSONL rows for the only fields this project is
allowed to translate: dataList[].content, dataList[].dialog, and dataList[].teller.

It can also apply a translated JSONL file back onto a source Localize tree to
produce a patch directory shaped like the app's existing translation cache:
files/en/<relative json path>.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable
from zipfile import ZipFile


TRANSLATABLE_FIELDS = ("content", "dialog", "teller")


@dataclass(frozen=True)
class SourceTree:
    root: Path
    cleanup: tempfile.TemporaryDirectory[str] | None = None

    def close(self) -> None:
        if self.cleanup is not None:
            self.cleanup.cleanup()


def open_source(path: Path) -> SourceTree:
    if path.is_dir():
        return SourceTree(path)
    if path.is_file() and path.suffix.lower() == ".zip":
        temp = tempfile.TemporaryDirectory(prefix="limbus-localize-")
        root = Path(temp.name)
        with ZipFile(path) as archive:
            archive.extractall(root)
        return SourceTree(root, temp)
    raise SystemExit(f"Input must be a Localize directory or snapshot zip: {path}")


def iter_json_files(root: Path) -> Iterable[Path]:
    en = root / "en"
    search_root = en if en.is_dir() else root
    yield from sorted(path for path in search_root.rglob("*.json") if path.is_file())


def canonical_relative(root: Path, file: Path) -> str:
    relative = file.relative_to(root).as_posix()
    if relative.startswith("en/"):
        return relative
    return f"en/{relative}"


def read_json(path: Path) -> Any | None:
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except Exception as exc:  # noqa: BLE001 - CLI should keep scanning other files.
        print(f"[warn] skip invalid JSON {path}: {exc}", file=sys.stderr)
        return None


def export_catalog(input_path: Path, output: Path, summary: Path | None) -> None:
    source = open_source(input_path)
    try:
        rows = 0
        files = 0
        field_counts = {field: 0 for field in TRANSLATABLE_FIELDS}
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("w", encoding="utf-8", newline="\n") as handle:
            for file in iter_json_files(source.root):
                data = read_json(file)
                if not isinstance(data, dict):
                    continue
                data_list = data.get("dataList")
                if not isinstance(data_list, list):
                    continue
                files += 1
                relative = canonical_relative(source.root, file)
                for index, item in enumerate(data_list):
                    if not isinstance(item, dict):
                        continue
                    item_id = item.get("id")
                    for field in TRANSLATABLE_FIELDS:
                        value = item.get(field)
                        if isinstance(value, str) and value:
                            row = {
                                "path": relative,
                                "index": index,
                                "id": item_id,
                                "field": field,
                                "source": value,
                                "translation": "",
                            }
                            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
                            handle.write("\n")
                            rows += 1
                            field_counts[field] += 1
        if summary is not None:
            summary.parent.mkdir(parents=True, exist_ok=True)
            summary.write_text(
                json.dumps(
                    {
                        "input": str(input_path),
                        "catalog": str(output),
                        "jsonFilesWithDataList": files,
                        "rows": rows,
                        "fieldCounts": field_counts,
                    },
                    ensure_ascii=False,
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
        print(f"exported {rows} rows from {files} JSON files -> {output}")
    finally:
        source.close()


def load_translations(catalog: Path) -> dict[tuple[str, int, str], str]:
    translations: dict[tuple[str, int, str], str] = {}
    with catalog.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            row = json.loads(line)
            translation = row.get("translation")
            if not isinstance(translation, str) or not translation:
                continue
            key = (str(row["path"]), int(row["index"]), str(row["field"]))
            translations[key] = translation
    return translations


def apply_catalog(input_path: Path, catalog: Path, output_dir: Path) -> None:
    translations = load_translations(catalog)
    if not translations:
        raise SystemExit(f"No non-empty translation values found in {catalog}")

    source = open_source(input_path)
    try:
        written = 0
        output_files = output_dir / "files" / "en"
        if output_dir.exists():
            shutil.rmtree(output_dir)
        output_files.mkdir(parents=True, exist_ok=True)

        for file in iter_json_files(source.root):
            relative = canonical_relative(source.root, file)
            data = read_json(file)
            if not isinstance(data, dict) or not isinstance(data.get("dataList"), list):
                continue
            changed = False
            for index, item in enumerate(data["dataList"]):
                if not isinstance(item, dict):
                    continue
                for field in TRANSLATABLE_FIELDS:
                    key = (relative, index, field)
                    if key in translations:
                        item[field] = translations[key]
                        changed = True
            if changed:
                target_relative = relative.removeprefix("en/")
                target = output_files / target_relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(
                    json.dumps(data, ensure_ascii=False, separators=(",", ":")),
                    encoding="utf-8",
                    newline="\n",
                )
                written += 1
        print(f"wrote {written} patched JSON files -> {output_dir}")
    finally:
        source.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    export = sub.add_parser("export", help="extract translatable strings to JSONL")
    export.add_argument("--input", required=True, type=Path)
    export.add_argument("--output", required=True, type=Path)
    export.add_argument("--summary", type=Path)

    apply = sub.add_parser("apply", help="apply translated JSONL to a patch directory")
    apply.add_argument("--input", required=True, type=Path)
    apply.add_argument("--catalog", required=True, type=Path)
    apply.add_argument("--output-dir", required=True, type=Path)

    args = parser.parse_args()
    if args.command == "export":
        export_catalog(args.input, args.output, args.summary)
    elif args.command == "apply":
        apply_catalog(args.input, args.catalog, args.output_dir)


if __name__ == "__main__":
    main()
