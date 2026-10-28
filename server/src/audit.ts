import {mkdirSync,writeFileSync,renameSync} from 'node:fs';
import {resolve} from 'node:path';
import {Store} from './store.ts';
export type TaskMessage={id:string;taskId:string;at:string;role:string;method:string;status:string;payload:unknown};
export function recordMessage(store:Store,root:string,taskId:string,role:string,method:string,payload:unknown,status='saved',id:string=crypto.randomUUID()):TaskMessage{
 const message={id,taskId,at:store.get<TaskMessage>('taskMessage',id)?.at??new Date().toISOString(),role,method,status,payload};store.put('taskMessage',message);
 const dir=resolve(root,'runtime/tasks',taskId);mkdirSync(dir,{recursive:true,mode:0o700});
 const file=resolve(dir,'messages.json');writeFileSync(file+'.tmp',JSON.stringify(store.all<TaskMessage>('taskMessage').filter(m=>m.taskId===taskId),null,2),{mode:0o600});renameSync(file+'.tmp',file);return message;
}
