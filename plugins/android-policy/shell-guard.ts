/**
 * Virtual-display guard for system_shell (m2.9).
 *
 * Every app operation of the agent happens on its own background virtual display (AgentDisplay), concurrently with
 * the user's main screen. A system_shell command such as `monkey -p com.ss.android.ugc.aweme 1`, `am start ...` or
 * `input tap ...` without a display argument acts on the MAIN screen and pulls the user's screen away, so it is refused
 * before the approval prompt and the model is pointed at the phone_* tools.
 *
 * Pure and best effort: it parses ordinary shell syntax (quotes, ; && || | & newlines, sh -c / su -c / adb shell /
 * env / nohup / timeout / busybox prefixes, absolute paths). It is a guard against the model's habits, not a sandbox;
 * system_shell itself stays full-access only and approval-gated.
 */
export type ShellGuardKind='launch'|'input'|'statusbar'|'move';
export interface ShellGuardHit {kind:ShellGuardKind;command:string;display:number|null}
export type ShellGuardDecision={kind:'continue'}|{kind:'deny';reason:string;hit:ShellGuardHit};

const SEPARATORS=new Set([';','&&','||','|','&','\n','(',')']);

/** Splits a command line into simple commands (arrays of words). Quotes are removed, operators separate commands. */
export function splitShell(line:string):string[][] {
  const out:string[][]=[];let cur:string[]=[];let word='';let inWord=false;
  const endWord=()=>{if(inWord){cur.push(word);word='';inWord=false;}};
  const endCmd=()=>{endWord();if(cur.length)out.push(cur);cur=[];};
  for(let i=0;i<line.length;i++) {
    const c=line[i]!;
    if(c==="'") {const j=line.indexOf("'",i+1);const end=j<0?line.length:j;word+=line.slice(i+1,end);inWord=true;i=end;continue;}
    if(c==='"') {
      inWord=true;i++;
      for(;i<line.length && line[i]!=='"';i++) {if(line[i]==='\\' && i+1<line.length && '"\\$`'.includes(line[i+1]!))i++;word+=line[i];}
      continue;
    }
    if(c==='\\' && i+1<line.length) {if(line[i+1]!=='\n'){word+=line[i+1];inWord=true;}i++;continue;}
    if(c==='#' && !inWord) {while(i<line.length && line[i]!=='\n')i++;i--;continue;}
    if(c==='$' && line[i+1]==='(') {endCmd();i++;continue;}
    if(c==='`') {endCmd();continue;}
    const two=line.slice(i,i+2);
    if(two==='&&'||two==='||') {endCmd();i++;continue;}
    if(SEPARATORS.has(c)) {endCmd();continue;}
    if(c===' '||c==='\t'||c==='\r') {endWord();continue;}
    word+=c;inWord=true;
  }
  endCmd();
  return out;
}

const base=(w:string)=>w.slice(w.lastIndexOf('/')+1);

/** Strips wrappers (env assignments, env, nohup, timeout N, busybox/toybox, exec, adb [-s X] shell) and returns the real command words, or nested scripts. */
function unwrap(words:string[]):{words:string[];nested:string[]} {
  const nested:string[]=[];let w=words.slice();
  for(let guard=0;guard<20 && w.length;guard++) {
    const head=base(w[0]!);
    if(/^[A-Za-z_][A-Za-z0-9_]*=/.test(w[0]!)) {w=w.slice(1);continue;}
    if(['nohup','exec','command','busybox','toybox','time','nice','stdbuf'].includes(head)) {w=w.slice(1);while(w.length && head!=='busybox' && head!=='toybox' && w[0]!.startsWith('-'))w=w.slice(1);continue;}
    if(head==='env') {w=w.slice(1);while(w.length && (w[0]!.startsWith('-')||w[0]!.includes('=')))w=w.slice(1);continue;}
    if(head==='timeout') {w=w.slice(1);while(w.length && w[0]!.startsWith('-'))w=w.slice(1);w=w.slice(1);continue;}
    if(head==='adb') {w=w.slice(1);while(w.length && w[0]!.startsWith('-')){if(['-s','-t','-H','-P','-L'].includes(w[0]!))w=w.slice(1);w=w.slice(1);}if(w[0]==='shell'){w=w.slice(1);while(w.length && w[0]!.startsWith('-'))w=w.slice(1);}if(w.length)nested.push(w.join(' '));return {words:[],nested};}
    if(['sh','bash','ash','mksh','su','eval'].includes(head)) {
      if(head==='eval') {nested.push(w.slice(1).join(' '));return {words:[],nested};}
      const c=w.indexOf('-c');
      if(c>=0 && c+1<w.length) {nested.push(w.slice(c+1).join(' '));return {words:[],nested};}
      // `su 0 cmd...` / `su root cmd...` / `su` alone
      if(head==='su') {w=w.slice(1);while(w.length && (w[0]!.startsWith('-')||/^(\d+|root|shell)$/.test(w[0]!)))w=w.slice(1);continue;}
      return {words:[],nested};
    }
    break;
  }
  return {words:w,nested};
}

