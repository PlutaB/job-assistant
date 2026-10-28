import {createServer} from 'node:http';
import {createHash,randomBytes} from 'node:crypto';
import {readFileSync,existsSync,mkdirSync} from 'node:fs';
import {resolve} from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {chromium} from 'playwright';
import QRCode from 'qrcode';
import {Store} from './store.ts';
import {type Queue} from './refresh.ts';
import {type createApi} from './api.ts';
import {type Identity} from './identity.ts';
import {createRemoteNetwork,type RemoteNetwork} from './remote-network.ts';
import {CodexAccount} from './account.ts';
const exec=promisify(execFile),digest=(s:string)=>createHash('sha256').update(s).digest('hex');
export function createAdmin(store:Store,queue:Queue,root:string,api:ReturnType<typeof createApi>,identity:Identity,options:{apiPort:number;port:number;account?:Pick<CodexAccount,'status'|'startLogin'|'close'>;addresses?:()=>string[];network?:{status:(force?:boolean)=>Promise<RemoteNetwork>};openNetwork?:()=>Promise<void>;control?:(action:string,enabled?:boolean)=>Promise<any>;stop?:()=>void;openFolder?:(path:string)=>Promise<void>} ){
 const network=options.network??createRemoteNetwork(options.apiPort);
 const account=options.account??new CodexAccount();const session=randomBytes(32).toString('hex'),internal=randomBytes(32).toString('hex');
 const old=store.meta('adminTokenHash');if(old)store.db.prepare('DELETE FROM tokens WHERE hash=?').run(old);const internalHash=digest(internal);store.db.prepare('INSERT INTO tokens VALUES(?,?)').run(internalHash,new Date().toISOString());store.meta('adminTokenHash',internalHash);
 const origin=()=>`http://127.0.0.1:${(server.address() as any)?.port??options.port}`;
 const reply=(res:any,status:number,data:any)=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.end(JSON.stringify(data));};
 async function body(req:any){let size=0;const chunks:Buffer[]=[];for await(const chunk of req){size+=chunk.length;if(size>36*1024*1024)throw Error('请求数据过大');chunks.push(chunk);}return JSON.parse(Buffer.concat(chunks).toString()||'{}');}
 const control=options.control??(async(action,enabled)=>{const {stdout}=await exec(process.execPath,[resolve(root,'tools/desktop-control.mjs'),action,...(enabled===undefined?[]:[enabled?'on':'off'])],{cwd:root,timeout:20000});return JSON.parse(stdout||'{}');});
 const dependencies=(async()=>{let python='未准备好';try{const {stdout,stderr}=await exec(process.env.JOB27_PYTHON??'python3',['--version'],{timeout:5000});python=(stdout||stderr).trim();}catch{}return {'Node':process.version,'Python':python,'浏览器':existsSync(chromium.executablePath())?'已准备好':'未准备好，请重新运行安装入口'};})();
 const server=createServer(async(req,res)=>{try{
  const host=req.headers.host,expected=new URL(origin()).host;
  if(!['127.0.0.1','::1','::ffff:127.0.0.1'].includes(req.socket.remoteAddress??'')||host!==expected){reply(res,403,{error:'管理页面只允许本机访问'});return;}
  if(req.headers.origin&&req.headers.origin!==origin()||req.headers['sec-fetch-site']==='cross-site'){reply(res,403,{error:'不接受其他网站发起的管理请求'});return;}
  const path=new URL(req.url??'/',origin()).pathname;
  if(path==='/'&&req.method==='GET'){
   res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Set-Cookie':`job27_admin=${session}; HttpOnly; SameSite=Strict; Path=/`,'Cache-Control':'no-store','Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",'X-Content-Type-Options':'nosniff'});res.end(readFileSync(resolve(root,'server/web/index.html')));return;
  }
  const assets:Record<string,[string,string]>={'/app.js':['app.js','text/javascript; charset=utf-8'],'/style.css':['style.css','text/css; charset=utf-8'],'/logo.png':['logo.png','image/png']};
  if(assets[path]&&req.method==='GET'){const [file,type]=assets[path];res.writeHead(200,{'Content-Type':type,'X-Content-Type-Options':'nosniff'});res.end(readFileSync(resolve(root,'server/web',file)));return;}
  if(path==='/desktop-health'&&req.method==='GET'){reply(res,200,{service:'job27-desktop',pid:process.pid,serverId:identity.id});return;}
  const cookie=(req.headers.cookie??'').split(';').map(x=>x.trim()).find(x=>x.startsWith('job27_admin='))?.slice('job27_admin='.length);
  if(cookie!==session){reply(res,401,{error:'请从本机重新打开管理网页'});return;}
  if(req.method!=='GET'&&(req.headers.origin!==origin()||req.headers['x-job27-action']!=='1')){reply(res,403,{error:'操作须从本机管理页面提交'});return;}
  if(path.startsWith('/api/')){const target=path.slice(4);if(!/^\/(snapshot|companies|jobs|tasks|export|backup|profile|import|resumes)(?:\/|$)/.test(target)){reply(res,404,{error:'管理接口不存在'});return;}delete req.headers.origin;req.headers.authorization='Bearer '+internal;req.url=(req.url??'').slice(4);await api.handler(req,res);return;}
  if(path==='/admin/status'&&req.method==='GET'){
   const settings=resolve(root,'runtime/desktop-install.json');const installation=existsSync(settings)?JSON.parse(readFileSync(settings,'utf8')):{};
   reply(res,200,{computer:identity,dataPath:resolve(root,'runtime'),dependencies:await dependencies,codex:await account.status(),autostart:{installed:!!installation.installed,enabled:!!installation.autostart},network:await network.status(),activeTasks:store.tasks().filter(t=>['queued','running'].includes(t.state)).length});return;
  }
  if(path==='/admin/pairing'&&req.method==='GET'){
   const remote=await network.status();const addresses=options.addresses?options.addresses():remote.addresses;const p=api.getPairing();const value={version:1,serverId:identity.id,name:identity.name,addresses,fingerprint:identity.fingerprint,code:p.code,expiresAt:p.expiresAt};const text='job27://pair?data='+Buffer.from(JSON.stringify(value)).toString('base64url');reply(res,200,{addresses,network:remote,expiresAt:p.expiresAt,text:addresses.length?text:'',qr:addresses.length&&p.expiresAt>Date.now()?await QRCode.toDataURL(text,{errorCorrectionLevel:'M',margin:3,width:512}):null});return;
  }
  if(path==='/admin/network'&&req.method==='GET'){reply(res,200,await network.status(true));return;}
  if(path==='/admin/network/open'&&req.method==='POST'){if(options.openNetwork)await options.openNetwork();else {const status=await network.status();await exec('/usr/bin/open',status.installed?['-a','Tailscale']:['https://tailscale.com/download']);}reply(res,200,{ok:true});return;}
  if(path==='/admin/pairing/renew'&&req.method==='POST'){api.regeneratePairing();reply(res,200,{ok:true});return;}
  if(path==='/admin/devices'&&req.method==='GET'){reply(res,200,{devices:store.all<any>('device').map(({tokenHash,...d})=>d)});return;}
  if(path==='/admin/devices/revoke'&&req.method==='POST'){const b=await body(req);const d=store.get<any>('device',b.id);if(!d)throw Error('设备不存在');store.db.prepare('DELETE FROM tokens WHERE hash=?').run(d.tokenHash);store.remove('device',d.id);reply(res,200,{ok:true});return;}
  if(path==='/admin/login'&&req.method==='POST'){reply(res,200,await account.startLogin());return;}
  if(path==='/admin/autostart'&&req.method==='POST'){const b=await body(req);if(typeof b.enabled!=='boolean')throw Error('自启设置无效');reply(res,200,await control('autostart',b.enabled));return;}
  if(path==='/admin/stop'&&req.method==='POST'){if(store.tasks().some(t=>['queued','running'].includes(t.state))){reply(res,409,{error:'仍有刷新任务执行中，请完成后再停止后台'});return;}reply(res,200,{ok:true});setTimeout(()=>options.stop?.(),200);return;}
  if(path==='/admin/open-folder'&&req.method==='POST'){const b=await body(req);const section=({data:'',companies:'company-jobs',tasks:'tasks',resumes:'attachments'} as Record<string,string>)[b.section];if(section===undefined)throw Error('目录无效');const folder=resolve(root,'runtime',section);mkdirSync(folder,{recursive:true,mode:0o700});if(options.openFolder)await options.openFolder(folder);else {if(process.platform!=='darwin')throw Error('此安装版只验证了Mac，请直接使用页面显示的目录');await exec('/usr/bin/open',[folder]);}reply(res,200,{ok:true});return;}
  if(path.startsWith('/admin/files/')&&req.method==='GET'){
   const [, , ,rawId,kind]=path.split('/');const id=decodeURIComponent(rawId??'');const c=store.get<any>('company',id);if(!c||!['list','matched'].includes(kind))throw Error('公司文件不存在');const file=resolve(root,'runtime/company-jobs',store.companyFileName(id,kind as 'list'|'matched'));const document=existsSync(file)?readFileSync(file):Buffer.from(JSON.stringify(kind==='list'?store.companyDocument(id):store.matchedCompanyDocument(id),null,2));res.writeHead(200,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','Content-Disposition':`attachment; filename*=UTF-8''${encodeURIComponent(c.name+'-'+kind+'.json')}`});res.end(document);return;
  }
  reply(res,404,{error:'管理接口不存在'});
 }catch(e){reply(res,400,{error:(e as Error).message});}});
 server.on('close',()=>{account.close();store.db.prepare('DELETE FROM tokens WHERE hash=?').run(internalHash);});
 return {server};
}
