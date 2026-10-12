#!/usr/bin/env python3
"""Exercise the existing controlled-source validator; retain every mutated input."""
import argparse
import copy
import json
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--replay', required=True)
parser.add_argument('--witness', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
original = json.loads(args.witness.read_text())
mutations = {
    'changed-request': lambda value: value['realized-statement-input']['requested'].update({'claim-a': '51'}),
    'changed-disposition-policy': lambda value: value['realized-statement-input'].update({'fail-action-policy': {'mode': 'pro-rata-treatment', 'deferred-policy': 'same-ratio', 'haircut-policy': 'same-ratio'}}),
    'changed-source-commitment': lambda value: value['prepared-operation'].update({'policy-root': '0x' + '00' * 32}),
    'caller-supplied-allocation': lambda value: value.update({'allocated': {'claim-a': '40', 'claim-b': '21', 'claim-c': '14'}}),
}
results = []
for name, mutate in mutations.items():
    value = copy.deepcopy(original)
    mutate(value)
    file = args.output / (name + '.json')
    file.write_text(json.dumps(value, indent=2) + '\n')
    run = subprocess.run([args.replay], input=file.read_text(), text=True, capture_output=True)
    if run.returncode == 0:
        raise AssertionError(f'{name}: invalid source was accepted')
    results.append({'case': name, 'input': file.name, 'result': 'rejected',
                    'exitCode': run.returncode, 'diagnostic': run.stderr.strip(),
                    'boundary': 'native controlled-source derivation; no EVM transaction attempted'})
(args.output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
print(f'{len(results)} controlled-source substitutions rejected; mutated inputs retained')
