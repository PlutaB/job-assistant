import {DatabaseSync} from 'node:sqlite';
import {mkdirSync,writeFileSync,renameSync,readFileSync,existsSync,unlinkSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {dirname,join} from 'node:path';
import {now,type Company,type Job,type Task} from './domain.ts';
export class Store {
 db:DatabaseSync;
 private companyFilesDir?:string;
 private inTransaction=false;
 private dirtyCompanies=new Set<string>();
 companyFileName(id:string,kind:'list'|'matched'='list'){return (/^[a-zA-Z0-9_-]{1,100}$/.test(id)?id:createHash('sha256').update(id).digest('hex'))+'.'+kind+'.json';}
 companyDocument(id:string){
  const company=this.get<Company>('company',id);if(!company)throw new Error('公司不存在');
  const jobs=this.jobs().filter(j=>j.companyId===id);
  const latestRefresh=this.tasks().filter(t=>t.companyId===id&&t.kind==='refresh').at(-1)??null;
  return {schemaVersion:2,companyId:id,company:{...company,logo:''},lastSuccessfulRefresh:company.lastSuccess,
   latestRefresh,confirmedCounts:company.snapshot,appliedCount:jobs.filter(j=>j.appliedAt).length,jobs:jobs.map(({description,...summary})=>summary)};
 }
 matchedCompanyDocument(id:string){const list=this.companyDocument(id);return {schemaVersion:2,companyId:id,company:list.company,lastSuccessfulRefresh:list.lastSuccessfulRefresh,latestRefresh:list.latestRefresh,jobs:this.jobs().filter(j=>j.companyId===id&&['S','A','B'].includes(j.grade??''))};}
 enableCompanyFiles(dir:string){this.companyFilesDir=dir;mkdirSync(dir,{recursive:true,mode:0o700});for(const c of this.companies())this.dirtyCompanies.add(c.id);this.flushCompanyFiles();}
 private changed(kind:string,doc:any){if(!this.companyFilesDir)return;const id=kind==='company'?doc.id:['job','task'].includes(kind)?doc.companyId:null;if(id)this.dirtyCompanies.add(id);if(!this.inTransaction)this.flushCompanyFiles();}
 private flushCompanyFiles(){
  if(!this.companyFilesDir)return;
  for(const id of this.dirtyCompanies){
   for(const kind of ['list','matched'] as const){const file=join(this.companyFilesDir,this.companyFileName(id,kind));
    if(!this.get('company',id)){if(existsSync(file))unlinkSync(file);continue;}
    const raw=JSON.stringify(kind==='list'?this.companyDocument(id):this.matchedCompanyDocument(id),null,2)+'\n';
    if(!existsSync(file)||readFileSync(file,'utf8')!==raw){const tmp=file+'.tmp';writeFileSync(tmp,raw,{mode:0o600});renameSync(tmp,file);}
   }
   const legacy=join(this.companyFilesDir,this.companyFileName(id).replace('.list.json','.json'));
   if(existsSync(legacy)){const old=JSON.parse(readFileSync(legacy,'utf8'));if(old.companyId===id)unlinkSync(legacy);}
   this.dirtyCompanies.delete(id);
  }
 }

 constructor(path:string){if(path!==':memory:')mkdirSync(dirname(path),{recursive:true,mode:0o700});this.db=new DatabaseSync(path);this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; CREATE TABLE IF NOT EXISTS documents(kind TEXT NOT NULL,id TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(kind,id)); CREATE TABLE IF NOT EXISTS tokens(hash TEXT PRIMARY KEY,created TEXT NOT NULL); CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);`);}
 get<T=any>(kind:string,id:string):T|undefined {const r=this.db.prepare('SELECT body FROM documents WHERE kind=? AND id=?').get(kind,id) as {body:string}|undefined;return r?JSON.parse(r.body):undefined;}
 all<T=any>(kind:string):T[]{return this.db.prepare('SELECT body FROM documents WHERE kind=? ORDER BY rowid').all(kind).map((r:any)=>JSON.parse(r.body));}
 put<T extends {id:string}>(kind:string,doc:T):T{this.db.prepare('INSERT INTO documents(kind,id,body) VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET body=excluded.body').run(kind,doc.id,JSON.stringify(doc));this.changed(kind,doc);return doc;}
 remove(kind:string,id:string){const doc=this.get(kind,id);this.db.prepare('DELETE FROM documents WHERE kind=? AND id=?').run(kind,id);if(doc)this.changed(kind,doc);}
 meta(key:string,value?:string):string|undefined{if(value!==undefined)this.db.prepare('INSERT INTO meta VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value').run(key,value);return (this.db.prepare('SELECT value FROM meta WHERE key=?').get(key) as any)?.value;}
 transaction<T>(fn:()=>T):T {const dirtyBefore=new Set(this.dirtyCompanies);this.db.exec('BEGIN IMMEDIATE');this.inTransaction=true;let r:T;
  try{r=fn();this.db.exec('COMMIT');}catch(e){this.db.exec('ROLLBACK');this.dirtyCompanies=dirtyBefore;throw e;}finally{this.inTransaction=false;}
  this.flushCompanyFiles();return r;
 }
 companies(){return this.all<Company>('company');} jobs(){return this.all<Job>('job');} tasks(){return this.all<Task>('task');}
 interruptTasks(){for(const t of this.tasks())if(['queued','running'].includes(t.state)){t.state='interrupted';t.stage='interrupted';t.message='电脑服务已重启，请重试；原有岗位记录已保留';t.finishedAt=now();this.put('task',t);}}
 close(){this.db.close();}
}
