import {readFileSync,writeFileSync,mkdirSync,existsSync,chmodSync,unlinkSync,realpathSync} from 'node:fs';
import {resolve,dirname,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {homedir} from 'node:os';
import {execFileSync,spawn} from 'node:child_process';
const defaultRoot=resolve(dirname(fileURLToPath(import.meta.url)),'..');
export const label=process.env.JOB27_SERVICE_LABEL??'io.github.plutab.jobassistant';
if(!/^io\.github\.plutab\.jobassistant(?:\.[a-zA-Z0-9-]+)?$/.test(label))throw Error('后台标识无效');
const xml=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
const sh=s=>"'"+String(s).replaceAll("'","'\"'\"'")+"'";
export function launchAgent(root,node=process.execPath){return `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0"><dict><key>Label</key><string>${label}</string><key>ProgramArguments</key><array><string>${xml(node)}</string><string>${xml(resolve(root,'server/src/main.ts'))}</string></array><key>WorkingDirectory</key><string>${xml(root)}</string><key>EnvironmentVariables</key><dict><key>PATH</key><string>${xml(dirname(node))}:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin</string><key>JOB27_HOST</key><string>0.0.0.0</string>${['JOB27_PORT','JOB27_ADMIN_PORT','JOB27_SERVICE_LABEL','JOB27_DESKTOP_HOME','JOB27_NO_BROWSER'].filter(k=>process.env[k]).map(k=>'<key>'+k+'</key><string>'+xml(process.env[k])+'</string>').join('')}</dict><key>RunAtLoad</key><true/><key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict><key>ThrottleInterval</key><integer>30</integer><key>StandardOutPath</key><string>${xml(resolve(root,'runtime/logs/service.log'))}</string><key>StandardErrorPath</key><string>${xml(resolve(root,'runtime/logs/error.log'))}</string></dict></plist>\n`;}
export async function control(action,arg,root=defaultRoot){
 const home=process.env.JOB27_DESKTOP_HOME??homedir(),plist=join(home,'Library/LaunchAgents',label+'.plist'),domain='gui/'+process.getuid(),target=domain+'/'+label;
 if(action==='dry-run')return {plist,app:join(home,'Applications/求职助手.app'),xml:launchAgent(root),data:resolve(root,'runtime')};
 if(process.platform!=='darwin')throw Error('当前安装版仅支持已验证的Mac流程');
 const run=(args,required=true)=>{try{return execFileSync('/bin/launchctl',args,{encoding:'utf8',stdio:['ignore','pipe','pipe']});}catch(e){if(required)throw Error('系统后台设置失败：'+(e.stderr?.toString().trim()||e.message));return '';}};
 const record=resolve(root,'runtime/desktop-install.json');const writeState=enabled=>{writeFileSync(record,JSON.stringify({installed:true,autostart:enabled,root,updatedAt:new Date().toISOString()}),{mode:0o600});};
 const registered=()=>!!run(['print',target],false);
 async function waitForServer(){for(let i=0;i<60;i++){try{
  const service=JSON.parse(readFileSync(resolve(root,'runtime/service.json'),'utf8'));const address=new URL(service.adminUrl);
  if(service.root!==root||address.protocol!=='http:'||address.hostname!=='127.0.0.1')throw Error('后台状态尚未更新');
  const response=await fetch(address.origin+'/desktop-health',{signal:AbortSignal.timeout(600)});const status=await response.json();if(response.ok&&status.service==='job27-desktop'&&status.pid===service.pid&&status.serverId===service.serverId)return address.origin;
 }catch{}await new Promise(r=>setTimeout(r,500));}throw Error('后台未能启动，请查看 '+resolve(root,'runtime/logs/error.log'));}

 if(action==='install'){
  mkdirSync(dirname(plist),{recursive:true});mkdirSync(resolve(root,'runtime/logs'),{recursive:true,mode:0o700});
  if(registered())run(['bootout',target]);writeFileSync(plist,launchAgent(root),{mode:0o600});
  const app=join(home,'Applications/求职助手.app');mkdirSync(join(app,'Contents/MacOS'),{recursive:true});
  writeFileSync(join(app,'Contents/Info.plist'),`<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>io.github.plutab.jobassistant.desktop</string><key>CFBundleName</key><string>求职助手</string><key>CFBundleExecutable</key><string>launch</string><key>CFBundlePackageType</key><string>APPL</string><key>LSUIElement</key><true/></dict></plist>`);
  const launcher=join(app,'Contents/MacOS/launch');writeFileSync(launcher,`#!/bin/sh\nexec ${sh(process.execPath)} ${sh(resolve(root,'tools/desktop-control.mjs'))} open\n`);chmodSync(launcher,0o755);
  run(['enable',target]);writeState(true);run(['bootstrap',domain,plist]);const adminUrl=await waitForServer();return {ok:true,app,adminUrl,data:resolve(root,'runtime')};
 }
 if(action==='autostart'){
  if(!existsSync(plist))throw Error('请先运行安装校招助手.command');const enabled=arg==='on';run([enabled?'enable':'disable',target]);writeState(enabled);return {ok:true,enabled};
 }
 if(action==='open'){
  const desired=existsSync(record)?JSON.parse(readFileSync(record,'utf8')).autostart!==false:true;
  if(registered()){if(!desired)run(['enable',target]);run(['kickstart',target]);if(!desired)run(['disable',target]);}else if(existsSync(plist)){run(['enable',target]);run(['bootstrap',domain,plist]);writeState(desired);if(!desired)run(['disable',target]);}else{mkdirSync(resolve(root,'runtime/logs'),{recursive:true});const {openSync}=await import('node:fs');const out=openSync(resolve(root,'runtime/logs/service.log'),'a',0o600);const child=spawn(process.execPath,[resolve(root,'server/src/main.ts')],{cwd:root,detached:true,stdio:['ignore',out,out]});child.unref();}
  const adminUrl=await waitForServer();if(process.env.JOB27_NO_BROWSER!=='1')execFileSync('/usr/bin/open',[adminUrl]);return {ok:true};
 }
 if(action==='uninstall'){
  const database=resolve(root,'runtime/job27.sqlite');if(existsSync(database)){const {DatabaseSync}=await import('node:sqlite');const db=new DatabaseSync(database,{readOnly:true});try{const n=db.prepare("SELECT count(*) AS n FROM documents WHERE kind='task' AND json_extract(body,'$.state') IN ('queued','running')").get().n;if(n>0)throw Error('仍有刷新任务，请完成后再卸载后台');}finally{db.close();}}
  run(['disable',target],false);if(registered())run(['bootout',target]);if(existsSync(plist))unlinkSync(plist);
  const app=join(home,'Applications/求职助手.app');const {rmSync}=await import('node:fs');rmSync(app,{recursive:true,force:true});writeFileSync(record,JSON.stringify({installed:false,autostart:false,root}),{mode:0o600});return {ok:true,keptData:resolve(root,'runtime')};
 }
 if(action==='status'){const s=existsSync(record)?JSON.parse(readFileSync(record,'utf8')):{};return {...s,registered:registered()};}
 throw Error('未知操作');
}
if(process.argv[1]&&realpathSync(process.argv[1])===fileURLToPath(import.meta.url)){control(process.argv[2]??'open',process.argv[3]).then(value=>console.log(JSON.stringify(value))).catch(e=>{console.error(e.message);process.exitCode=1;});}
