/**
 * Permission-tier gate. It runs in `tools/pre-execute` BEFORE any approval policy is consulted,
 * so no approval mode (including the user-enabled no-approval mode) can widen a tier:
 * read-only still refuses writes, and workspace paths are still confined by `workspacePath`.
 */
export const READ_TOOLS:ReadonlySet<string>=new Set(['get_device_info','read','read_image','skill','self_inspect','self_config_read','self_read','self_diff','self_doctor','ask_user']);
export type TierDecision={kind:'continue'}|{kind:'deny';reason:string};
/**
 * Shown to the model when system_shell is refused. It must not send the user off to install Shizuku:
 * opening or operating other apps is the job of the phone_* tools, which need no full-access (m2.8.1).
 */
export const SYSTEM_SHELL_DENY_REASON='system_shell is only available in a full-access session the user enabled by hand. '+
  'To open or operate other apps use the phone_* tools (phone_launch_app, phone_do_steps, phone_observe); they need no Shizuku and no full-access. '+
  'If phone control is not set up, call phone_status and ask the user to open 设置 → 设置手机操作. Do not ask the user to install or configure Shizuku.';
export function tierGate(tier:string|undefined,planMode:boolean|undefined,tool:string):TierDecision{
  if(tool==='system_shell' && tier!=='full-access')return {kind:'deny',reason:SYSTEM_SHELL_DENY_REASON};
  if(!READ_TOOLS.has(tool) && (tier==='read-only' || planMode))return {kind:'deny',reason:'Read-only session'};
  return {kind:'continue'};
}

/** Tools hidden from a session's tool list: system_shell is only exposed to authorized full-access sessions. */
export function tierHiddenTools(tier:string|undefined):string[]{
  return tier==='full-access' ? [] : ['system_shell'];
}

/** Session prompt for an authorized full-access session. The executor is Shizuku or the built-in wireless-debugging helper. */
export const FULL_ACCESS_PROMPT='Full-access is authorized. For Android system commands use system_shell; it runs through Shizuku or the built-in wireless-debugging helper and returns its real uid and cwd. '+
  'Ordinary bash remains inside Alpine and file tools remain in the private workspace. System commands still require approval. '+
  'To open or operate other apps use only the phone_* tools (phone_launch_app, phone_do_steps): they work on the background agent display, concurrently with the user\'s main screen. '+
  'Never use system_shell for that: monkey, am start, cmd activity start, input and cmd statusbar act on the user\'s main screen and are refused.';
