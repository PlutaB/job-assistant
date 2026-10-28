import {createServer,type IncomingMessage,type ServerResponse} from 'node:http';
import {createServer as createHttpsServer,type ServerOptions} from 'node:https';
import {type Identity} from './identity.ts';
import {createHash,randomBytes,randomInt,timingSafeEqual} from 'node:crypto';
import {mkdirSync,writeFileSync,readFileSync,existsSync,unlinkSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {Store} from './store.ts';
import {backupPreview,restoreBackup} from './backup.ts';
import {Queue} from './refresh.ts';
import {importPreview,importLegacy} from './importer.ts';
import {gradeOrder,hasApplied,httpUrl,newJob,now,statuses,textField,type Job,type Company} from './domain.ts';
const hash=(v:string)=>createHash('sha256').update(v).digest('hex');
const allowedImage=/^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=\r\n]+$/;
export function createApi(store:Store,queue:Queue,root:string,transport?:{tls:ServerOptions;identity:Identity;connection?:()=>Promise<{addresses:string[];state:string;message:string}>}){
 let pairCode=String(randomInt(100000,1000000));let pairExpires=Date.now()+600000;let attempts=0;let windowStart=Date.now();
 const attachmentDir=resolve(root,'runtime/attachments');mkdirSync(attachmentDir,{recursive:true,mode:0o700});
 const reply=(res:ServerResponse,status:number,data:any)=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.end(JSON.stringify(data));};
 async function body(req:IncomingMessage){let n=0;const chunks:Buffer[]=[];for await(const chunk of req){n+=chunk.length;if(n>36*1024*1024)throw new Error('数据超过36MB限制');chunks.push(chunk);}try{return JSON.parse(Buffer.concat(chunks).toString()||'{}');}catch{throw new Error('JSON格式无效');}}
 const handler=async(req:IncomingMessage,res:ServerResponse)=>{try{
 if(req.headers.origin){reply(res,403,{error:'请使用配对的安卓应用连接'});return;}
 const u=new URL(req.url||'/','http://localhost');const p=u.pathname.split('/').filter(Boolean);const method=req.method||'GET';
 if((u.pathname==='/health'||u.pathname==='/identity')&&method==='GET'){reply(res,200,{ok:true,version:'0.3.1',service:'job27',computer:transport?.identity??null});return;}
 if(u.pathname==='/pair'&&method==='POST'){if(Date.now()-windowStart>600000){windowStart=Date.now();attempts=0;}if(++attempts>10){reply(res,429,{error:'尝试过多，请10分钟后再试'});return;}const b=await body(req);if(transport&&b.serverId!==transport.identity.id){reply(res,403,{error:'电脑身份不匹配，请重新扫描二维码'});return;}const entered=String(b.code??'');if(Date.now()>pairExpires||entered.length!==pairCode.length||!timingSafeEqual(Buffer.from(entered),Buffer.from(pairCode))){reply(res,403,{error:'配对码错误或已过期，请在电脑重新生成'});return;}const token=randomBytes(32).toString('hex');store.db.prepare('INSERT INTO tokens VALUES(?,?)').run(hash(token),now());const device={id:crypto.randomUUID(),name:typeof b.deviceName==='string'?b.deviceName.slice(0,80):'安卓手机',createdAt:now(),tokenHash:hash(token)};store.put('device',device);pairExpires=0;reply(res,200,{token,computer:transport?.identity??null,deviceId:device.id});return;}
 const token=req.headers.authorization?.replace(/^Bearer /,'')??'';if(!token||!store.db.prepare('SELECT hash FROM tokens WHERE hash=?').get(hash(token))){reply(res,401,{error:'请先配对电脑服务'});return;}
 if(u.pathname==='/connection'&&method==='GET'){reply(res,200,{computerId:transport?.identity.id,...(await transport?.connection?.()??{addresses:[],state:'unavailable',message:'请更新电脑端'})});return;}
 if(p[0]==='media'&&method==='GET'){const name=p[1];if(!/^image\d+\.png$/.test(name)){reply(res,404,{error:'图片不存在'});return;}const file=resolve(root,'data/assets',name);if(!existsSync(file)){reply(res,404,{error:'原图待导入'});return;}res.writeHead(200,{'Content-Type':'image/png','Cache-Control':'private, max-age=86400'});res.end(readFileSync(file));return;}
 if(u.pathname==='/backup/preview'&&method==='POST'){reply(res,200,backupPreview(await body(req)));return;}
 if(u.pathname==='/backup/restore'&&method==='POST'){reply(res,200,restoreBackup(store,attachmentDir,await body(req)));return;}
 if(u.pathname==='/unpair'&&method==='POST'){store.db.prepare('DELETE FROM tokens WHERE hash=?').run(hash(token));for(const d of store.all<any>('device'))if(d.tokenHash===hash(token))store.remove('device',d.id);reply(res,200,{ok:true});return;}
 if(u.pathname==='/snapshot'&&method==='GET'){reply(res,200,{companies:store.companies().map(c=>({...c,applied:store.jobs().filter(j=>j.companyId===c.id&&hasApplied(j)).length})),jobs:store.jobs().sort((a,b)=>gradeOrder(a)-gradeOrder(b)||b.firstSeenAt.localeCompare(a.firstSeenAt)),tasks:store.tasks().reverse(),taskMessages:store.all('taskMessage'),profile:store.get('profile','me'),resumes:store.all('resume'),companyFiles:store.companies().map(c=>({companyId:c.id,list:store.companyDocument(c.id),matched:store.matchedCompanyDocument(c.id)}))});return;}
 if(p[0]==='companies'){
 if(p.length===1&&method==='POST'){const b=await body(req);const links=(b.links??[]).map((l:any)=>({name:textField(l.name,200),url:httpUrl(l.url)}));const c:Company={id:crypto.randomUUID(),name:textField(b.name,200).trim(),website:b.website?httpUrl(b.website):links[0]?.url??'',description:b.description??'',englishName:b.englishName??'',logo:b.logo&&allowedImage.test(b.logo)?b.logo:'',notes:'',links,media:[],lastSuccess:null,snapshot:null};if(!c.name)throw new Error('公司名不能为空');store.put('company',c);reply(res,201,c);return;}
 const c=store.get<Company>('company',p[1]);if(!c){reply(res,404,{error:'公司不存在'});return;}
 if(p.length===2&&method==='PATCH'){const b=await body(req);for(const k of ['name','englishName','description','notes'] as const)if(b[k]!==undefined)c[k]=textField(b[k]);if(b.website!==undefined)c.website=b.website?httpUrl(b.website):'';if(b.links!==undefined)c.links=b.links.map((l:any)=>({name:textField(l.name,200),url:httpUrl(l.url)}));if(b.logo!==undefined){if(b.logo&&!allowedImage.test(b.logo))throw new Error('Logo需为PNG/JPEG/WebP图片');c.logo=b.logo;}store.put('company',c);reply(res,200,c);return;}
 if(p[2]==='refresh'&&method==='POST'){if(!c.links.length)throw new Error('请先编辑公司并添加招聘入口');reply(res,202,queue.enqueue(c.id));return;}
 }
 if(p[0]==='jobs'){
 if(p.length===1&&method==='POST'){const b=await body(req);if(!store.get('company',b.companyId))throw new Error('请选择公司');const title=textField(b.title,500).trim();if(!title)throw new Error('岗位名不能为空');const url=b.url?httpUrl(b.url):'';const existing=store.jobs().find(j=>j.companyId===b.companyId&&url&&j.url===url&&j.title===title);if(existing){reply(res,200,existing);return;}const j=newJob(b.companyId,title,url);j.location=b.location??'';j.department=b.department?textField(b.department,500):'';j.description=b.description??'';store.put('job',j);reply(res,201,j);return;}
 const j=store.get<Job>('job',p[1]);if(!j){reply(res,404,{error:'岗位不存在'});return;}
 if(p[2]==='review'&&method==='POST'){if(!j.url)throw new Error('请先填写岗位官网链接');reply(res,202,queue.enqueue(j.companyId,'review',j.id));return;}
 if(p.length===2&&method==='PATCH'){const b=await body(req);if(b.department!==undefined){const department=textField(b.department,500);if(department!==(j.department??''))j.manualDepartment=true;j.department=department;}for(const k of ['title','location','description','notes','pendingConditions','recruitmentType'] as const)if(b[k]!==undefined)j[k]=textField(b[k]);if(b.url!==undefined)j.url=b.url?httpUrl(b.url):'';if(b.grade!==undefined){if(!['S','A','B','U',null].includes(b.grade))throw new Error('评级无效');j.grade=b.grade;j.manualGrade=true;}if(b.status!==undefined){if(!statuses.includes(b.status))throw new Error('投递状态无效');if(b.status!==j.status){j.status=b.status;j.history.push({status:j.status,at:now()});if(j.status!=='未投'&&!j.appliedAt)j.appliedAt=now();}}if(b.resumeId!==undefined){if(b.resumeId&&!store.get('resume',b.resumeId))throw new Error('简历不存在');j.resumeId=b.resumeId||null;}store.put('job',j);if(b.grade!==undefined){const c=store.get<Company>('company',j.companyId);if(c?.snapshot&&c.snapshotJobIds){c.snapshot.matched=store.jobs().filter(x=>c.snapshotJobIds!.includes(x.id)&&['S','A','B'].includes(x.grade??'')).length;store.put('company',c);}}reply(res,200,j);return;}
 }
 if(p[0]==='tasks'&&p[1]&&p[2]==='messages'&&method==='GET'){if(!store.get('task',p[1])){reply(res,404,{error:'任务不存在'});return;}reply(res,200,{messages:store.all<any>('taskMessage').filter(m=>m.taskId===p[1])});return;}
 if(p[0]==='tasks'&&p[1]&&method==='GET'){const t=store.get('task',p[1]);reply(res,t?200:404,t??{error:'刷新任务不存在'});return;}
 if(p[0]==='profile'&&method==='PATCH'){const b=await body(req);const profile=store.get<any>('profile','me')??{id:'me'};for(const k of ['name','email','phone','education','experience','skills','preferences','photo'])if(b[k]!==undefined){if(k==='photo'&&b[k]&&!allowedImage.test(b[k]))throw new Error('照片需为PNG/JPEG/WebP图片');profile[k]=textField(b[k],k==='photo'?4000000:100000);}if(b.preferences!==undefined)profile.preferenceVersion=(profile.preferenceVersion??0)+1;store.put('profile',profile);reply(res,200,profile);return;}
 if(p[0]==='resumes'){
 if(p.length===1&&method==='POST'){const b=await body(req);const data=Buffer.from(textField(b.base64,34000000),'base64');if(data.length>25*1024*1024||data.subarray(0,5).toString()!=='%PDF-')throw new Error('请上传25MB以内的PDF简历');const id=crypto.randomUUID();writeFileSync(join(attachmentDir,id+'.pdf'),data,{mode:0o600});const doc={id,name:textField(b.name,300),size:data.length,createdAt:now(),isDefault:store.all('resume').length===0};store.put('resume',doc);reply(res,201,doc);return;}
 const r=store.get<any>('resume',p[1]);if(!r){reply(res,404,{error:'简历不存在'});return;}
 if(p[2]==='file'&&method==='GET'){res.writeHead(200,{'Content-Type':'application/pdf','Content-Disposition':`inline; filename="resume.pdf"`,'Cache-Control':'no-store'});res.end(readFileSync(join(attachmentDir,r.id+'.pdf')));return;}
 if(method==='PATCH'){const b=await body(req);if(b.name!==undefined)r.name=textField(b.name,300);if(b.isDefault){for(const other of store.all<any>('resume'))store.put('resume',{...other,isDefault:false});r.isDefault=true;}store.put('resume',r);reply(res,200,r);return;}
 if(method==='DELETE'){if(store.jobs().some(j=>j.resumeId===r.id))throw new Error('此简历已关联岗位，请先解除关联');store.remove('resume',r.id);if(existsSync(join(attachmentDir,r.id+'.pdf')))unlinkSync(join(attachmentDir,r.id+'.pdf'));reply(res,200,{ok:true});return;}
 }
 if(u.pathname==='/import/preview'&&method==='POST'){reply(res,200,importPreview(store,await body(req)));return;}
 if(u.pathname==='/import/commit'&&method==='POST'){reply(res,200,importLegacy(store,await body(req)));return;}
 if(u.pathname==='/export'&&method==='GET'){const resumes=store.all<any>('resume').map(r=>({...r,base64:readFileSync(join(attachmentDir,r.id+'.pdf')).toString('base64')}));reply(res,200,{version:1,exportedAt:now(),companies:store.companies().map(c=>({...c,media:c.media.map(m=>/^image\d+\.png$/.test(m)&&existsSync(resolve(root,'data/assets',m))?'data:image/png;base64,'+readFileSync(resolve(root,'data/assets',m)).toString('base64'):m)})),jobs:store.jobs(),profile:store.get('profile','me'),resumes,tasks:store.tasks(),taskMessages:store.all('taskMessage')});return;}
 reply(res,404,{error:'接口不存在'});
 }catch(e){reply(res,400,{error:(e as Error).message});}};
 const server=transport?createHttpsServer(transport.tls,handler):createServer(handler);
 return {server,handler,getPairing:()=>({code:pairCode,expiresAt:pairExpires}),regeneratePairing:()=>{pairCode=String(randomInt(100000,1000000));pairExpires=Date.now()+600000;attempts=0;windowStart=Date.now();return pairCode;}};
}
