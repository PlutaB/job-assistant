import {Bonjour} from 'bonjour-service';
import {type Identity} from './identity.ts';
export function advertise(identity:Identity,port:number){
 const bonjour=new Bonjour(undefined,(e:Error)=>console.error('局域网发现暂不可用：'+e.message));
 const service=bonjour.publish({name:'Job27-'+identity.id,type:'job27',protocol:'tcp',port,txt:{id:identity.id,version:'1',tls:'1'}});
 service.on('error',e=>console.error('局域网广播暂不可用：'+e.message));
 return ()=>{service.stop?.();bonjour.destroy();};
}
