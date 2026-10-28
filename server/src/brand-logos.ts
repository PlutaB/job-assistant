import {existsSync,readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {Store} from './store.ts';
import {companyKey} from './importer.ts';
/** Backfill only missing logos. Never replace an image uploaded by the user. */
export function applyBrandLogos(store:Store,root:string){const path=resolve(root,'data/brand-logos.json');if(!existsSync(path))return;const logos=JSON.parse(readFileSync(path,'utf8')) as {name:string;dataUrl?:string}[];for(const company of store.companies()){if(company.logo)continue;const logo=logos.find(x=>companyKey(x.name)===companyKey(company.name)&&x.dataUrl);if(logo?.dataUrl)store.put('company',{...company,logo:logo.dataUrl});}}
