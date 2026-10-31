/**
 * Permission-tier gate. It runs in `tools/pre-execute` BEFORE any approval policy is consulted,
 * so no approval mode (including the user-enabled no-approval mode) can widen a tier:
 * read-only still refuses writes, and workspace paths are still confined by `workspacePath`.
 */
export const READ_TOOLS:ReadonlySet<string>=new Set(['get_device_info','read','read_image','skill','self_inspect','self_config_read','self_read','self_diff','self_doctor','ask_user']);
export type TierDecision={kind:'continue'}|{kind:'deny';reason:string};
export function tierGate(tier:string|undefined,planMode:boolean|undefined,tool:string):TierDecision{
  if(tool==='system_shell' && tier!=='full-access')return {kind:'deny',reason:'System shell requires an authorized full-access session'};
  if(!READ_TOOLS.has(tool) && (tier==='read-only' || planMode))return {kind:'deny',reason:'Read-only session'};
  return {kind:'continue'};
}
