"""Deterministic recruitment collector. No GPT calls. Python 3.10+, standard library only.
Playwright's existing Node installation renders dynamic sites; Python controls extraction.
"""
import sys,json,re,subprocess,hashlib,html
from urllib.parse import urlsplit,urlunsplit,urlencode,parse_qsl,urljoin
from datetime import datetime,timezone

def canonical(raw):
    p=urlsplit(raw)
    query=[(k,v) for k,v in parse_qsl(p.query) if not k.lower().startswith('utm_') and k.lower() not in ('source','sourcetoken')]
    return urlunsplit((p.scheme,p.netloc,p.path.rstrip('/'),urlencode(query),p.fragment))

def text(raw):
    return html.unescape(re.sub('<[^>]+>',' ',str(raw or ''))).strip()

def nodes(value):
    if isinstance(value,list):
        for x in value: yield from nodes(x)
    elif isinstance(value,dict):
        yield value
        for k,v in value.items():
            if k not in ('description',): yield from nodes(v)

def structured(page):
    result=[]
    for raw in page.get('jsonld',[]):
        try:result.extend(nodes(json.loads(raw)))
        except (ValueError,TypeError):continue
    return result

def iso_date(raw):
    if not raw:return None
    raw=str(raw).strip()
    if not re.match(r'^\d{4}-\d{2}-\d{2}(?:$|T)',raw):return None
    try:datetime.fromisoformat(raw.replace('Z','+00:00'));return raw
    except ValueError:return None

def department(row,body):
    value=row.get('department')
    org=row.get('hiringOrganization',{})
    if not value and isinstance(org,dict):value=org.get('department')
    def name(v):return text(v.get('name','')) if isinstance(v,dict) else text(v) if isinstance(v,str) else ''
    if value:
        result=' / '.join(filter(None,(name(v) for v in value))) if isinstance(value,list) else name(value)
        if result:return result[:500]
    match=re.search(r'^\s*(?:所属部门|招聘部门|业务部门|部门|Department)\s*[:：]\s*([^\n]+)',body,re.I|re.M)
    if not match:match=re.search(r'^\s*(?:所属部门|招聘部门|Department)\s*[:：]?\s*\n[ \t]*([^\n]+)',body,re.I|re.M)
    if match:
        value=match.group(1).strip()
        if len(value)<=160 and not re.match(r'职位|岗位|地点|发布时间|任职|职责',value):return value
    return ''

def extract_job(page,link_title=''):
    if not page.get('ok') or page.get('truncated'):return None
    body=page.get('text','')
    link_title=re.split(r'\n|发布于|发布时间|发布日期',link_title)[0].strip()[:300]
    if re.search(r'验证码|访问过于频繁|captcha|access denied|sign in to continue',body,re.I):return None
    postings=[row for row in structured(page) if 'JobPosting' in ([row.get('@type')] if isinstance(row.get('@type'),str) else row.get('@type',[]))]
    if len(postings)>1:
        postings=[row for row in postings if row.get('url') and canonical(urljoin(page['url'],row['url']))==canonical(page['url'])]
        if len(postings)!=1:return None
    for row in postings:
        desc=text(row.get('description'));title=text(row.get('title'))
        if not title or not desc:continue
        identifier=row.get('identifier',{});identifier=identifier.get('value','') if isinstance(identifier,dict) else identifier
        locations=row.get('jobLocation',[]);locations=[locations] if isinstance(locations,dict) else locations
        places=[]
        for loc in locations:
            if isinstance(loc,dict):
                address=loc.get('address',{})
                places.append(', '.join(str(address.get(k,'')) for k in ('addressLocality','addressRegion','addressCountry') if address.get(k)) if isinstance(address,dict) else str(address))
        deadline=iso_date(row.get('validThrough'));closed=False
        if deadline:
            d=datetime.fromisoformat(deadline.replace('Z','+00:00'));d=d.replace(tzinfo=timezone.utc) if d.tzinfo is None else d
            closed=d<datetime.now(timezone.utc)
        return dict(title=title,url=page['url'],officialId=str(identifier or ''),location=' / '.join(places),department=department(row,body),description=desc,recruitmentType=text(row.get('employmentType')),publishedAt=iso_date(row.get('datePosted')),active='closed' if closed else 'open',grade=None,reason='待评级',pendingConditions='',sources=[page['url']])
    # Only a candidate job detail page with actual JD sections can use a text fallback.
    if not link_title or len(body)<80 or not re.search(r'岗位职责|职位描述|任职要求|职位要求|responsibilit|qualification|job description',body,re.I):return None
    location='';dept=department({},body);kind='';official_id=''
    if (urlsplit(page['url']).hostname or '').endswith('mokahr.com'):
        header=re.search(r'([^\n]+)\n分享\n([^\n]*\|[^\n]+)\n发布于',body)
        if header:
            link_title=header.group(1).strip()[:300];parts=[v.strip() for v in header.group(2).split('|')]
            kind=parts[0];location=parts[-1];dept=parts[-2] if len(parts)>=3 else dept
        match=re.search(r'#/job/([^/?#]+)',page['url']);official_id=match.group(1) if match else ''
    published=re.search(r'(?:发布时间|发布日期|发布于|Date posted)\s*[:：]?\s*(\d{4}[-/]\d{2}[-/]\d{2})',body,re.I)
    return dict(title=link_title,url=page['url'],officialId=official_id,location=location,department=dept,description=body,recruitmentType=kind,publishedAt=iso_date(published.group(1).replace('/','-')) if published else None,active='closed' if re.search(r'职位已关闭|岗位已下架|position (?:is )?closed|no longer accepting',body,re.I) else 'open',grade=None,reason='待评级',pendingConditions='官网未结构化提供地点等字段，需核实',sources=[page['url']])

