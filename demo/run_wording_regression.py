"""Opt-in live Docker wording regression; uses configured model and synthetic sessions.
Run from repo: python demo/run_wording_regression.py --output-dir <new-directory>
Requires running Compose dependencies and a freshly built app image; incurs model calls.
"""
from pathlib import Path
import re
from datetime import datetime, timezone
import argparse, hashlib, json, os, shutil, subprocess, sys, time, urllib.request
parser=argparse.ArgumentParser()
parser.add_argument('--project',type=Path,default=Path(__file__).resolve().parents[1])
parser.add_argument('--output-dir',type=Path,required=True)
parser.add_argument('--cases',type=Path)
args=parser.parse_args()
PROJECT=args.project.resolve();ROOT=args.output_dir.resolve();ROOT.mkdir(parents=True,exist_ok=True)
CASES=args.cases or PROJECT/'evaluation/wording-regression-cases.json'
DOCKER=shutil.which('docker') or str(Path.home()/'AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe')
PORT=28218
NAME='echomind-wording-'+str(time.time_ns())
EXPECTED_NAMES={'账单退款处理规范','通用客服接待规范','技术支持处理规范'}

def now():
    return datetime.now(timezone.utc).isoformat()

def save(name, value):
    (ROOT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def docker(*args, check=True):
    result = subprocess.run([DOCKER, *args], cwd=PROJECT, capture_output=True, text=True,
                            encoding='utf-8', errors='replace')
    if check and result.returncode:
        # Do not print raw environment/provider details on failures.
        raise RuntimeError('Docker operation failed: ' + args[0])
    return result

def api(path, port=PORT, data=None, timeout=5):
    body = None if data is None else json.dumps(data,ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}',data=body,headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.load(response)

def inspect(name):
    return json.loads(docker('inspect', name).stdout)[0]

def safe_inspect(value):
    env = dict(item.split('=', 1) for item in value['Config']['Env'] if '=' in item)
    allowed = ['SPRING_PROFILES_ACTIVE', 'LLM_FALLBACK_ENABLED', 'ECHOMIND_SKILLS_DIR',
               'ECHOMIND_DATA_DIR', 'KNOWLEDGE_STORE_PATH', 'MEMORY_STORE_PATH',
               'DEEPSEEK_MODEL', 'SERVER_PORT', 'REDIS_HOST', 'REDIS_PORT']
    return {'id':value['Id'], 'image_id':value['Image'], 'name':value['Name'],
            'environment':{key:env[key] for key in allowed if key in env},
            'mounts':[{key:mount.get(key) for key in ['Type','Source','Destination','RW']}
                      for mount in value['Mounts']],
            'networks':list(value['NetworkSettings']['Networks']),
            'ports':value['NetworkSettings']['Ports']}

def validate_skills(value):
    assert value.get('count') == 3 and value.get('errors') == []
    assert value.get('root_dir') == '/app/skills'
    assert {item['name'] for item in value['skills']} == EXPECTED_NAMES
    assert all(item.get('enabled') is True for item in value['skills'])

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


rows=[];created=False
metadata={'scope':'Evidence gate regression / frozen holdout; case file defines turns, production Docker HTTP, real model, fallback false','started_at':now(),'cases_sha256':sha(CASES)}
try:
 data=ROOT/'isolated-data';data.mkdir(exist_ok=False)
 args=['compose','run','-d','--no-deps','--name',NAME,'-p','127.0.0.1:28218:8080','--volume',f'{data.as_posix()}:/app/data']
 for k,v in {'LLM_FALLBACK_ENABLED':'false','ECHOMIND_DATA_DIR':'/app/data/wording','KNOWLEDGE_STORE_PATH':'/app/data/wording/knowledge-store.json','MEMORY_STORE_PATH':'/app/data/wording/memory-store.json'}.items():args+=['-e',f'{k}={v}']
 docker(*args,'echomind-java');created=True
 metadata['container']=safe_inspect(inspect(NAME));metadata['jar_sha256']=docker('exec',NAME,'sha256sum','/app/echomind-java.jar').stdout.split()[0]
 metadata['source_sha256']={name:sha(PROJECT/name) for name in ['src/main/java/com/echomind/agent/BaseAgent.java','src/main/java/com/echomind/agent/AgentOrchestrator.java','src/main/java/com/echomind/agent/AnswerVerifier.java','src/main/java/com/echomind/agent/EvidenceBoundaryChecks.java','src/main/java/com/echomind/api/EchoMindController.java','skills/billing_support/SKILL.md','skills/technical_support/SKILL.md']}
 metadata['skills_sha256']=docker('exec',NAME,'sha256sum','/app/skills/billing_support/SKILL.md','/app/skills/technical_support/SKILL.md').stdout
 assert metadata['container']['environment']['LLM_FALLBACK_ENABLED']=='false'
 for _ in range(90):
  try:api('/health',port=28218);break
  except Exception:time.sleep(1)
 else:raise RuntimeError('Readiness timeout')
 skills=api('/skills',port=28218);validate_skills(skills);save('skills.json',skills)
 knowledge=json.loads((PROJECT/'demo/knowledge.json').read_text('utf-8'))
 api('/knowledge/add',port=28218,data=knowledge,timeout=60)
 for conv in json.loads((CASES).read_text('utf-8')):
  for i,msg in enumerate(conv['messages']):
   req={'message':msg,'user_id':NAME+'-'+conv['id'],'conv_id':NAME+'-'+conv['id']}
   answer=api('/chat',port=28218,data=req,timeout=180)
   rows.append({'case_id':conv['id']+'_'+str(i+1),'request':req,'response':answer})
   save('live-result.json',{'cases':rows});print(rows[-1]['case_id']+' completed',flush=True)
finally:
 if created:docker('rm','-f',NAME,check=False)
 metadata['source_unchanged']=all(sha(PROJECT/n)==v for n,v in metadata.get('source_sha256',{}).items())
 metadata['cases_unchanged']=sha(CASES)==metadata['cases_sha256']
 metadata['finished_at']=now();metadata['removed']=docker('inspect',NAME,check=False).returncode!=0
 save('live-metadata.json',metadata)
