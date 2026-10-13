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
required={f'test_{name}()' for name in ['QualificationWithdrawsAndClosesExactly','RejectProofPublicValueSubstitution','RejectUnauthorizedAndRouteBypass','RejectRatificationAndFundingReplay','RejectProofForAnotherOperationAndPolicyMutation','RejectOverWithdrawalAndDuplicateConsumption','RejectStaleReserveThenRecoverWithFreshRealProof','RejectChangedAllocationRepresentation','RejectFundingMoreThanCommittedCapital','RejectCarolClaimIncludingProtectedSponsorCapital','FrozenV2HasNoTerminalProtectedRefundEntryPoint']}
assert required <= set(cases), 'Missing required qualification tests'
assert all(v['status']=='Success' for v in cases.values()),cases
finals=[]; transfer_records=[]; state_records=[]; protected_policies=[]; funding_attributions=[]
for case in cases.values():
    for line in case.get('decoded_logs',[]):
        if 'PARTIAL_FILL_FINAL_JSON ' in line: finals.append(json.loads(line.split('PARTIAL_FILL_FINAL_JSON ',1)[1]))
        if 'PARTIAL_FILL_TRANSFERS_JSON ' in line: transfer_records.append(json.loads(line.split('PARTIAL_FILL_TRANSFERS_JSON ',1)[1]))
        if 'PARTIAL_FILL_ACCOUNTING_STATE_JSON ' in line: state_records.append(json.loads(line.split('PARTIAL_FILL_ACCOUNTING_STATE_JSON ',1)[1]))
        if 'PARTIAL_FILL_PROTECTED_CAPITAL_POLICY_JSON ' in line: protected_policies.append(json.loads(line.split('PARTIAL_FILL_PROTECTED_CAPITAL_POLICY_JSON ',1)[1]))
        if 'PARTIAL_FILL_FUNDING_ATTRIBUTION_JSON ' in line: funding_attributions.append(json.loads(line.split('PARTIAL_FILL_FUNDING_ATTRIBUTION_JSON ',1)[1]))
assert len(finals)==1, 'Final asserted state record missing'
assert len(transfer_records)==1
assert len(protected_policies)==1 and len(funding_attributions)==1, 'Protected-capital policy/funding attribution missing'
context=read('context.json')['prepared-operation']; policy=protected_policies[0]; funding=funding_attributions[0]
assert policy['schema']=='eae.protected-sponsor-capital-policy.v1' and policy['version']==1
assert policy['chainId']==31337 and policy['restrictedAmount']==10
assert policy['reserve'].lower()==context['reserve'].lower() and policy['token'].lower()==context['token'].lower()
assert policy['operationId'].lower()==context['operation-id'].lower()
assert policy['sponsorAndFundingSource'].lower()==context['initiator'].lower()
assert policy['beneficialOwner'].lower()==r['ordered-entitlements'][2]['beneficiary'].lower()
assert policy['refundBeneficiary']==policy['beneficialOwner']
assert policy['releaseAuthority'].lower()!=policy['beneficialOwner'].lower()
assert policy['policyRoot'].startswith('0x') and len(policy['policyRoot'])==66
assert 'no policy-binding or protected-capital release entry point' in policy['enforcementStatus']
assert funding['sponsor'].lower()==policy['sponsorAndFundingSource'].lower()
assert funding['reserve'].lower()==policy['reserve'].lower() and funding['token'].lower()==policy['token'].lower()
assert funding['sponsorMintedByFixture']==80 and funding['sponsorTransferredToReserve']==80
assert funding['restrictedForCarolByPolicy']==10 and funding['erc20TransferObserved'] and funding['reserveDepositEventObserved']
funding['attributionBoundary']='The local test-token mint and sponsor-to-reserve transfer are observed; Carol ownership is declared by this fixture policy, not inferred from those transfers or from prior history.'
(p/'protected-capital-policy.json').write_text(json.dumps(policy,indent=2)+'\n')
(p/'funding-attribution.json').write_text(json.dumps(funding,indent=2)+'\n')
expected_stages=['prepared','proofAdmitted','funded','activated','afterAlice','afterBob','closed']
assert [x['stage'] for x in state_records]==expected_stages, 'Intermediate accounting snapshots missing or out of order'
for state in state_records:
    assert state['boundary']=='after local EVM contract call'
    assert state['totalCustody']==80
    assert state['reserveBalance']+state['allocatorBalance']+state['paid']==80
    assert state['reserveBalance']>=state['protectedCapital']+state['reservedCapital']
    assert state['claimedCount']==sum(v>0 for v in [state['aliceBalance'],state['bobBalance'],state['carolBalance']])
    assert state['paid']==state['aliceBalance']+state['bobBalance']+state['carolBalance']
    if state['stage'] in ['prepared','proofAdmitted','funded']:
        assert state['activatedLiability']==0 and state['remainingLiability']==0
    else:
        assert state['activatedLiability']==state['paid']+state['remainingLiability']==70
