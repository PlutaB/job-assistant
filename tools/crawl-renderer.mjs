// Rendering only. Discovery, pagination and extraction decisions belong to Python.
import {chromium} from 'playwright';
import {createInterface} from 'node:readline';
import {lookup} from 'node:dns/promises';
const hosts=new Set(JSON.parse(process.env.JOB27_ALLOWED_URLS||'[]').map(x=>new URL(x).hostname));
const privateIp=s=>s==='::1'||s==='::'||s.startsWith('127.')||s.startsWith('10.')||s.startsWith('192.168.')||s.startsWith('169.254.')||s.startsWith('0.')||/^172\.(1[6-9]|2\d|3[01])\./.test(s)||/^(fc|fd|fe80:|::ffff:)/i.test(s);
async function publicUrl(raw,navigation=false){const u=new URL(raw);if(!['https:','http:'].includes(u.protocol)||u.username||u.password||(navigation&&!hosts.has(u.hostname)))throw Error('链接不在公司招聘入口域名范围');const ips=await lookup(u.hostname,{all:true});if(!ips.length||ips.some(x=>privateIp(x.address)))throw Error('禁止读取私有网络');return u.href;}
let browser,page;
try{for await(const line of createInterface({input:process.stdin})){
 try{const req=JSON.parse(line);browser??=await chromium.launch({headless:true});if(!page){page=await browser.newPage();await page.route('**/*',async r=>{try{await publicUrl(r.request().url(),r.request().isNavigationRequest()&&r.request().frame()===page.mainFrame());await r.continue();}catch{await r.abort();}});}
 if(req.op==='visit')await page.goto(await publicUrl(req.url,true),{waitUntil:'domcontentloaded',timeout:45000});
 else if(req.op==='next'){const button=page.locator('button,[role=button],a[rel=next]').nth(req.index);const label=(await button.textContent())||await button.getAttribute('aria-label')||'';if(!/下一|next|更多|加载/i.test(label)&&await button.getAttribute('rel')!=='next')throw Error('仅允许翻页');await button.click({timeout:15000});}
 else throw Error('未知读取操作');
 await publicUrl(page.url(),true);await page.waitForTimeout(1200);
 const data=await page.evaluate(()=>({url:location.href,title:document.title,text:document.body.innerText,
  links:[...document.querySelectorAll('a[href]')].map(a=>({text:(a.innerText||a.textContent).trim(),url:a.href,rel:a.rel})),
  jsonld:[...document.querySelectorAll('script[type="application/ld+json"]')].map(x=>x.textContent),
  buttons:[...document.querySelectorAll('button,[role=button],a[rel=next]')].map((b,index)=>({index,text:(b.textContent||b.getAttribute('aria-label')||'').trim(),rel:b.getAttribute('rel'),disabled:b.disabled||b.getAttribute('aria-disabled')==='true'||/disabled/.test(b.className||'')})).filter(x=>/下一|next|更多|加载/i.test(x.text)||x.rel==='next'),
  dates:[...document.querySelectorAll('time[datetime],meta[property="article:published_time"]')].map(e=>e.getAttribute('datetime')||e.content)
 }));
 if(data.text.length>400000){data.text=data.text.slice(0,400000);data.truncated=true;}
 process.stdout.write(JSON.stringify({ok:true,...data})+'\n');
 }catch(e){process.stdout.write(JSON.stringify({ok:false,error:e.message})+'\n');}
}}finally{await browser?.close();}