function intArg(words:string[],flags:string[]):number|null|undefined {
  for(let i=0;i<words.length;i++) {
    const w=words[i]!;
    for(const f of flags) {
      if(w===f) {const n=Number(words[i+1]);return Number.isInteger(n)?n:null;}
      if(w.startsWith(f+'=')) {const n=Number(w.slice(f.length+1));return Number.isInteger(n)?n:null;}
    }
  }
  return undefined;
}

const INPUT_CMDS=new Set(['text','keyevent','key','tap','swipe','draganddrop','press','roll','motionevent','keycombination','scroll']);

/** Classifies one simple command (wrappers already removed). */
export function classify(words:string[]):ShellGuardHit|null {
  if(!words.length)return null;
  const head=base(words[0]!);
  const text=words.join(' ');
  let w=words;
  if(head==='cmd' && words[1]==='activity')w=['am',...words.slice(2)];
  else if(head==='cmd' && words[1]==='input')w=['input',...words.slice(2)];
  else if(head==='cmd' && words[1]==='statusbar')return {kind:'statusbar',command:text,display:null};
  const h=base(w[0]!);
  if(h==='monkey')return {kind:'launch',command:text,display:null};
  if(h==='am') {
    const sub=w[1];
    if(sub==='start' || sub==='start-activity') {const d=intArg(w,['--display']);return {kind:'launch',command:text,display:d===undefined?null:d===null?-1:d};}
    if((sub==='display' && w[2]==='move-stack') || (sub==='stack' && w[2]==='move-task') || (sub==='task' && w[2]==='move-to-display'))return {kind:'move',command:text,display:null};
    return null;
  }
  if(h==='input') {
    if(!w.slice(1).some(x=>INPUT_CMDS.has(x)))return null;
    const d=intArg(w,['-d','--display']);
    return {kind:'input',command:text,display:d===undefined?null:d===null?-1:d};
  }
  return null;
}

/** Every main-screen-affecting simple command found in a system_shell command line (including sh -c / su -c / adb shell scripts). */
export function screenCommands(line:string,depth=0):ShellGuardHit[] {
  const hits:ShellGuardHit[]=[];
  if(depth>4)return hits;
  for(const words of splitShell(line)) {
    const {words:real,nested}=unwrap(words);
    for(const n of nested)hits.push(...screenCommands(n,depth+1));
    const hit=classify(real);if(hit)hits.push(hit);
  }
  return hits;
}

export const LAUNCH_DENY='Refused: this command would open or switch apps on the user\'s main screen. '+
  'Use phone_launch_app instead (it opens the app on the background agent display and does not disturb the user\'s main screen), then phone_observe / phone_do_steps.';
export const INPUT_DENY='Refused: input events without a display go to the user\'s main screen. '+
  'Use phone_tap / phone_swipe / phone_type_text / phone_press_key / phone_do_steps instead; they act only on the background agent display.';
export const STATUSBAR_DENY='Refused: cmd statusbar acts on the user\'s main screen (notification shade, quick settings). App operations run only on the background agent display; do not touch the user\'s status bar.';
export const MOVE_DENY='Refused: moving tasks between displays can pull an app onto the user\'s main screen. To hand an app to the user use phone_handoff.';

function denyFor(hit:ShellGuardHit,agentDisplay:number|null):string {
  const base={launch:LAUNCH_DENY,input:INPUT_DENY,statusbar:STATUSBAR_DENY,move:MOVE_DENY}[hit.kind];
  if(hit.kind==='launch' || hit.kind==='input') {
    if(hit.display===0)return base+' Display 0 is the user\'s main screen.';
    if(hit.display!==null && agentDisplay===null)return base+' There is no agent display right now; open the app with phone_launch_app first.';
    if(hit.display!==null)return base+` Display ${hit.display} is not the agent display.`;
    if(agentDisplay!==null)return base+` (If you really need a raw command, only one that explicitly targets the agent display is accepted: ${hit.kind==='input'?'input -d '+agentDisplay:'am start --display '+agentDisplay}. monkey is never accepted.)`;
  }
  return base;
}

/**
 * Decides whether a system_shell command may run. `agentDisplay` is the current agent display id (null when no phone
 * task holds one). Allowed: commands that touch no screen, and `am start --display <agent>` / `input -d <agent>` that
 * explicitly target the agent display. Refused: monkey (no display option), am start / cmd activity start / input without
 * a display or with another display, cmd statusbar, and task moves between displays.
 */
export function guardSystemShell(command:string,agentDisplay:number|null):ShellGuardDecision {
  for(const hit of screenCommands(command)) {
    const explicitAgent=hit.display!==null && agentDisplay!==null && hit.display===agentDisplay && hit.display!==0;
    const monkey=/^(\S*\/)?monkey\b/.test(hit.command);
    if((hit.kind==='launch' || hit.kind==='input') && explicitAgent && !monkey)continue;
    return {kind:'deny',reason:denyFor(hit,agentDisplay),hit};
  }
  return {kind:'continue'};
}

/** True when the command contains anything the guard looks at (so callers only ask Android for the display id then). */
export function touchesScreen(command:string):boolean {return screenCommands(command).length>0;}