class Renderer:
    def __init__(self,node,script):
        self.proc=subprocess.Popen([node,script],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True,encoding='utf-8')
    def call(self,**request):
        self.proc.stdin.write(json.dumps(request)+'\n');self.proc.stdin.flush()
        raw=self.proc.stdout.readline()
        if not raw:raise RuntimeError('招聘网页渲染器已停止；请检查 Playwright Chromium 是否安装')
        return json.loads(raw)
    def close(self):
        self.proc.stdin.close()
        try:self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:self.proc.kill();self.proc.wait()

JOB_LINK=re.compile(r'(?:[/?#](?:jobs?|positions?|requisitions?)(?:[/=?#]|$)|job[_-]?id=|position[_-]?id=|#/job/|/jobdetail/)',re.I)

def discover(page,hosts,pattern=None):
    found={};regex=re.compile(pattern) if pattern else JOB_LINK
    for a in page.get('links',[]):
        url=urljoin(page['url'],a.get('url',''));label=a.get('text','').strip()
        if urlsplit(url).hostname not in hosts or urlsplit(url).scheme not in ('http','https'):continue
        if re.fullmatch(r'/?(?:jobs|positions|requisitions)/?',urlsplit(url).fragment,re.I):continue
        if label and regex.search(url) and canonical(url)!=canonical(page['url']):found[canonical(url)]=(url,label)
    for row in structured(page):
        if row.get('@type')=='ListItem':
            item=row.get('item',{});url=row.get('url') or (item.get('url') if isinstance(item,dict) else item)
            label=row.get('name') or (item.get('name','') if isinstance(item,dict) else '')
            if url and label and urlsplit(urljoin(page['url'],url)).hostname in hosts:found[canonical(urljoin(page['url'],url))]=(urljoin(page['url'],url),label)
    return found

def count_evidence(page):
    for row in structured(page):
        if row.get('@type')=='ItemList' and isinstance(row.get('numberOfItems'),int):return row['numberOfItems']
    match=re.search(r'(?:共(?:有)?|total\s*[:：]?)\s*(\d+)\s*(?:个?\s*(?:职位|岗位)|jobs?|positions?)',page.get('text',''),re.I)
    if match:return int(match.group(1))
    if re.search(r'暂无(?:在招)?(?:职位|岗位)|no (?:open )?(?:jobs|positions)(?: available| found)?',page.get('text',''),re.I):return 0
    return None

