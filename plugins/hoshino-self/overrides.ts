export interface UiOverrides {
  accent?: string; bodyScale?: number; codeScale?: number; tagline?: string;
  capsules?: Array<{label:string;prompt:string}>;
  commands?: Array<{name:string;description:string;prompt:string}>;
  sidebar?: Array<{route:string;visible:boolean}>;
}
/** A closed data schema; no executable UI values, credentials, or unknown keys. */
export function validateOverrides(value: unknown): UiOverrides {
  if(!value || typeof value!=='object' || Array.isArray(value))throw new Error('Overrides must be an object');
  const v=value as Record<string,unknown>;
  if(Object.keys(v).some(k=>!['accent','bodyScale','codeScale','tagline','capsules','commands','sidebar'].includes(k)))throw new Error('Unknown override field');
  const text=(x: unknown,max: number)=>typeof x==='string' && x.length>0 && x.length<=max;
  if(v.accent!==undefined && (typeof v.accent!=='string' || !/^#[0-9a-f]{6}$/i.test(v.accent)))throw new Error('Accent must be #RRGGBB');
  for(const k of ['bodyScale','codeScale'])if(v[k]!==undefined && (typeof v[k]!=='number' || v[k]<0.8 || v[k]>1.5))throw new Error('Font scale must be 0.8–1.5');
  if(v.tagline!==undefined && !text(v.tagline,160))throw new Error('Invalid tagline');
  for(const [field,keys,max] of [['capsules',['label','prompt'],4],['commands',['name','description','prompt'],20],['sidebar',['route','visible'],30]] as const) {
    const a=v[field];if(a===undefined)continue;
    if(!Array.isArray(a) || a.length>max)throw new Error('Invalid '+field);
    for(const item of a) {
      if(!item || typeof item!=='object' || Object.keys(item).some(k=>!(keys as readonly string[]).includes(k)) || keys.some(k=>!(k in item)))throw new Error('Invalid '+field+' item');
      if(field==='sidebar') { if(!text(item.route,100) || typeof item.visible!=='boolean')throw new Error('Invalid sidebar entry'); }
      else for(const key of keys)if(!text(item[key],key==='prompt'?4000:160))throw new Error('Invalid '+field+' text');
      if(field==='commands' && !/^[a-z][a-z0-9-]{0,31}$/.test(item.name))throw new Error('Invalid command name');
    }
  }
  return structuredClone(value) as UiOverrides;
}
