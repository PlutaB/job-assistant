import {existsSync} from 'node:fs';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
const exec=promisify(execFile);
export type RemoteNetwork={installed:boolean;state:'not-installed'|'needs-login'|'stopped'|'ready'|'unavailable';message:string;addresses:string[]};
export function isTailAddress(value:string){const n=value.split('.').map(Number);return n.length===4&&n.every((v,i)=>/^\d+$/.test(value.split('.')[i])&&v>=0&&v<=255)&&n[0]===100&&n[1]>=64&&n[1]<=127;}
export function parseTailStatus(data:any,port:number):RemoteNetwork{
 const state=data?.BackendState;const ips:string[]=(data?.Self?.TailscaleIPs??data?.TailscaleIPs??[]).filter((s:any)=>typeof s==='string'&&isTailAddress(s));
 if(state==='NeedsLogin'||state==='NeedsMachineAuth')return {installed:true,state:'needs-login',message:state==='NeedsMachineAuth'?'请在 Tailscale 管理页面批准这台电脑。':'请打开 Tailscale，登录与手机相同的账户。',addresses:[]};
 if(state==='Stopped')return {installed:true,state:'stopped',message:'Tailscale 已停止，请在电脑打开连接。',addresses:[]};
 if(state!=='Running'||data?.Self?.Online===false||!ips.length)return {installed:true,state:'unavailable',message:'电脑的 Tailscale 尚未连接，请检查网络并重新检查。',addresses:[]};
 const dns=String(data?.Self?.DNSName??'').replace(/\.$/,'');
 const addresses=ips.map((ip:string)=>`https://${ip}:${port}`);
 if(/^[a-z0-9.-]+\.ts\.net$/i.test(dns))addresses.push(`https://${dns}:${port}`);
 return {installed:true,state:'ready',message:'电脑已接入 Tailscale。手机登录同一账户并开启连接后，可用流量或其他 Wi-Fi 访问。',addresses:[...new Set(addresses)]};
}
export function createRemoteNetwork(port:number,options:{find?:()=>string|undefined;run?:(binary:string)=>Promise<string>;now?:()=>number}={}){
 const find=options.find??(()=>['/Applications/Tailscale.app/Contents/MacOS/Tailscale','/usr/local/bin/tailscale','/opt/homebrew/bin/tailscale'].find(existsSync));
 const run=options.run??(async binary=>(await exec(binary,['status','--json'],{timeout:6000,maxBuffer:2*1024*1024,env:{...process.env,TAILSCALE_BE_CLI:'1',TERM:'dumb',SHLVL:'1'}})).stdout);
 const now=options.now??Date.now;let cached:RemoteNetwork|undefined,at=0,pending:Promise<RemoteNetwork>|undefined;
 return {async status(force=false):Promise<RemoteNetwork>{
  if(!force&&cached&&now()-at<8000)return cached;if(pending)return pending;
  pending=(async()=>{const binary=find();let value:RemoteNetwork;
   if(!binary)value={installed:false,state:'not-installed',message:'先在电脑和手机安装 Tailscale，使用同一账户登录，无需租云服务器。',addresses:[]};
   else try{value=parseTailStatus(JSON.parse(await run(binary)),port);}catch{value={installed:true,state:'unavailable',message:'暂时无法读取 Tailscale 状态，请打开它并确认已登录和连接。',addresses:[]};}
   cached=value;at=now();return value;
  })();try{return await pending;}finally{pending=undefined;}
 }};
}