def collect(context,renderer,stage=lambda *args:None):
    company=context['company'];hosts={urlsplit(l['url']).hostname for l in company['links']}
    if context.get('target'):hosts.add(urlsplit(context['target']['url']).hostname)
    limits=context.get('limits',{});max_pages=min(int(limits.get('maxPages',100)),200);max_jobs=min(int(limits.get('maxJobs',2000)),5000)
    jobs={};entries=[];errors=[]
    if context.get('target'):
        target=context['target'];stage('details','Python 正在读取目标岗位详情');page=renderer.call(op='visit',url=target['url']);job=extract_job(page,target['title'])
        return dict(complete=False,message='单岗位读取完成' if job else '未取得目标岗位完整JD',entries=[],jobs=[job] if job else [])
    for entry in company['links']:
        stage('listing','Python 正在读取 '+entry['url']);discovered={};seen=set();expected=None;finished=False;problem='';page=renderer.call(op='visit',url=entry['url'])
        for index in range(max_pages):
            if not page.get('ok') or page.get('truncated'):
                problem=page.get('error','网页内容截断');break
            if re.search(r'验证码|captcha|访问过于频繁|access denied',page.get('text',''),re.I):problem='入口需要验证或拒绝访问';break
            signature=hashlib.sha256(json.dumps([page.get('links'),page.get('text')],sort_keys=True).encode()).hexdigest()
            if signature in seen:problem='翻页未产生新内容';break
            seen.add(signature);n=count_evidence(page)
            if n is not None:
                if expected is not None and expected!=n:problem='刷新过程中官网总数发生变化';break
                expected=n
            config=context.get('siteConfigs',{}).get(urlsplit(entry['url']).hostname,{})
            discovered.update(discover(page,hosts,config.get('jobUrlPattern')))
            if len(discovered)>max_jobs:problem='达到岗位抓取上限';break
            next_buttons=[b for b in page.get('buttons',[]) if not b.get('disabled')]
            next_links=[a for a in page.get('links',[]) if a.get('rel')=='next']
            if next_buttons:page=renderer.call(op='next',index=next_buttons[0]['index'])
            elif next_links:page=renderer.call(op='visit',url=urljoin(page['url'],next_links[0]['url']))
            else:finished=True;break
        urls=[];details_ok=True
        for key,(url,title) in list(discovered.items())[:max_jobs]:
            if key not in jobs:
                stage('details','Python 正在核对 '+title);detail=renderer.call(op='visit',url=url);job=extract_job(detail,title)
                if not job:details_ok=False;continue
                jobs[key]=job
            urls.append(jobs[key]['url'])
            jobs[key]['sources']=list(dict.fromkeys(jobs[key]['sources']+[entry['url']]))
        complete=finished and expected is not None and expected==len(discovered) and len(set(urls))==len(discovered) and details_ok and not problem
        evidence=f'Python: 遍历{len(seen)}页，官网总数{expected}，唯一候选{len(discovered)}，取得JD{len(urls)}。'
        if not complete:evidence+=' 未完整核实：'+(problem or '缺少可靠总数、分页或完整JD证据');errors.append(entry['url']+': '+evidence)
        entries.append(dict(url=entry['url'],complete=complete,listedCount=expected,jobUrls=list(dict.fromkeys(urls)),evidence=evidence))
    complete=bool(entries) and all(e['complete'] for e in entries)
    unique={}
    for job in jobs.values():
        key=canonical(job['url'])
        if key in unique:unique[key]['sources']=list(dict.fromkeys(unique[key]['sources']+job['sources']))
        else:unique[key]=job
    return dict(complete=complete,message='Python 抓取完成' if complete else 'Python 部分完成；'+('；'.join(errors)),entries=entries,jobs=list(unique.values()))

def main():
    context=json.load(sys.stdin)
    renderer=Renderer(context['node'],context['renderer'])
    def stage(name,message):print(json.dumps(dict(stage=name,message=message),ensure_ascii=False),file=sys.stderr,flush=True)
    try:result=collect(context,renderer,stage);print(json.dumps(result,ensure_ascii=False))
    finally:renderer.close()
if __name__=='__main__':main()
