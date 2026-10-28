import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {readFileSync,existsSync,writeFileSync} from 'node:fs';
import {createInterface} from 'node:readline';
import {Store} from './store.ts';
import {applyBrandLogos} from './brand-logos.ts';
import {Queue} from './refresh.ts';
import {runCodex} from './codex.ts';
import {createApi} from './api.ts';
import {importLegacy,companyKey} from './importer.ts';
import {loadIdentity} from './identity.ts';
import {advertise} from './discovery.ts';
import {createRemoteNetwork} from './remote-network.ts';
import {createAdmin} from './admin.ts';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const store=new Store(resolve(root,'runtime/job27.sqlite'));store.interruptTasks();
if(!store.meta('initialised')){const seed=JSON.parse(readFileSync(resolve(root,'data/website-seed.json'),'utf8'));importLegacy(store,seed);const entries=resolve(root,'data/recruitment-entries.json');if(existsSync(entries)){for(const c of store.companies()){const rows=JSON.parse(readFileSync(entries,'utf8')).filter((x:any)=>x.companyId===c.id||companyKey(x.name)===companyKey(c.name));for(const r of rows)for(const l of r.links)if(!c.links.some(x=>x.url===l.url))c.links.push(l);store.put('company',c);}}const profilePath=resolve(root,'data/profile.json');store.put('profile',existsSync(profilePath)?JSON.parse(readFileSync(profilePath,'utf8')):{id:'me',name:'',email:'',phone:'',education:'',experience:'',skills:'',preferences:'',preferenceVersion:1,photo:''});const {copyFileSync,mkdirSync,readdirSync}=await import('node:fs');const resumeDir=resolve(root,'data/resumes');const attach=resolve(root,'runtime/attachments');mkdirSync(attach,{recursive:true});if(existsSync(resumeDir)){for(const name of readdirSync(resumeDir).filter(n=>n.endsWith('.pdf'))){const id=crypto.randomUUID();copyFileSync(resolve(resumeDir,name),resolve(attach,id+'.pdf'));store.put('resume',{id,name,size:readFileSync(resolve(resumeDir,name)).length,createdAt:new Date().toISOString(),isDefault:name==='resume.pdf'});}}store.meta('initialised','1');}
applyBrandLogos(store,root);
store.enableCompanyFiles(resolve(root,'runtime/company-jobs'));
const settingsPath=resolve(root,'runtime/desktop-settings.json');
if(existsSync(settingsPath)){const settings=JSON.parse(readFileSync(settingsPath,'utf8'));if(settings.python&&!process.env.JOB27_PYTHON)process.env.JOB27_PYTHON=settings.python;if(settings.codex&&!process.env.CODEX_BIN)process.env.CODEX_BIN=settings.codex;}
const q=new Queue(store,(task,stage)=>runCodex(store,root,task,stage));
const {identity,tls}=loadIdentity(root);
const host=process.env.JOB27_HOST??'0.0.0.0',port=Number(process.env.JOB27_PORT??4327),adminPort=Number(process.env.JOB27_ADMIN_PORT??4328);
const network=createRemoteNetwork(port);const api=createApi(store,q,root,{identity,tls,connection:()=>network.status()});
let stopDiscovery:(()=>void)|undefined;let closing=false;
const shutdown=()=>{if(closing)return;closing=true;stopDiscovery?.();admin.server.close(()=>{api.server.close(()=>{store.close();process.exit(0);});});setTimeout(()=>process.exit(0),4000).unref();};
const admin=createAdmin(store,q,root,api,identity,{apiPort:port,port:adminPort,stop:shutdown,network});
api.server.on('error',e=>{console.error('手机接口无法启动：'+e.message+'。若已有后台运行，请直接打开管理网页。');process.exit(1);});
admin.server.on('error',e=>{console.error('管理网页无法启动：'+e.message);process.exit(1);});
api.server.listen(port,host,()=>{
 admin.server.listen(adminPort,'127.0.0.1',()=>{
  const actualApiPort=(api.server.address() as any).port,actualAdminPort=(admin.server.address() as any).port;
  writeFileSync(resolve(root,'runtime/service.json'),JSON.stringify({pid:process.pid,root,apiPort:actualApiPort,adminPort:actualAdminPort,adminUrl:`http://127.0.0.1:${actualAdminPort}`,serverId:identity.id}),{mode:0o600});
  if(process.env.JOB27_DISCOVERY!=='off')stopDiscovery=advertise(identity,actualApiPort);
  console.log(`求职助手后台已启动。电脑管理网页：http://127.0.0.1:${actualAdminPort}\n手机首次在管理网页扫码配对，以后自动连接。数据目录：${resolve(root,'runtime')}`);
 });
});
if(process.stdin.isTTY){const rl=createInterface({input:process.stdin});rl.on('line',line=>{if(line.trim()==='pair'){api.regeneratePairing();console.log('二维码已更新，请在管理网页重新查看。');}});}
process.on('SIGINT',shutdown);process.on('SIGTERM',shutdown);
