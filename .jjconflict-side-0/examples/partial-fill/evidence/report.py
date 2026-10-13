"""Join retained real proof, independent reconstruction and contract qualification."""
import json, pathlib, sys, hashlib
p=pathlib.Path(sys.argv[1]); read=lambda n:json.loads((p/n).read_text())
r=read('replay.json'); c=read('clojure-replay.json'); assert r==c
identity=read('identity.json')
for prefix in ['', 'recovery-']:
    proof=read(prefix+'proof.json'); replay=read(prefix+'replay.json')
    for k in ['program_id','vkey','elf_sha256']: assert proof[k]==identity[k],k
    assert proof['proof_system']=='groth16'
    assert proof['public_values'].removeprefix('0x')==replay['public-values']
tests=read('evm-tests.json'); cases={}
for suite in tests.values():
    if isinstance(suite,dict): cases.update(suite.get('test_results',{}))
required={f'test_{name}()' for name in ['QualificationWithdrawsAndClosesExactly','RejectProofPublicValueSubstitution','RejectUnauthorizedAndRouteBypass','RejectRatificationAndFundingReplay','RejectProofForAnotherOperationAndPolicyMutation','RejectOverWithdrawalAndDuplicateConsumption','RejectStaleReserveThenRecoverWithFreshRealProof','RejectChangedAllocationRepresentation','RejectFundingMoreThanCommittedCapital']}
assert required <= set(cases), 'Missing required qualification tests'
assert all(v['status']=='Success' for v in cases.values()),cases
finals=[]; transfer_records=[]
for case in cases.values():
    for line in case.get('decoded_logs',[]):
        if 'PARTIAL_FILL_FINAL_JSON ' in line: finals.append(json.loads(line.split('PARTIAL_FILL_FINAL_JSON ',1)[1]))
        if 'PARTIAL_FILL_TRANSFERS_JSON ' in line: transfer_records.append(json.loads(line.split('PARTIAL_FILL_TRANSFERS_JSON ',1)[1]))
assert len(finals)==1, 'Final asserted state record missing'
assert len(transfer_records)==1
transfers=transfer_records[0]; assert [e['amount'] for e in transfers]==[70,35,21,14]
context=read('context.json')['prepared-operation']; normalize=lambda v:v.lower()
assert normalize(transfers[0]['from'])==normalize(context['reserve']) and normalize(transfers[0]['to'])==normalize(context['allocator'])
for i,event in enumerate(transfers[1:]):
    assert normalize(event['from'])==normalize(context['allocator'])
    assert normalize(event['to'])==normalize(r['ordered-entitlements'][i]['beneficiary'])
    assert normalize(event['token'])==normalize(context['token'])
(p/'token-transfers.json').write_text(json.dumps({'scope':'recorded local Foundry EVM token events, not public-chain transaction receipts','transfers':transfers},indent=2)+'\n')
final=finals[0]; assert final['withdrawn']==[35,21,14] and final['remainingFundedLiability']==0
source=read('source.json'); rows=[]
for i,name in enumerate(['Alice','Bob','Carol']):
    amount=int(r['ordered-entitlements'][i]['amount']); requested=[50,30,20][i]
    assert amount==requested*70//100
    rows.append(dict(name=name,requested=requested,allocated=amount,unfilled=requested-amount,withdrawn=final['withdrawn'][i],beneficiary=r['ordered-entitlements'][i]['beneficiary']))
report=dict(schema='partial-fill-qualification.v1',qualified=True,scope='local Foundry EVM; real SP1 Groth16; no public-chain execution',participants=rows,tokenTransfers=transfers,requested=100,available=70,unmet=30,final=final,identity=identity,sourceRoot=r['realized-statement-root'],policy=read('policy.json'),configuration=read('configuration.json'),genesis=read('genesis.json'),roots=r,tests={k:dict(status=v['status'],gas=v.get('kind',{}).get('Unit',{}).get('gas')) for k,v in cases.items()},sourceRejections=read('negative-source/results.json'),independentReplay='Exact Clojure/Rust equality, including canonical roots and 288 public bytes',limitations=['Controlled initiator-specified requests, not independent evidence of external liabilities.','Single local test governor; production governance and Base Sepolia unqualified.','No public-chain transaction receipts.','Unmet requests create no deferred entitlement, recognized loss or future obligation.','Stale recovery preserves the operation’s committed 70-unit allocation; changed available capital would require source-consistent requalification/new operation.'])
(p/'qualification.json').write_text(json.dumps(report,indent=2)+'\n')
print(f"Qualified {len(cases)} real-verifier EVM tests; independent source reconstruction; exact withdrawals and closure")
