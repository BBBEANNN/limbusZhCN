"""Join metadata-only coverage and native reports, requiring matching row hashes."""
import argparse
import csv
import json
from collections import Counter
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('directory', type=Path, help='Directory containing records.tsv')
parser.add_argument('native_report', type=Path)
args = parser.parse_args()
directory = args.directory
with (directory / 'records.tsv').open(encoding='utf-8', newline='') as stream:
    rows = list(csv.DictReader(stream, delimiter='\t'))
with args.native_report.open(encoding='utf-8', newline='') as stream:
    native = list(csv.DictReader(stream, delimiter='\t'))
position = 0
for row in rows:
    row['runtimeResult'] = 'file_metadata'
    row['stablePasses'] = 'not_applicable'
    row['stableFormattedCopy'] = 'not_applicable'
    if row['field']:
        assert row['sourceHash64'] == native[position]['sourceHash64'], position
        row['runtimeResult'] = native[position]['result']
        # 兼容旧版单次查找报告，但不能把未测量的多轮稳定性算作通过。
        row['stablePasses'] = native[position].get('stablePasses', 'not_measured')
        row['stableFormattedCopy'] = native[position].get('stableFormattedCopy', 'not_measured')
        position += 1
assert position == len(native)
review = [row for row in rows if row['stablePasses'] == '0' or row['stableFormattedCopy'] == '0' or
          row['runtimeResult'] in ('different_from_pair', 'translated_without_pair') or
          (row['runtimeResult'] == 'unchanged' and row['status'] in ('ambiguous_source', 'missing_translation', 'invalid_text'))]
for name, values in [('coverage-complete.tsv', rows), ('needs-review.tsv', review)]:
    with (directory / name).open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, list(rows[0]), delimiter='\t')
        writer.writeheader()
        writer.writerows(values)
summary = {'rows': position, 'reviewRows': len(review),
           'repeatedPasses': dict(Counter(row['stablePasses'] for row in rows if row['field'])),
           'formattedCopies': dict(Counter(row['stableFormattedCopy'] for row in rows if row['field'])),
           'runtime': dict(Counter(row['runtimeResult'] for row in rows)),
           'reviewCategories': dict(Counter(row['status'] + '/' + row['runtimeResult'] for row in review)),
           'missingUnchangedResources': dict(Counter(row['resource'] for row in review if row['status'] == 'missing_translation' and row['runtimeResult'] == 'unchanged'))}
(directory / 'coverage-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False, indent=2))
