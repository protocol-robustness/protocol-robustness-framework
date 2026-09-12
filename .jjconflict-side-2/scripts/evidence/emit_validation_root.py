#!/usr/bin/env python3
import json
import sys
import os
import argparse
from pathlib import Path

from evidence_config import EvidenceConfig

CRITICAL_ERROR_KEYS = {
    'registry/dangling-dependency', 'artifact/hash-mismatch',
    'replay/non-deterministic', 'invariant/broken',
    'financial-finality/invalid', 'evidence/binding-mismatch'
}


def load_suite_json(path):
    with open(path, 'r') as f:
        return json.load(f)


def merge_validation_results(root, suite_result):
    root['status_keys'].update(suite_result.get('status_keys', []))
    root['error_keys'].update(suite_result.get('error_keys', []))
    root['warning_keys'].update(suite_result.get('warning_keys', []))
    root['errors'].extend(suite_result.get('errors', []))
    root['warnings'].extend(suite_result.get('warnings', []))

    suite_id = suite_result.get('suite/id')
    root['suite_results'][suite_id] = suite_result

    m1 = root['metrics']
    m2 = suite_result.get('metrics', {})
    root['metrics'] = {
        'checks': m1.get('checks', 0) + m2.get('checks', 0),
        'passed': m1.get('passed', 0) + m2.get('passed', 0),
        'failed': m1.get('failed', 0) + m2.get('failed', 0),
        'warnings': m1.get('warnings', 0) + m2.get('warnings', 0)
    }
    return root


def derive_status(root):
    error_keys = set(root['error_keys'])
    if error_keys & CRITICAL_ERROR_KEYS:
        return 'failed-critical'
    if error_keys:
        return 'failed'
    if root['warning_keys']:
        return 'warning'
    return 'passed'


def main():
    cfg = EvidenceConfig()
    parser = argparse.ArgumentParser()
    parser.add_argument('--input-dir', required=True)
    parser.add_argument('--output-dir', required=True)
    args = parser.parse_args()

    root = {
        'status': 'unknown',
        'status_keys': set(),
        'error_keys': set(),
        'warning_keys': set(),
        'errors': [],
        'warnings': [],
        'suite_results': {},
        'metrics': {'checks': 0, 'passed': 0, 'failed': 0, 'warnings': 0}
    }

    input_path = Path(args.input_dir)
    for suite_file in input_path.glob('suite-*.json'):
        suite_res = load_suite_json(suite_file)
        merge_validation_results(root, suite_res)

    root['status_keys'] = list(root['status_keys'])
    root['error_keys'] = list(root['error_keys'])
    root['warning_keys'] = list(root['warning_keys'])
    root['status'] = derive_status(root)
    root['schema_version'] = cfg.schema('validation-root')

    output_path = Path(args.output_dir)
    out_file = cfg.artifact('validation-root')['file']
    with open(output_path / out_file, 'w') as f:
        json.dump(root, f, indent=2)
    print(f"Validation root written to {output_path / out_file}")


if __name__ == '__main__':
    main()
