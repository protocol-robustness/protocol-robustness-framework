  import csv, json, pathlib, datetime, subprocess, sys                        
  sys.path.insert(0, "scripts")                                               
  sys.path.insert(0, "scripts/evidence")                                      
  from evidence_config import EvidenceConfig                                  
  try:                                                                        
      import vcs_info                                                         
  except ImportError:                                                         
      vcs_info = None                                                         

  _cfg = EvidenceConfig()                                                     
  csv_path = pathlib.Path("$ARTIFACT_DIR/.targets-${RUN_ID}.csv")             
  risk_path = pathlib.Path("$ARTIFACT_DIR/.risk-${RUN_ID}.lines")             
  lh_meta_path = pathlib.Path("$ARTIFACT_DIR/.long-horizon-${RUN_ID}.meta")   
  force_refund_trace = pathlib.Path("data/fixtures/traces/s64-force-refund-   
  then-illegal-release-attempt.trace.json")                                   
  rows = []                                                                   
  for r in csv.reader(csv_path.read_text().splitlines()):                     
      if len(r) != 5:                                                         
          continue                                                            
      rows.append({                                                           
          "target": r[0],                                                     
          "status": r[1],                                                     
          "exit_code": int(r[2]),                                             
          "duration_ms": int(r[3]),                                           
          "log_file": r[4]                                                    
      })                                                                      
                                                                              
  risk_digest = {                                                             
    "critical_findings": [],                                                  
    "warnings": [],                                                           
    "infos": [],                                                              
    "gates_failed": [],                                                       
    "gates_passed": []                                                        
  }                                                                           
  if risk_path.exists():                                                      
      for line in risk_path.read_text().splitlines():                         
          # Expected format: severity|phase|code|message                      
          # Use maxsplit=3 so free-form message text is preserved.            
          parts = line.split("|", 3)                                          
          if len(parts) != 4:                                                 
              continue                                                        
          severity, phase, code, message = parts[0], parts[1], parts[2],      
  parts[3]                                                                    
          item = {"phase": phase, "code": code, "message": message}           
          if severity == "critical":                                          
              risk_digest["critical_findings"].append(item)                   
              risk_digest["gates_failed"].append(code)                        
          elif severity == "warning":                                         
              risk_digest["warnings"].append(item)                            
          else:                                                               
              risk_digest["infos"].append(item)                               
              risk_digest["gates_passed"].append(code)                        
                                                                              
  # P1 clarity: enrich risk digest from target logs (OOM/kill signals, etc.)  
  for t in rows:                                                              
      lp = pathlib.Path(t.get("log_file", ""))                                
      if not lp.exists():                                                     
          continue                                                            
      txt = lp.read_text(errors="ignore")                                     
      if "Killed                  clojure -M:run -- -U" in txt or " Killed    
  clojure -M:run -- -U" in txt:                                               
          risk_digest["warnings"].append({                                    
              "phase": "phase-ah",                                            
              "code": "AH_RUNTIME_KILLED",                                    
              "message": "Phase AH process was killed (likely resource        
  exhaustion); interpret downstream results with caution"                     
          })                                                                  
                                                                              
  overall = "pass" if $FAILURES == 0 else "fail"                              
  if risk_digest["critical_findings"]:                                        
      decision = "REJECTED_CRITICAL"                                          
  elif overall == "pass" and risk_digest["warnings"]:                         
      decision = "ACCEPTED_WITH_WARNINGS"                                     
  elif overall == "pass":                                                     
      decision = "PASS_CLEAN"                                                 
  else:                                                                       
      decision = "REJECTED"                                                   
                                                                              
  out = {                                                                     
    "schema_version": _cfg.schema("test-summary"),                            
    "run_id": "$RUN_ID",                                                      
    "mode": "$MODE",                                                          
    "overall_status": overall,                                                
    "acceptance_decision": decision,                                          
    "failure_count": $FAILURES,                                               
    "risk_digest": risk_digest,                                               
    "targets": rows,                                                          
  }                                                                           
                                                                              
  def scenario_capabilities_summary(scenarios_dir: pathlib.Path):             
      profiles = set()                                                        
      archetypes = set()                                                      
      module_statuses = set()                                                 
      liquidity_modes = set()                                                 
      failure_modes = set()                                                   
      required_capabilities = set()                                           
      actor_abilities_present = 0                                             
      yield_enabled_scenarios = 0                                             
      yield_disabled_scenarios = 0                                            
      shortfall_related_scenarios = 0                                         
      partial_liquidity_enabled_scenarios = 0                                 
                                                                              
      for p in sorted(scenarios_dir.glob("*.json")):                          
          try:                                                                
              obj = json.loads(p.read_text())                                 
          except Exception:                                                   
              continue                                                        
          if not isinstance(obj, dict):                                       
              continue                                                        
                                                                              
          req_caps = obj.get("required_capabilities") or []                   
          for c in req_caps:                                                  
              required_capabilities.add(str(c))                               
                                                                              
          if obj.get("actor_abilities"):                                      
              actor_abilities_present += 1                                    
                                                                              
          ycfg = obj.get("yield_config") or {}                                
          modules = (ycfg.get("modules") or {}) if isinstance(ycfg, dict) else
  {}                                                                          
          if not isinstance(modules, dict):                                   
              modules = {}                                                    
          if modules:                                                         
              yield_enabled_scenarios += 1                                    
          else:                                                               
              yield_disabled_scenarios += 1                                   
                                                                              
          for module_id, module_cfg in modules.items():                       
              profiles.add(str(module_id))                                    
              if isinstance(module_cfg, dict):                                
                  module_statuses.add(str(module_cfg.get("module_status",     
  "active")))                                                                 
                  tokens = module_cfg.get("tokens") or {}                     
                  if not isinstance(tokens, dict):                            
                      tokens = {}                                             
                  for _, token_cfg in tokens.items():                         
                      if not isinstance(token_cfg, dict):                     
                          continue                                            
                      lm = str(token_cfg.get("liquidity_mode", "available"))  
                      liquidity_modes.add(lm)                                 
                      fms = [str(x) for x in (token_cfg.get("failure_modes")  
  or [])]                                                                     
                      for fm in fms:                                          
                          failure_modes.add(str(fm))                          
                      if lm == "shortfall" or "partial-liquidity" in fms:     
                          shortfall_related_scenarios += 1                    
                      if "partial-liquidity" in fms:                          
                          partial_liquidity_enabled_scenarios += 1            
                                                                              
          pp = obj.get("protocol_params") or {}                               
          if not isinstance(pp, dict):                                        
              pp = {}                                                         
          yid = pp.get("yield_generation_module")                             
          if yid:                                                             
              profiles.add(str(yid))                                          
              if str(yid) == "aave-v3":                                       
                  archetypes.add("yield.provider/liquid-lending")             
                                                                              
      return {                                                                
          "yield": {                                                          
              "enabled": yield_enabled_scenarios > 0,                         
              "profile_ids": sorted(profiles),                                
              "archetypes": sorted(archetypes),                               
              "module_statuses": sorted(module_statuses),                     
              "liquidity_modes": sorted(liquidity_modes),                     
              "failure_modes": sorted(failure_modes),                         
              "enabled_scenarios": yield_enabled_scenarios,                   
              "disabled_scenarios": yield_disabled_scenarios,                 
              "shortfall_related_scenarios": shortfall_related_scenarios,     
              "partial_liquidity_enabled_scenarios":                          
  partial_liquidity_enabled_scenarios,                                        
          },                                                                  
          "required_capabilities_seen": sorted(required_capabilities),        
          "actor_abilities_scenarios": actor_abilities_present,               
      }                                                                       
                                                                              
  caps = scenario_capabilities_summary(pathlib.Path("scenarios"))             
  claimable_path = pathlib.Path("$CLAIMABLE_CLASSIFICATION_FILE")             
  if claimable_path.exists():                                                 
    claimable_classification = json.loads(claimable_path.read_text())         
  else:                                                                       
    claimable_classification = {                                              
      "schema_version": _cfg.schema("claimable-classification"),              
      "observations_status": "taxonomy-only",                                 
      "shortfall_policy": {                                                   
        "mode": "partial-liquidity-supported",                                
        "allocation": "fulfilled-plus-deferred",                              
        "rounding_policy": _cfg.rounding_policy                               
      },                                                                      
      "classes": {}                                                           
    }                                                                         
  run_manifest = {                                                            
    "schema_version": _cfg.schema("test-run"),                                
    "contract_version": _cfg.contract_version,                                
    "produced_by": {                                                          
      "name": "test-run-emitter",                                             
      "version": "v1"                                                         
    },                                                                        
    "run_id": "$RUN_ID",                                                      
    "created_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),   
    "framework": {                                                            
      "name": _cfg.framework["name"],                                         
      "version": _cfg.framework["version"],                                   
      "git_commit": None                                                      
    },                                                                        
    "model": {                                                                
      "id": "sew",                                                            
      "version": "sew-model.v1",                                              
      "git_commit": None                                                      
    },                                                                        
    "suite": {                                                                
      "id": "$MODE",                                                          
      "version": "suite.v1"                                                   
    },                                                                        
    "capabilities_resolved": {                                                
      "yield": caps["yield"],                                                 
      "withdrawals": {"enabled": True, "delivery_model": "pull"},             
      "dispute_resolution": {"enabled": True},                                
      "module_snapshotting": {"enabled": True},                               
      "settlement_delivery": {"enabled": True, "mode": "pull-claimable"},     
      "invariants": {"enabled": True},                                        
      "projection": {"enabled": True}                                         
    },                                                                        
    "artifacts": {                                                            
      "test_summary": "$ARTIFACT_FILE",                                       
      "test_artifacts": "$ARTIFACT_REGISTRY_FILE",                            
      "claimable_classification": "$CLAIMABLE_CLASSIFICATION_FILE",           
      "coverage": _cfg.artifact_path("coverage"),                             
      "findings": _cfg.artifact_path("findings"),                             
      "issues": _cfg.artifact_path("issues")                                  
    }                                                                         
  }                                                                           
                                                                              
  try:                                                                      
      git_sha = (vcs_info.commit_sha() if vcs_info else None)                  
      if git_sha:                                                             
          run_manifest["framework"]["git_commit"] = git_sha                   
          run_manifest["model"]["git_commit"] = git_sha                       
  except Exception:                                                           
      pass                                                                    
                                                                              
  out["run_manifest"] = {                                                     
    "schema_version": run_manifest["schema_version"],                         
    "path": "$RUN_MANIFEST_FILE",                                             
    "run_id": run_manifest["run_id"]                                          
  }                                                                           
  out["yield_context"] = caps["yield"]                                        
  out["shortfall_exposure"] = {                                               
    "shortfall_related_scenarios": caps["yield"].                             
  get("shortfall_related_scenarios", 0),                                      
    "partial_liquidity_enabled_scenarios": caps["yield"].                     
  get("partial_liquidity_enabled_scenarios", 0),                              
    "rounding_policy": _cfg.rounding_policy                                   
  }                                                                           
  out["claimable_classification"] = {                                         
    "schema_version": claimable_classification["schema_version"],             
    "path": "$CLAIMABLE_CLASSIFICATION_FILE"                                  
  }                                                                           
                                                                              
  # Status-count block for quick dashboard/CLI consumption                    
  target_total = len(rows)                                                    
  target_pass = sum(1 for r in rows if r.get("status") == "pass")             
  target_fail = sum(1 for r in rows if r.get("status") == "fail")             
  target_unknown = max(0, target_total - target_pass - target_fail)           
                                                                              
  critical_count = len(risk_digest.get("critical_findings", []))              
  warning_count = len(risk_digest.get("warnings", []))                        
  info_count = len(risk_digest.get("infos", []))                              
  gates_failed_count = len(risk_digest.get("gates_failed", []))               
  gates_passed_count = len(risk_digest.get("gates_passed", []))               
                                                                              
  out["status_counts"] = {                                                    
    "targets": {                                                              
      "total": target_total,                                                  
      "pass": target_pass,                                                    
      "fail": target_fail,                                                    
      "unknown": target_unknown                                               
    },                                                                        
    "risk": {                                                                 
      "critical": critical_count,                                             
      "warning": warning_count,                                               
      "info": info_count                                                      
    },                                                                        
    "gates": {                                                                
      "failed": gates_failed_count,                                           
      "passed": gates_passed_count                                            
    }                                                                         
  }                                                                           
                                                                              
  # P2: per-phase failure counters for dashboard-friendly aggregation         
  phase_failures = {                                                          
      "phase_ai": 0,                                                          
      "phase_z": 0,                                                           
      "phase_ah": 0,                                                          
      "contracts": 0,                                                         
      "other": 0                                                              
  }                                                                           
                                                                              
  # Count known hard-gate failures from structured risk digest                
  for item in risk_digest.get("critical_findings", []):                       
      code = str(item.get("code", ""))                                        
      if code == "AI_CRITICAL_FAILURE":                                       
          phase_failures["phase_ai"] += 1                                     
      elif code == "Z_LEGITIMACY_FAILURE":                                    
          phase_failures["phase_z"] += 1                                      
      else:                                                                   
          phase_failures["other"] += 1                                        
                                                                              
  # Enrich counters from log signatures (captures non-critical failures too)  
  for t in rows:                                                              
      target = str(t.get("target", ""))                                       
      status = str(t.get("status", ""))                                       
      lp = pathlib.Path(t.get("log_file", ""))                                
      txt = lp.read_text(errors="ignore") if lp.exists() else ""              
                                                                              
      if target == "contracts" and status == "fail":                          
          phase_failures["contracts"] += 1                                    
                                                                              
      if "Killed                  clojure -M:run -- -U" in txt or " Killed    
  clojure -M:run -- -U" in txt:                                               
          phase_failures["phase_ah"] += 1                                     
                                                                              
  out["phase_failures"] = phase_failures                                      
                                                                              
  if lh_meta_path.exists():                                                   
      meta = {}                                                               
      for line in lh_meta_path.read_text().splitlines():                      
          if "=" not in line:                                                 
              continue                                                        
          k, v = line.split("=", 1)                                           
          meta[k.strip()] = v.strip()                                         
      if "internal_failures" in meta:                                         
          try:                                                                
              out["long_horizon_internal_failures"] =                         
  int(meta["internal_failures"])                                              
          except ValueError:                                                  
              out["long_horizon_internal_failures"] =                         
  meta["internal_failures"]                                                   
                                                                              
  # P1 force-refund forward-only fixture signal                               
  force_refund_signal = {                                                     
      "checked": False,                                                       
      "status": "inconclusive",                                               
      "offending_workflows": [],                                              
      "note": "fixture not found"                                             
  }                                                                           
  if force_refund_trace.exists():                                             
      force_refund_signal["checked"] = True                                   
      try:                                                                    
          obj = json.loads(force_refund_trace.read_text())                    
          events = obj.get("events", [])                                      
          # Heuristic fixture-level check: after buyer-winning                
  execute_resolution,                                                         
          # a seller release attempt must exist (forward-only guard regression
  pattern).                                                                   
          has_buyer_win = any(                                                
              e.get("action") == "execute_resolution" and                     
              str(e.get("params", {}).get("winner", "")).lower() == "buyer"   
              for e in events                                                 
          )                                                                   
          has_seller_release_attempt = any(                                   
              e.get("action") == "release" and str(e.get("agent", "")).lower()
  == "seller"                                                                 
              for e in events                                                 
          )                                                                   
          if has_buyer_win and has_seller_release_attempt:                    
              force_refund_signal.update({                                    
                  "status": "pass",                                           
                  "note": "force-refund forward-only regression pattern is    
  present in fixture"                                                         
              })                                                              
          else:                                                               
              force_refund_signal.update({                                    
                  "status": "fail",                                           
                  "note": "expected force-refund forward-only regression      
  pattern missing in fixture"                                                 
              })                                                              
      except Exception as e:                                                  
          force_refund_signal.update({                                        
              "status": "inconclusive",                                       
              "note": f"unable to parse force-refund fixture: {e}"            
          })                                                                  
  out["force_refund_forward_only"] = force_refund_signal                      
                                                                              
  pathlib.Path("$ARTIFACT_FILE").write_text(json.dumps(out, indent=2))        
  pathlib.Path("$RUN_MANIFEST_FILE").write_text(json.dumps(run_manifest,      
  indent=2))                                                                  
  if not claimable_path.exists():                                             
    pathlib.Path("$CLAIMABLE_CLASSIFICATION_FILE").write_text(json.           
  dumps(claimable_classification, indent=2))                                  
                                                                              
  def sha256_file(path: pathlib.Path):                                        
      if not path.exists():                                                   
          return None                                                         
      import hashlib                                                          
      h = hashlib.sha256()                                                    
      with path.open("rb") as f:                                              
          for chunk in iter(lambda: f.read(8192), b""):                       
              h.update(chunk)                                                 
      return h.hexdigest()                                                    
                                                                              
  def artifact_meta(path_s: str):                                             
      p = pathlib.Path(path_s)                                                
      if not p.exists():                                                      
          return None                                                         
      st = p.stat()                                                           
      return {                                                                
        "sha256": sha256_file(p),                                             
        "bytes": st.st_size,                                                  
        "mtime_utc": datetime.datetime.fromtimestamp(st.st_mtime, datetime.   
  timezone.utc).isoformat()                                                   
      }                                                                       
                                                                              
  def _make_entry_v1(aid, path_s, input_versions=None):                       
      a = _cfg.artifact(aid)                                                  
      if not a or not path_s:                                                 
          return None                                                         
      p = pathlib.Path(path_s)                                                
      m = artifact_meta(path_s)                                               
      if not m:                                                               
          return None                                                         
      return {                                                                
        "id": aid,                                                            
        "kind": a["kind"],                                                    
        "path": str(p),                                                       
        "schema_version": _cfg.schema(a["schema_key"]),                       
        "contract_version": _cfg.contract_version,                            
        "producer": _cfg.producer(a["producer_key"]),                         
        "verifies_against": list(a.get("verifies_against", [])),              
        "input_versions": input_versions or {},                               
        "sha256": m["sha256"],                                                
        "bytes": m["bytes"],                                                  
        "mtime_utc": m["mtime_utc"]                                           
      }                                                                       
                                                                              
  def _input_versions(aid):                                                   
      """Build input_versions dict for v1 registry entries."""                
      iv = {}                                                                 
      for dep_id in _cfg.artifact_input_dependencies(aid):                    
          dep = _cfg.artifact(dep_id)                                         
          if dep:                                                             
              dep_schema = _cfg.schema(dep["schema_key"])                     
              iv[dep_id.replace("-", "_")] = dep_schema                       
      return iv                                                               
                                                                              
  artifact_registry = {                                                       
    "schema_version": "test-artifacts.v1.2",                                  
    "contract_version": _cfg.contract_version,                                
    "run_id": "$RUN_ID",                                                      
    "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(), 
    "generator": {"name": "artifact-registry-emitter", "version": "v1"},      
    "root_dir": _cfg.artifact_dir,                                            
    "run_manifest": {                                                         
      "path": "$RUN_MANIFEST_FILE",                                           
      "schema_version": run_manifest["schema_version"],                       
      "sha256": (artifact_meta("$RUN_MANIFEST_FILE") or {}).get("sha256"),    
      "bytes": (artifact_meta("$RUN_MANIFEST_FILE") or {}).get("bytes"),      
      "mtime_utc": (artifact_meta("$RUN_MANIFEST_FILE") or {}).               
  get("mtime_utc")                                                            
    },                                                                        
    "artifacts": []                                                           
  }                                                                           
                                                                              
  _v1_aids = ["test-summary", "test-run", "claimable-classification",         
              "validation-root", "coverage", "findings", "issues"]            
  _path_map = {                                                               
      "test-summary": "$ARTIFACT_FILE",                                       
      "test-run": "$RUN_MANIFEST_FILE",                                       
      "claimable-classification": "$CLAIMABLE_CLASSIFICATION_FILE",           
  }                                                                           
                                                                              
  entries = []                                                                
  for aid in _v1_aids:                                                        
      path_s = _path_map.get(aid) or _cfg.artifact_path(aid)                  
      e = _make_entry_v1(aid, path_s, _input_versions(aid))                   
      if e:                                                                   
          entries.append(e)                                                   
                                                                              
  artifact_registry["artifacts"] = [e for e in entries if e is not None]      
  pathlib.Path("$ARTIFACT_REGISTRY_FILE").write_text(json.                    
  dumps(artifact_registry, indent=2))                                         
  print(f"Wrote machine-readable summary: $ARTIFACT_FILE")                    
  print(f"Wrote run manifest: $RUN_MANIFEST_FILE")                            
  print(f"Wrote artifact registry: $ARTIFACT_REGISTRY_FILE")                  
  print(f"Wrote claimable classification: $CLAIMABLE_CLASSIFICATION_FILE")    
                                                                              
  status_word = "PASS" if overall == "pass" else "FAIL"                       
  fail_detail = ""                                                            
  if target_fail > 0:                                                         
      fail_detail = f" ({target_fail}/{target_total} failed)"                 
  print(f"")                                                                  
  print(f"Overall: {status_word}{fail_detail}  |  Acceptance: {decision}")   
