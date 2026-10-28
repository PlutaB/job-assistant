import {canonicalUrl,newJob,now,httpUrl,type Company,type Job} from './domain.ts';
import {Store} from './store.ts';
const aliases:Record<string,string>={'J&J':'强生','Boehringer Ingelheim':'勃林格殷格翰',Merck:'默克','Novo Nordisk':'诺和诺德',Takeda:'武田',Astellas:'安斯泰来',Abbott:'雅培',Unilever:'联合利华',AstraZeneca:'阿斯利康',Roche:'罗氏',MSD:'默沙东',Novartis:'诺华',Lilly:'礼来',Pfizer:'辉瑞',GSK:'葛兰素史克',Bayer:'拜耳',Sanofi:'赛诺菲',AbbVie:'艾伯维',Amgen:'安进','Johnson & Johnson':'强生',华东医药:'中美华东医药',正大天晴:'正大天晴药业','诺禾致源（Novogene）':'诺禾致源','传奇生物（Legend Biotech）':'传奇生物','晶泰科技（XtalPi）':'晶泰科技','复星万邦':'复星医药','复星万邦（复星医药成员企业）':'复星医药'};
export const companyKey=(s:string)=>(aliases[s]??s).replace(/[\s（）()·]/g,'').toLowerCase();
export function importPreview(store:Store,input:any){
 const arr=Array.isArray(input)?input:input?.companies;if(!Array.isArray(arr)||arr.length>2000)throw new Error('需要网页导出的公司数组或 companies 字段');
 const existing=store.companies();const names=new Set<string>();
 const rows=arr.map((c:any)=>{if(!c||typeof c.name!=='string'||!c.name.trim())throw new Error('公司缺少名称');const key=companyKey(c.name);if(names.has(key))throw new Error('导入数据中公司重复：'+c.name);names.add(key);const match=existing.find(x=>companyKey(x.name)===key);const links=(c.links??[]).map((l:any)=>({name:String(Array.isArray(l)?l[0]:l.name??''),url:httpUrl(Array.isArray(l)?l[1]:l.url)}));return {name:c.name,existingId:match?.id??null,action:match?'merge':'add',jobs:Array.isArray(c.jobs)?c.jobs.length:0,entries:links.length};});
 return {companies:rows.length,jobs:rows.reduce((n:any,r:any)=>n+r.jobs,0),rows,unmatchedExisting:existing.filter(c=>!names.has(companyKey(c.name))).map(c=>c.name)};
}
export function importLegacy(store:Store,input:any){const preview=importPreview(store,input);const arr=Array.isArray(input)?input:input.companies;store.transaction(()=>{for(let i=0;i<arr.length;i++){
 const c=arr[i];const old=preview.rows[i].existingId?store.get<Company>('company',preview.rows[i].existingId!):undefined;
 const links=(c.links??[]).map((l:any)=>({name:String(Array.isArray(l)?l[0]:l.name??''),url:httpUrl(Array.isArray(l)?l[1]:l.url)}));
 const mergedLinks=[...old?.links??[]];for(const l of links)if(!mergedLinks.some(x=>canonicalUrl(x.url)===canonicalUrl(l.url)))mergedLinks.push(l);
 const media=(c.media??[]).map((m:any)=>typeof m==='string'?(input.assets?.[m]??m):'').filter(Boolean);
 const doc:Company=old?{...old,links:mergedLinks,media:[...new Set([...old.media,...media])],legacy:c}:{id:crypto.randomUUID(),name:c.name,englishName:c.englishName??'',website:links[0]?.url??'',description:c.description??'',notes:[c.recruitmentNotes,c.jobNotes,c.evaluation].filter(Boolean).join('\n'),links:mergedLinks,media,lastSuccess:null,snapshot:null,legacy:c};store.put('company',doc);
 for(const raw of c.jobs??[]){if(!raw.title)continue;const url=raw.url?httpUrl(raw.url):'';const found=store.jobs().find(j=>j.companyId===doc.id&&((raw.id&&(j.legacy as any)?.id===raw.id)||(j.title===raw.title&&((url&&canonicalUrl(j.url)===canonicalUrl(url))||!url))));
 const base=found??newJob(doc.id,String(raw.title),url);const tag=String(raw.statusTag??raw.tags?.at(-1)??'');const status=tag==='AI'?'AI测评':/^offer$/i.test(tag)?'Offer':/❌|拒/.test(tag)?'拒绝':/^[1-4]面$/.test(tag)?tag:(raw.done??false)?'已投':'未投';
 if(!found){base.firstSeenAt=typeof raw.firstSeenAt==='string'?raw.firstSeenAt:'';base.location=raw.location??'';base.notes=raw.note??'';base.description=raw.description??'';base.status=status;base.appliedAt=status==='未投'?null:now();base.history=status==='未投'?[]:[{status,at:now(),source:'import'}];base.reason='历史网页记录，待核实完整JD与当前在招状态';base.legacy=raw;store.put('job',base);}else{store.put('job',{...found,legacy:raw});}
 }
 }});return preview;}
