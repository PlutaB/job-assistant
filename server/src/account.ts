import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {codexCommand} from './codex.ts';
/** A real App Server session used solely for official account/read and account/login/start. */
export class CodexAccount {
 private proc?:ReturnType<typeof spawn>;
 private seq=0;
 private pending=new Map<number,{resolve:(v:any)=>void;reject:(e:Error)=>void;timer:ReturnType<typeof setTimeout>}>();
 private ready?:Promise<void>;
 login:{url?:string;code?:string;state:string;error?:string}={state:'idle'};
 private ensure(){if(this.ready)return this.ready;this.ready=this.start();return this.ready;}
 private async start(){
  const bin=codexCommand();const proc=spawn(bin.command,[...bin.prefix,'app-server','--listen','stdio://'],{stdio:['pipe','pipe','pipe']});this.proc=proc;proc.stderr?.resume();
  const fail=(e:Error)=>{for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(e);}this.pending.clear();this.ready=undefined;this.proc=undefined;};
  proc.on('error',fail);proc.on('exit',()=>fail(new Error('Codex账户连接已关闭')));proc.stdin?.on('error',fail);
  createInterface({input:proc.stdout!}).on('line',line=>{try{const m=JSON.parse(line);if(m.id!==undefined){const p=this.pending.get(m.id);if(!p)return;clearTimeout(p.timer);this.pending.delete(m.id);m.error?p.reject(new Error(m.error.message)):p.resolve(m.result);}
   else if(m.method==='account/login/completed')this.login={...this.login,state:m.params.success?'complete':'failed',error:m.params.error??undefined};
  }catch{}});
  await this.rpc('initialize',{clientInfo:{name:'job27_companion',title:'求职助手',version:'0.3.0'}});proc.stdin!.write(JSON.stringify({method:'initialized',params:{}})+'\n');
 }
 private rpc(method:string,params:any){return new Promise<any>((resolve,reject)=>{const id=++this.seq;const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error('Codex账户请求超时，请检查安装或登录状态'));},15000);this.pending.set(id,{resolve,reject,timer});this.proc!.stdin!.write(JSON.stringify({id,method,params})+'\n');});}
 async status(){try{await this.ensure();const result=await this.rpc('account/read',{refreshToken:false});return {available:true,loggedIn:result.account?.type==='chatgpt',account:result.account,login:this.login};}catch(e){return {available:false,loggedIn:false,error:(e as Error).message,login:this.login};}}
 async startLogin(){await this.ensure();if(this.login.state==='pending')return this.login;const result=await this.rpc('account/login/start',{type:'chatgpt'});if(!result.authUrl)throw Error('Codex未返回官方登录地址');const url=new URL(result.authUrl);if(url.protocol!=='https:'||!['auth.openai.com','chatgpt.com'].includes(url.hostname))throw Error('Codex返回了未识别的登录地址');this.login={url:url.href,state:'pending'};return this.login;}
 close(){this.proc?.kill();for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(new Error('服务已停止'));}this.pending.clear();}
}
