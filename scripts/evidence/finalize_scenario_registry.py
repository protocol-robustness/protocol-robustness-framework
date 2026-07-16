#!/usr/bin/env python3
"""Refresh and atomically publish a structured scenario artifact inventory."""
import argparse, hashlib, json, os
from datetime import datetime, timezone
from pathlib import Path
EXCLUDED={"manifest/artifacts.json","manifest/artifact-registry-validation.json","completion.json",".run-state"}
def digest(path):
 h=hashlib.sha256()
 with path.open("rb") as f:
  for chunk in iter(lambda:f.read(1048576),b""): h.update(chunk)
 return h.hexdigest()
def finalize(root):
 root=Path(root).resolve(); rp=root/"manifest/artifacts.json"; data=json.loads(rp.read_text()); seen=set(); hashes={}; out=[]
 for entry in data.get("artifacts",[]):
  item=dict(entry); aid=item.get("id"); raw=item.get("path")
  if not isinstance(aid,str) or not aid or aid in seen: raise ValueError("invalid or duplicate artifact id")
  path=(root/raw).resolve()
  try: rel=path.relative_to(root).as_posix()
  except ValueError: raise ValueError(f"artifact path escapes run root: {raw}")
  if rel in EXCLUDED or not path.is_file(): raise ValueError(f"invalid registered artifact: {rel}")
  item["path"]=rel; item["sha256"]=digest(path); item["bytes"]=path.stat().st_size; item.pop("mtime_utc",None)
  seen.add(aid); hashes[aid]=item["sha256"]; out.append(item)
 for item in out:
  for dep in item.get("dependencies",[]):
   if isinstance(dep,dict) and dep.get("id") in hashes: dep["sha256"]=hashes[dep["id"]]
 data["root_dir"]="."; data["artifacts"]=sorted(out,key=lambda x:x["id"]); data["generated_at"]=datetime.now(timezone.utc).isoformat(); data["generator"]={"name":"finalize-scenario-registry","version":"v1"}
 tmp=rp.with_suffix(".json.tmp"); tmp.write_text(json.dumps(data,indent=2,sort_keys=True)+"\n"); os.replace(tmp,rp); return data
def main():
 p=argparse.ArgumentParser(); p.add_argument("--run-root",required=True); a=p.parse_args()
 try: print(f"finalized {len(finalize(a.run_root)['artifacts'])} artifact(s)")
 except Exception as e: print(f"registry finalization failed: {e}",file=os.sys.stderr); return 1
 return 0
if __name__=="__main__": raise SystemExit(main())
