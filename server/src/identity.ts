import {mkdirSync,readFileSync,writeFileSync,existsSync,chmodSync} from 'node:fs';
import {resolve} from 'node:path';
import {hostname,networkInterfaces} from 'node:os';
import {randomUUID,X509Certificate} from 'node:crypto';
import {execFileSync} from 'node:child_process';
export type Identity={id:string;name:string;fingerprint:string};
export function loadIdentity(root:string){
 const dir=resolve(root,'runtime/identity');mkdirSync(dir,{recursive:true,mode:0o700});
 const meta=resolve(dir,'computer.json');if(!existsSync(meta))writeFileSync(meta,JSON.stringify({id:randomUUID(),name:hostname()}),{mode:0o600});
 const saved=JSON.parse(readFileSync(meta,'utf8'));const certFile=resolve(dir,'certificate.pem'),keyFile=resolve(dir,'private-key.pem');
 if(!existsSync(certFile)||!existsSync(keyFile)){
  const config=resolve(dir,'openssl.cnf');writeFileSync(config,`[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=Job27-${saved.id}\n[ext]\nsubjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n`,{mode:0o600});
  execFileSync('/usr/bin/openssl',['req','-x509','-newkey','rsa:2048','-nodes','-keyout',keyFile,'-out',certFile,'-days','3650','-config',config],{stdio:'pipe'});chmodSync(keyFile,0o600);
 }
 const cert=readFileSync(certFile),key=readFileSync(keyFile);const fingerprint=new X509Certificate(cert).fingerprint256.replaceAll(':','').toLowerCase();
 return {identity:{id:saved.id,name:saved.name,fingerprint} as Identity,tls:{cert,key,minVersion:'TLSv1.2' as const}};
}
export function connectionAddresses(port:number){
 return [...new Set(Object.values(networkInterfaces()).flat().filter(x=>x&&!x.internal&&x.family==='IPv4').map(x=>`https://${x!.address}:${port}`))];
}
