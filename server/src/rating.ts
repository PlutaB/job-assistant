import {createHash} from 'node:crypto';
import {canonicalUrl,type Job} from './domain.ts';
import {type CaptureJob} from './refresh.ts';
export const ratingPolicy={version:1,minimumDescriptionLength:80,grades:{S:'核心方向高度匹配，关键资格满足',A:'整体匹配，有少量可补足差距',B:'值得尝试，有明确能力迁移依据',U:'不推荐，说明主要原因'},insufficient:'资料不足则待审核，不凭标题强行评级',hardExclusions:[] as string[]};
export function ratingProfile(profile:any){return {education:profile.education??'',experience:profile.experience??'',skills:profile.skills??'',preferences:profile.preferences??'',preferenceVersion:profile.preferenceVersion??1};}
export function fingerprint(job:CaptureJob,profile:any){return createHash('sha256').update(JSON.stringify({title:job.title,description:job.description,location:job.location,department:job.department??'',recruitmentType:job.recruitmentType,profile:ratingProfile(profile),policy:ratingPolicy})).digest('hex');}
export function previousJob(jobs:Job[],raw:CaptureJob){return jobs.find(j=>canonicalUrl(j.url)===canonicalUrl(raw.url)||(raw.officialId&&j.officialId===raw.officialId&&new URL(j.url).hostname===new URL(raw.url).hostname));}