assert [(x['stage'],x['reserveBalance'],x['allocatorBalance'],x['paid'],x['remainingLiability']) for x in state_records]==[
    ('prepared',80,0,0,0),('proofAdmitted',80,0,0,0),('funded',10,70,0,0),
    ('activated',10,70,0,70),('afterAlice',10,35,35,35),('afterBob',10,14,56,14),('closed',10,0,70,0)]
assert all(x['operationId']==state_records[0]['operationId'] for x in state_records)
assert state_records[-1]['allocatorState']=='Closed' and state_records[-1]['claimedCount']==3
assert state_records[2]['fundedEntitlement']==70 and state_records[2]['activatedLiability']==0
assert state_records[3]['fundedEntitlement']==70 and state_records[3]['activatedLiability']==70
assert state_records[0]['unmetRequest']==30 and all(x['unmetRequest']==30 for x in state_records)
assert state_records[0]['requested']-80==20 and 80-70==10 and 20+10==30
assert all(x['protectedCapital']==10 for x in state_records)
transfers=transfer_records[0]; assert [e['amount'] for e in transfers]==[70,35,21,14]
normalize=lambda v:v.lower()
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
report=dict(schema='partial-fill-qualification.v1',qualified=True,scope='local Foundry EVM; real SP1 Groth16; no public-chain execution',participants=rows,tokenTransfers=transfers,intermediateStates=state_records,protectedCapitalPolicy=policy,fundingAttribution=funding,unmetRequestDecomposition={'totalUnmet':30,'notDepositedAgainstRequests':20,'depositedButProtected':10,'equation':'30 unmet = 20 not deposited against requests + 10 deposited but protected','assumption':'The fixture’s 80-unit deposit is the complete source capital for these requests; its configured 10-unit protected floor is excluded from allocation.'},requested=100,available=70,unmet=30,final=final,identity=identity,sourceRoot=r['realized-statement-root'],policy=read('policy.json'),configuration=read('configuration.json'),genesis=read('genesis.json'),roots=r,tests={k:dict(status=v['status'],gas=v.get('kind',{}).get('Unit',{}).get('gas')) for k,v in cases.items()},sourceRejections=read('negative-source/results.json'),independentReplay='Exact Clojure/Rust equality, including canonical roots and 288 public bytes',limitations=['Controlled initiator-specified requests, not independent evidence of external liabilities.','Single local test governor; production governance and Base Sepolia unqualified.','No public-chain transaction receipts.','Unmet requests create no deferred entitlement, funded liability, recognized loss or future obligation.','Carol’s beneficial ownership is declared by the adopted fixture policy; token movements establish only local funding attribution, not independent legal ownership.','Current frozen OnchainShortfallReserveV2 stores only an immutable protected amount; it has no owner/purpose policy binding or terminal refund route, so the adopted refund policy is not yet enforced or executed.','No canonical accounting journal is generated; the seven observed state snapshots remain primary, and any future journal must be derived from their verified transitions.','Snapshots are actual getter observations at local Foundry contract-call boundaries, not separate mined transactions or public-chain states.','Stale recovery preserves the operation’s committed 70-unit allocation; changed available capital would require source-consistent requalification/new operation.'])
(p/'accounting-states.json').write_text(json.dumps({'schema':'partial-fill-accounting-states.v1','scope':'Actual getter observations captured after local Foundry EVM contract calls; not separate mined transactions.','states':state_records},indent=2)+'\n')
(p/'qualification.json').write_text(json.dumps(report,indent=2)+'\n')
print(f"Qualified {len(cases)} real-verifier EVM tests; independent source reconstruction; exact withdrawals and closure")
