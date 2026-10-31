import { tierHiddenTools } from '../android-policy/tier.js';

/**
 * Tools a session must not see. The mode's allow-list (when it has one) and the permission tier both apply:
 * system_shell is hidden from every session that is not an authorized full-access session, so the model
 * is never tempted to ask for Shizuku just to open an app (m2.8.1). The tier gate still denies it at runtime.
 */
export function sessionToolDeny(all:readonly string[],modeTools:unknown,tier:string|undefined):string[]{
  const hidden=new Set(tierHiddenTools(tier));
  const allowed=Array.isArray(modeTools) ? new Set(modeTools.map(String)) : undefined;
  return all.filter(name=>hidden.has(name) || (allowed!==undefined && !allowed.has(name)));
}
