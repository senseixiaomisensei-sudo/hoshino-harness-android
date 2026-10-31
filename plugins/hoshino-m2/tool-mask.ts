import { tierHiddenTools } from '../android-policy/tier.js';

/**
 * Tool families added after a user could have saved a mode allow-list. An old allow-list never lists them, so it must not
 * hide them: before m2.9 a preset saved by the m2.8.1 app (or an edited built-in) silently removed every phone_* tool.
 * Only the chat mode stays tool-less.
 */
export const ALWAYS_ALLOWED_PREFIXES:readonly string[]=['phone_'];

/**
 * The mode's effective allow-list, or undefined for "every tool". The app used to write `"tools": []` for any preset
 * without an explicit selection (DshHarnessClient.upsertPreset), so an empty list means "all tools" except for chat,
 * whose empty list really means "no tools".
 */
export function modeAllowList(mode:{id?:unknown;tools?:unknown}|undefined):Set<string>|undefined{
  if(!mode || !Array.isArray(mode.tools))return undefined;
  if(mode.tools.length===0 && mode.id!=='chat')return undefined;
  return new Set(mode.tools.map(String));
}

/** Whether a mode lets the session see and run `tool` (tier rules are separate). */
export function modeAllows(mode:{id?:unknown;tools?:unknown}|undefined,tool:string):boolean{
  const allowed=modeAllowList(mode);
  if(allowed===undefined || allowed.has(tool))return true;
  if(mode?.id==='chat')return false;
  return ALWAYS_ALLOWED_PREFIXES.some(prefix=>tool.startsWith(prefix));
}

/**
 * Tools a session must not see. The mode's allow-list (when it has one) and the permission tier both apply:
 * system_shell is hidden from every session that is not an authorized full-access session, so the model
 * is never tempted to ask for Shizuku just to open an app (m2.8.1). The tier gate still denies it at runtime.
 * `modeTools` may be the mode object ({id, tools}) or, for older callers, the bare tools value.
 */
export function sessionToolDeny(all:readonly string[],modeTools:unknown,tier:string|undefined):string[]{
  const hidden=new Set(tierHiddenTools(tier));
  const mode=modeTools && typeof modeTools==='object' && !Array.isArray(modeTools) ? modeTools as {id?:unknown;tools?:unknown} : {tools:modeTools};
  return all.filter(name=>hidden.has(name) || !modeAllows(mode,name));
}

/**
 * One tool per built-in Hoshino plugin. dsh boots optional plugins that throw in apply() as "did not activate" and only
 * prints a stderr warning, so a broken plugin silently removes its whole tool family (m2.8.1: every phone_* tool).
 * self_doctor reports the plugins whose marker tool is not registered.
 */
export const BUILTIN_TOOL_MARKERS:Readonly<Record<string,string>>={
  'android-phone':'phone_launch_app','android-shizuku':'system_shell','android-ui':'app_screenshot','android-device-info':'get_device_info','hoshino-m2':'self_doctor',
};
export function inactiveBuiltinPlugins(registered:readonly string[]):Array<{plugin:string;missingTool:string}>{
  const names=new Set(registered);
  return Object.entries(BUILTIN_TOOL_MARKERS).filter(([,tool])=>!names.has(tool)).map(([plugin,missingTool])=>({plugin,missingTool}));
}
