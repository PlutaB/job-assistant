package chat.plutab.job27

import org.json.JSONArray
import org.json.JSONObject

/** Explicit numbered pagination on official Tupu listings; page count is not a job count. */
object RecruitmentPagination {
 val script="""(function(){
  const host=location.hostname;
  if(host.endsWith('.zhiye.com')){
   const root=document.querySelector('.phoenix-pagination');
   if(root){
    const active=root.querySelector('.phoenix-pagination__numberButtonPure--clicked');
    const current=active&&Number(active.textContent.trim());const match=(root.querySelector('.phoenix-pagination__totalPage')?.textContent||'').match(/共\s*(\d+)\s*页/);const total=match&&Number(match[1]);
    const next=root.querySelector('.phoenix-pagination__pageButton--next');const canNext=next&&!/disabled/.test(next.className);
    if(!current||!total||total<current||total>200)return {};
    return {current,total,next:canNext?current+1:0,selector:'.phoenix-pagination .phoenix-pagination__pageButton--next',mode:'beisen-table'};
   }
   const pager=document.querySelector('.pager');
   if(pager&&!pager.textContent.trim()&&document.querySelector('a[href*="/zpdetail/"]'))return {current:1,total:1,next:0,mode:'beisen-legacy-single'};
  }
  if(host==='wecruit.hotjob.cn'){
   const root=document.querySelector('.ant-pagination');if(!root)return {};
   const nodes=[...root.querySelectorAll('.ant-pagination-item')];const active=root.querySelector('.ant-pagination-item-active');
   const current=active&&Number(active.textContent.trim());const total=Math.max(0,...nodes.map(n=>Number(n.textContent.trim())).filter(Number.isInteger));
   const next=root.querySelector('.ant-pagination-next');const canNext=next&&next.getAttribute('aria-disabled')!=='true'&&!/disabled/.test(next.className);
   if(!current||total<current||total>200)return {};
   return {current,total,next:canNext?current+1:0,selector:'.ant-pagination .ant-pagination-next',mode:'hotjob-numbered'};
  }
  if(host==='app.mokahr.com'){
   const root=document.querySelector('.theme-pagination');if(!root)return {};
   const nodes=[...root.querySelectorAll('.pagination-item')];const numbers=nodes.map(n=>Number(n.textContent.trim())).filter(n=>Number.isInteger(n)&&n>0);
   const active=nodes.find(n=>/active/.test(n.className));const current=active&&Number(active.textContent.trim());const total=Math.max(0,...numbers);
   const next=root.querySelector('[class*="next-page"]');const canNext=next&&!/disabled/.test(next.className);
   if(!current||total<current||total>200||(current===total&&canNext)||/\.\.\.|…/.test(root.textContent))return {};
   return {current,total,next:canNext?current+1:0,selector:'.theme-pagination [class*="next-page"]',mode:'moka-numbered'};
  }
  if(host.endsWith('moseeker.com')){
   const root=document.querySelector('.el-pagination');if(!root)return {};
   const nodes=[...root.querySelectorAll('.el-pager .number')];const active=nodes.find(n=>n.classList.contains('active'));
   const current=active&&Number(active.textContent.trim());const total=Math.max(0,...nodes.map(n=>Number(n.textContent.trim())).filter(Number.isInteger));
   const next=root.querySelector('.btn-next');const canNext=next&&!next.disabled&&!/disabled/.test(next.className);
   if(!current||total<current||total>200)return {};
   return {current,total,next:canNext?current+1:0,selector:'.el-pagination .btn-next',mode:'moseeker-numbered'};
  }
  if(!['careersite.tupu360.com','chinacampus.jnj.com.cn'].includes(host))return {};
  const active=document.querySelector('.page-item.active');const current=active&&Number(active.textContent.trim());
  const label=[...document.querySelectorAll('.page-item')].map(n=>n.textContent).join(' ');
  const match=label.match(/共\s*(\d+)\s*页/);const total=match&&Number(match[1]);
  if(!Number.isInteger(current)||!Number.isInteger(total)||current<1||total<current||total>200)return {};
  const selector='[data-action="pageTurn"][data-pagenum="'+(current+1)+'"]';const next=current<total&&document.querySelector(selector);
  return {current,total,next:next?current+1:0,selector,mode:'tupu-numbered'};
 })()"""
 fun recognized(page:JSONObject):Boolean=page.optJSONObject("pagination")?.let{it.optInt("current")>0&&it.optInt("total")>=it.optInt("current")}==true
 fun commit(j:JSONObject,entry:JSONObject,rows:List<JSONObject>,page:JSONObject){
  val p=page.getJSONObject("pagination");val current=p.getInt("current");val total=p.getInt("total");entry.put("paginationMode",p.s("mode"))
  require(current in 1..total&&total<=200){"官网数字分页无效"}
  require(rows.isNotEmpty()){ "当前数字分页未读到岗位，保留断点" }
  var pages=entry.optJSONObject("numberedPages")
  if(pages==null||entry.optInt("numberedTotal")!=total){pages=JSONObject();entry.put("numberedPages",pages).put("numberedTotal",total)}
  pages.put(current.toString(),JSONArray(rows.map{RefreshJournal.key(it.s("url"))}.distinct()))
  val complete=(1..total).all{pages.has(it.toString())}
  val keys=(1..total).flatMap{pages.strings(it.toString())}.distinct()
  val declared=PhoneJobs.count(page)
  if(complete&&declared!=null)require(declared==keys.size){"官网${declared}岗，数字分页取得${keys.size}个唯一岗位，保留断点"}
  // Replace stale keys only with the union of pages read in this verified listing.
  entry.put("keys",JSONArray(keys)).put("total",JSONObject.NULL).put("nextPage",current).put("done",false)
  RefreshJournal.addPage(j,entry,rows,if(complete)keys.size else null,current<total)
  if(!complete&&current==total){entry.put("nextPage",1);error("数字分页尚缺已确认页面，保留断点")}
  if(!complete)require(p.optInt("next")==current+1){"官网未提供下一数字分页，保留断点"}
 }
}
