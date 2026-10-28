package chat.plutab.job27

/** Public DOM adapters for sites whose job cards are not anchors. No applications are submitted. */
object RecruitmentDom {
 val cards="""(function(){
  let links=[];let embedded=[];let listState=null;let pending=false;let buttons=[];
  if(location.hostname==='xyz.51job.com'&&location.pathname==='/consumer/pc/home/index'&&new URL(location.href).searchParams.get('ctmid')==='9532340'){
   const vm=[...document.querySelectorAll('*')].map(n=>n.__vue__).find(v=>v&&Array.isArray(v.list)&&v.listQuery&&v.page);
   if(vm&&Number.isInteger(vm.total)&&vm.total>0){
    for(const row of vm.list){if(!row.jobId||!row.jobName)continue;const u=new URL('/consumer/pc/home/job',location.origin);u.search=location.search;u.searchParams.set('_jobId',row.jobId);if(row.ehireJobId)u.searchParams.set('jobid',row.ehireJobId);
     const item={url:u.href,title:row.jobName,officialId:row.jobId,description:row.jobInfo||'',descriptionComplete:!!row.jobInfo,location:row.jobAreas||'',department:row.departmentName||'',evidence:'official-51job-public-records'};embedded.push(item);links.push({url:item.url,text:item.title});
    }
    listState={kind:'official-total',count:vm.total,hasMore:vm.list.length<vm.total};
    if(listState.hasMore){const n=[...document.querySelectorAll('p.btn')].find(n=>n.textContent.trim()==='查看更多');if(n){n.setAttribute('data-job27-more','true');buttons.push({selector:'[data-job27-more]',index:-1,text:'查看更多'})}}
   }else pending=true;
  }
  if(location.hostname==='biocytogen.com.cn'&&location.pathname==='/career'){
   const box=document.querySelector('.all_careerlist');const loading=box?.parentElement.querySelector('.loadingArea');
   const ready=box&&getComputedStyle(box).display!=='none'&&(!loading||getComputedStyle(loading).display==='none');
   if(typeof window.api_request==='function'&&!window.__job27BioStarted){window.__job27BioStarted=true;window.api_request('api/career/list',{search:''},rows=>{if(Array.isArray(rows))window.__job27BioRows=rows})}
   pending=!ready||!Array.isArray(window.__job27BioRows);
   if(!pending){
    for(const node of box.querySelectorAll('.item-group h5 a[href]')){const u=new URL(node.href);if(u.hostname===location.hostname||u.hostname==='biocytogen.workable.com')links.push({url:u.href,text:node.textContent.trim()})}
    for(const row of window.__job27BioRows){const u=new URL(row.url,location.href);if(u.hostname!==location.hostname)continue;const d=document.createElement('div');d.innerHTML=row._content||row.content||'';const description=d.textContent.trim();embedded.push({url:u.href,title:row.title,officialId:String(row.id),description,descriptionComplete:!!description,location:row.location||'',recruitmentType:row.job_type||''})}
    listState={kind:'official-total',count:new Set(links.map(n=>n.url)).size,hasMore:false,evidence:'official-promise-all-rendered-list'};
   }
  }
  if(location.hostname==='www.fosunwanbang.com'&&location.pathname==='/join/campus.html'){
   const pages=document.querySelector('#pagescount')?.textContent.match(/\/\s*(\d+)\s*页/);if(pages&&Number(pages[1])===1){
    for(const node of document.querySelectorAll('a[href*="/join/details-"]'))links.push({url:node.href,text:node.textContent.trim().split('\n')[0]});
    if(links.length)listState={kind:'official-total',count:new Set(links.map(n=>n.url)).size,hasMore:false};
   }
  }
  if(location.hostname==='jobs.gsk.com'&&location.pathname.endsWith('/search-results')){
   const data=window.phApp?.ddo?.eagerLoadRefineSearch;
   if(data&&Number.isInteger(data.totalHits)&&data.totalHits>=0){
    listState={kind:'official-total',count:data.totalHits,hasMore:!!document.querySelector('[data-ph-at-id="pagination-next-link"]:not([aria-disabled="true"]):not([disabled])')};
   }
  }
  if(location.hostname==='www.moseeker.com'&&location.pathname.startsWith('/positions/index/cid/')){
   const raw=document.querySelector('textarea#position-data');let data;try{data=JSON.parse(raw.value||raw.textContent)}catch{}
   if(data&&Array.isArray(data.positions)&&Number.isInteger(data.total)&&data.total===0&&data.positions.length===0)listState={kind:'official-total',count:0,hasMore:false,evidence:'server-rendered-scoped-search'};
  }
  if(location.hostname==='www.bettapharma.com'&&/^\/Jobs\/?$/.test(location.pathname)){
   const rows=[...document.querySelectorAll('tr.showjob')];
   for(const row of rows){const cells=[...row.querySelectorAll('td')];const title=cells[0]?.textContent.trim();const description=row.nextElementSibling?.querySelector('.showjobinfo_1')?.textContent.trim();if(!title||!description)continue;
    const id=row.nextElementSibling?.querySelector('input[id^="tables"]')?.id.match(/^tables(\d+)$/)?.[1];if(!id)continue;
    const u=new URL(location.href);u.hash='position='+id;
    const headers=Array.from(row.closest('table')?.querySelector('tr')?.children||[]).map(n=>n.textContent.trim());const deptIndex=headers.findIndex(t=>/^(所属部门|招聘部门|部门)$/.test(t));const locIndex=headers.findIndex(t=>/^(工作地点|工作城市|地点)$/.test(t));
    const item={url:u.href,title,officialId:id,description,descriptionComplete:true,location:locIndex>=0?cells[locIndex]?.textContent.trim()||'':'',department:deptIndex>=0?cells[deptIndex]?.textContent.trim()||'':'',departmentEvidence:deptIndex>=0?'official-labelled-table':'',evidence:'official-inline-job-description'};embedded.push(item);links.push({url:item.url,text:title});
   }
  }
  if(location.hostname==='sc.hotjob.cn'&&/^\/wt\/GZMPC\/mobweb\/v8\/position\/list$/.test(location.pathname)){
   for(const n of document.querySelectorAll('.position_list-list-demo')){
    const node=n.querySelector('[onclick*="toDetailPostUrl"]');const m=node?.getAttribute('onclick')?.match(/toDetailPostUrl\((\d+),(\d+),(\d+)\)/);const title=n.querySelector('.position_list-list-demo-title')?.textContent.trim();if(!m||!title)continue;
    const u=new URL('/wt/GZMPC/mobweb/v8/position/detail',location.origin);u.searchParams.set('safe','Y');u.searchParams.set('canBack','true');u.searchParams.set('recruitType',m[2]);u.searchParams.set('postIdsAry',m[1]);u.searchParams.set('entityPage.currentPage',m[3]);links.push({url:u.href,text:title});
   }
   const last=[...document.querySelectorAll('input#lastPage')].map(n=>n.value).filter(Boolean).pop();
   if(last==='true'||last==='false')listState={kind:'hotjob-mobile-cumulative',count:links.length,hasMore:last!=='true'};
  }
  if(location.hostname==='campus.51job.com'&&location.pathname.startsWith('/Innovent2027/')){
   const records=typeof data!=='undefined'&&Array.isArray(data)?data:null;
   pending=!records;
   if(records){
    const ids=new Set();
    for(const row of records){if(!row.attr2||!row.attr5||row.attr5==='即将上线')continue;let u;try{u=new URL(row.attr5,location.href)}catch{continue}
     const id=u.searchParams.get('jobAdId');if(u.protocol!=='https:'||u.hostname!=='innoventbio.zhiye.com'||u.username||u.password||u.port||!id||!/(?:^|\/)detail$/.test(u.pathname)||ids.has(id))continue;
     ids.add(id);links.push({url:u.href,text:row.attr2});
    }
    listState={kind:'official-total',count:ids.size,hasMore:false,evidence:'official-complete-campaign-data-all-categories'};
   }
  }
  if(location.hostname==='chinacampus.jnj.com.cn'&&typeof positionData!=='undefined'){
   const flatten=v=>typeof v==='string'?v:Array.isArray(v)?v.map(flatten).filter(Boolean).join('\n'):v&&typeof v==='object'?['name','txt','text','content','details','list'].map(k=>flatten(v[k])).filter(Boolean).join('\n'):'';
   for(const group of positionData.content||[])for(const [index,role] of (group.list||[]).entries()){
    const params=new URLSearchParams({category:group.type,posinx:String(index)}).toString();const description=(role.faq||[]).map(section=>(section.title||'')+'\n'+flatten(section.details)).join('\n\n');
    if(!params||!description.trim())continue;
    const u=new URL('/jnj/home/index/',location.origin);u.hash='/position?'+params;
    const item={url:u.href,title:flatten(role.name),officialId:role.id,description,descriptionComplete:true,location:(role.city||[]).map(c=>c.name).join(' · '),department:flatten(role.department)||'',recruitmentType:'校园招聘',evidence:'official-jnj-public-position-data'};
    embedded.push(item);links.push({url:item.url,text:item.title});
   }
  }
  if(['careersite.tupu360.com','chinacampus.jnj.com.cn'].includes(location.hostname))for(const n of document.querySelectorAll('.position-item[pid]')){
   const id=n.getAttribute('pid');if(!/^[a-zA-Z0-9_-]+$/.test(id))continue;
   let u=new URL(location.pathname.replace(/\/position\/.*$/,'/position/detail'),location.origin);
   u.searchParams.set('positionId',id);u.searchParams.set('recruitmentType',n.getAttribute('recruitment')||'');u.searchParams.set('currentLang','zh_CN');const project=n.getAttribute('projectId')||new URL(location.href).searchParams.get('projectId');if(project)u.searchParams.set('projectId',project);
   links.push({url:u.href,text:(n.querySelector('.title .txt,.title')||n).textContent.trim().split('\n')[0]});
  }
  if(location.hostname==='campus.51job.com'&&location.pathname.startsWith('/msd/')){
   const n=document.querySelector('.job-main');const v=n&&(n.__vue__||(n.parentElement&&n.parentElement.__vue__));
   for(const j of (v&&v.joblist)||[]){const raw=j.link&&j.link[0]&&j.link[0].href;let u;try{u=new URL(raw)}catch{continue}
    if(u.protocol!=='https:'||u.hostname!=='xyz.51job.com'||!u.searchParams.get('jobid'))continue;
    let d=document.createElement('div');d.innerHTML=j.jobinfo||'';
    embedded.push({url:u.href,title:j.jobname||'',description:d.innerText||d.textContent,descriptionComplete:!!(d.textContent||'').trim(),location:j.city||'',department:'',jobCategory:j.funtype||'',recruitmentType:v.jobType===1?'应届生':'实习生'});
   }
  }
  if(location.hostname==='wecruit.hotjob.cn'&&/\/mc\/position\//.test(location.pathname)){
   const nodes=[...document.querySelectorAll('.listItem')];let allRows=null;
   for(const n of nodes){let f=n[Object.keys(n).find(k=>k.startsWith('__reactInternalInstance')||k.startsWith('__reactFiber'))];let d;
    for(let i=0;f&&i<20;i++,f=f.return){const v=f.memoizedProps&&f.memoizedProps.dataList;if(v&&v.postId&&v.postName)d=v;
     const state=f.stateNode&&f.stateNode.state;
     if(state&&Array.isArray(state.datas)&&typeof state.hasMore==='boolean'&&!state.isLoading&&!state.apiLoading){listState={kind:'hotjob-mobile-cumulative',hasMore:state.hasMore,count:state.datas.length,current:state.pageIndex};allRows=state.datas;}
    }
    if(!d||!/^[a-f0-9]{24}$/i.test(d.postId))continue;
    const u=new URL(location.pathname.replace(/\/mc\/position\/.*$/,'/mc/detail'),location.origin);u.searchParams.set('postId',d.postId);u.searchParams.set('recruitType',location.pathname.split('/').pop());u.searchParams.set('distance','0');
    links.push({url:u.href,text:d.postName});
   }
   for(const d of allRows||[]){if(!/^[a-f0-9]{24}$/i.test(d.postId)||!d.postName)continue;
    const u=new URL(location.pathname.replace(/\/mc\/position\/.*$/,'/mc/detail'),location.origin);u.searchParams.set('postId',d.postId);u.searchParams.set('recruitType',location.pathname.split('/').pop());u.searchParams.set('distance','0');links.push({url:u.href,text:d.postName});
   }
  }
  if(location.hostname==='wecruit.hotjob.cn'&&/\/pb\/(social|school|interns)\.html$/.test(location.pathname)){
   const type=location.pathname.endsWith('/school.html')?'campus':location.pathname.endsWith('/interns.html')?'intern':'society';
   for(const n of document.querySelectorAll('.list-item-main[id]')){
    if(!/^[a-f0-9]{24}$/i.test(n.id))continue;
    const title=n.querySelector('.pos-name .list-cell-span,.pos-name');if(!title)continue;
    const u=new URL('posDetail.html',location.href);u.searchParams.set('postId',n.id);u.searchParams.set('postType',type);
    links.push({url:u.href,text:(title.getAttribute('title')||title.textContent).trim()});
   }
  }
  if(location.hostname.endsWith('.zhiye.com'))for(const n of document.querySelectorAll('[class*=STListItem-editor],[class*=jobSpan-editor]')){
   let f=n[Object.keys(n).find(k=>k.startsWith('__reactInternalInstance')||k.startsWith('__reactFiber'))];let d;
   for(let i=0;f&&i<14;i++,f=f.return){let v=f.memoizedProps&&f.memoizedProps.data;if(v&&v.Id&&v.JobAdName){d=v;break}}
   if(!d||!/^[a-zA-Z0-9-]+$/.test(d.Id))continue;
   let u=new URL(location.pathname.replace(/\/jobs\/?$/,'')+'/detail',location.origin);u.searchParams.set('jobAdId',d.Id);
   let desc=document.createElement('div');desc.innerHTML='工作职责\n'+(d.Duty||'')+'\n任职资格\n'+(d.Require||'');
   const item={url:u.href,title:d.JobAdName,officialId:String(d.JobAdId||d.Id),description:desc.innerText||desc.textContent,descriptionComplete:!!((d.Duty||'')+(d.Require||'')).replace(/<[^>]*>/g,'').trim(),location:(d.LocNames||[]).join(' · '),department:d.Org||'',recruitmentType:d.CategoryId==='2'?'校园招聘':''};
   embedded.push(item);links.push({url:item.url,text:item.title});
  }
  return {links,embedded,listState,pending,buttons};
 })()"""
 const val descriptionSelector="document.querySelector('[class*=job-description], [class*=jobDescription], .position-description, [itemprop=description], .position-detail .description, .position-detail .position-content, .position-details .description')"
}
