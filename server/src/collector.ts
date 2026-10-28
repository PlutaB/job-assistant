import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {resolve} from 'node:path';
import {readFileSync,existsSync} from 'node:fs';
import {type Capture} from './refresh.ts';
export async function collectPython(root:string,context:any,stage:(s:string,m:string)=>void):Promise<Capture>{
 const config=resolve(root,'data/crawler-config.json');
 const input={...context,node:process.execPath,renderer:resolve(root,'tools/crawl-renderer.mjs'),siteConfigs:existsSync(config)?JSON.parse(readFileSync(config,'utf8')):{}};
 const urls=[...context.company.links.map((l:any)=>l.url),...(context.target?.url?[context.target.url]:[])];
 return new Promise((yes,no)=>{
  const child=spawn(process.env.JOB27_PYTHON??(process.platform==='win32'?'python':'python3'),[resolve(root,'python/job27_crawler.py')],{cwd:root,env:{...process.env,JOB27_ALLOWED_URLS:JSON.stringify(urls)},stdio:['pipe','pipe','pipe']});
  let output='',error='',overflow=false;const timer=setTimeout(()=>{child.kill();no(new Error('Python 抓取超过30分钟，已保留旧岗位'));},30*60*1000);
  child.stdout.on('data',b=>{output+=b.toString();if(output.length>64*1024*1024){overflow=true;child.kill();}});
  const lines=createInterface({input:child.stderr});lines.on('line',line=>{try{const e=JSON.parse(line);if(['listing','details'].includes(e.stage))stage(e.stage,e.message);}catch{error=(error+line).slice(-2000);}});
  child.on('error',e=>{clearTimeout(timer);lines.close();no(new Error('无法启动 Python；请安装 Python 3 并配置 JOB27_PYTHON。'+e.message));});
  child.on('close',code=>{clearTimeout(timer);lines.close();if(code!==0||overflow){no(new Error('Python 抓取失败：'+(overflow?'结果过大':error)));return;}try{const capture=JSON.parse(output);if(!Array.isArray(capture.jobs)||!Array.isArray(capture.entries)||typeof capture.complete!=='boolean')throw Error('抓取格式错误');yes(capture);}catch(e){no(e);}});
  child.stdin.on('error',()=>{});child.stdin.end(JSON.stringify(input));
 });
}
