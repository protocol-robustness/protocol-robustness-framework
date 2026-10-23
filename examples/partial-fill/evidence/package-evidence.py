"""Freeze implementation sources and hash every retained publication artifact."""
import hashlib,json,pathlib,subprocess,sys,tarfile,shutil,tomllib,re
root=pathlib.Path(__file__).resolve().parents[2]; out=pathlib.Path(sys.argv[1]).resolve();evm=pathlib.Path(sys.argv[2]).resolve()
coprocessor_root=pathlib.Path(subprocess.run([str(root/'scripts/resolve-prf-coprocessor.sh')],capture_output=True,text=True,check=True).stdout.strip())
for name in ['README.md','materialize.clj','run-local.sh','report.py','qualify-source.py','package-evidence.py']:
    shutil.copyfile(root/'examples/partial-fill'/name,out/name)
shutil.copyfile(evm/'test/PartialFillControlled.t.sol',out/'PartialFillControlled.t.sol')
if pathlib.Path('/tmp/partial-fill-regression.json').exists() and not (out/'regression-tests.json').exists():shutil.copyfile('/tmp/partial-fill-regression.json',out/'regression-tests.json')
def archive(name,base,paths,external_sources=()):
    with tarfile.open(out/name,'w:gz') as tf:
        for relative in paths:
            path=base/relative
            if path.exists(): tf.add(path,arcname=relative,filter=lambda info:None if any(x in pathlib.PurePosixPath(info.name).parts for x in ['target','.cpcache','node_modules','__pycache__']) else info)
        for archive_root,source_root in external_sources:
            for path in source_root.rglob('*'):
                if path.is_file():
                    arcname=(pathlib.Path(archive_root)/path.relative_to(source_root)).as_posix()
                    tf.add(path,arcname=arcname,recursive=False,filter=lambda info:None if any(x in pathlib.PurePosixPath(info.name).parts for x in ['target','.jj','.git','.cpcache','node_modules','__pycache__']) else info)
pending=['resolver-sim.genesis','resolver-sim.allocation.source-bound-entitlement','resolver-sim.allocation.persisted-statement-admission','resolver-sim.hash.canonical']; source_paths=set()
while pending:
    ns=pending.pop(); relative='src/'+ns.replace('.','/').replace('-','_')+'.clj'; file=root/relative
    if relative in source_paths or not file.exists():continue
    source_paths.add(relative)
    pending.extend(re.findall(r'\bresolver-sim\.[a-zA-Z0-9_.-]+',file.read_text()))
archive('prf-source.tar.gz',root,sorted(source_paths)+['test/resolver_sim/allocation/source_bound_entitlement_test.clj','deps.edn','config/defaults.edn','config/evidence.json','resources','packages/pro-rata-allocation','packages/canonical','etc/conformance','etc/deployments/eae-controlled-entitlement-demo/protocol-genesis-package.edn','examples/partial-fill/README.md','examples/partial-fill/run-local.sh','examples/partial-fill/materialize.clj','examples/partial-fill/report.py','examples/partial-fill/qualify-source.py','examples/partial-fill/package-evidence.py'],[('prf-coprocessor',coprocessor_root)])
archive('evm-source.tar.gz',evm,['src','test/PartialFillControlled.t.sol','test/fixtures/partial-fill','foundry.toml','remappings.txt','.gitmodules'])
def command(args,cwd=None):
    r=subprocess.run(args,cwd=cwd,capture_output=True,text=True);return r.stdout.strip() or r.stderr.strip()
toolchain={'scope':'local Foundry EVM execution; official SP1 Groth16 verifier; no public-chain transaction','proof_runtime':{'SP1_PROVER':'cpu','RAYON_NUM_THREADS':'8','GOMAXPROCS':'8','GOMEMLIMIT':'20GiB','SP1_WORKER_NUM_CORE_WORKERS':'1','SP1_WORKER_NUM_RECURSION_PROVER_WORKERS':'1','pipeline':'checkpointed SDK compressed/shrink/wrap; fresh native Groth16 FFI process; SDK cryptographic verification'},'rust':command(['rustc','--version']),'cargo':command(['cargo','--version']),'sp1_sdk':next(p['version'] for p in tomllib.loads((coprocessor_root/'Cargo.lock').read_text())['package'] if p['name']=='sp1-sdk'),'prf_coprocessor_revision':command(['git','rev-parse','HEAD'],coprocessor_root),'solc':command(['solc','--version']),'forge':command(['forge','--version']),'evm_base_revision':command(['git','rev-parse','HEAD'],evm),'prf_revision_status':'workspace snapshot; SHA-256 source archives, no usable git revision','dependencies':{name:command(['git','rev-parse','HEAD'],evm/'lib'/name) for name in ['forge-std','openzeppelin-contracts','sp1-contracts']}}
(out/'toolchain.json').write_text(json.dumps(toolchain,indent=2)+'\n')
q=json.loads((out/'qualification.json').read_text());active=set() if q['qualified'] else {'prove.log','recovery-prove.log'};q['availableFiles']=sorted(str(f.relative_to(out)) for f in out.rglob('*') if f.is_file() and f.name not in active and f.name!='manifest.json')+['manifest.json'];(out/'qualification.json').write_text(json.dumps(q,indent=2)+'\n');inputs=[];records=[]
for f in sorted(out.rglob('*')):
    if not f.is_file() or f.name=='manifest.json' or f.name in active:continue
    rel=f.relative_to(out).as_posix()
    item={'path':'evidence/partial-fill/'+rel,'role':'retained qualification/reproduction artifact','sha256':hashlib.sha256(f.read_bytes()).hexdigest()}
    (inputs if rel in ['source.json','policy.json','witness.json','recovery-witness.json','configuration.json','genesis.json'] else records).append(item)
manifest={'schema':'prf-demo-manifest.v1','manifest_version':1,'demo':{'id':'partial-fill','page':'/demos/partial-fill','title':'What happens when there isn’t enough?'},'source_repository':'prf-main','subject_revision':{'git_revision':None,'revision_status':'Source snapshots and owning EVM base revision retained in toolchain.json; every artifact hashed.'},'subject':{'question':'Can controlled insufficient capital be divided under a committed policy and withdrawn through a protected route?','scope':q['scope']},'inputs':inputs,'retained_evidence':records,'expected_outputs':{'allocated':[35,21,14],'withdrawn':q['final']['withdrawn'] if q['qualified'] else None,'local_contract_qualification':'pass' if q['qualified'] else 'pending','unmet':[15,9,6],'funded':70,'remaining_funded_liability':0,'final_protected_reserve':10,'operation':'Closed','tests_passed':len(q['tests'])},'verification':[{'working_directory':'PRF repository root','command':'SP1_GROTH16_ARTIFACTS=/path/to/installed/circuit PRF_EVM_ROOT=/path/to/prf-evm examples/partial-fill/run-local.sh /new/output-directory','purpose':'Regenerate source, canonical identities, independent projections and real proofs; run actual contract qualification.'},{'working_directory':'website repository root','command':'node scripts/verify-partial-fill.mjs','purpose':'Check published file hashes, cross-language equality, proof identities/public values, retained contract verdicts and links; does not rerun EVM verification.'}],'limitations':q['limitations']}
(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(f'Packaged {len(inputs)+len(records)} hashed artifacts')
