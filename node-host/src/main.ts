import { boot, loadLayeredEnv } from '@deepseek-ai/dsh-app-boot';
import { DSH_LAUNCH_ENVIRONMENT_KEY } from '@deepseek-ai/dsh-launch-environment';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createInterface } from 'node:readline';
import {writeSelfDocs} from './self-docs.js';
import { AndroidBridge, type Bootstrap, type Context } from './platform.js';
// Bootstrap travels over a private stdin pipe, never argv, environment, or a file.
const lines = createInterface({ input: process.stdin });
const line = await new Promise<string>((accept, reject) => {
  lines.once('line', accept); lines.once('close', () => reject(new Error('Missing bootstrap')));
});
lines.close();
const bootstrap = JSON.parse(line) as Bootstrap;
if (!Number.isInteger(bootstrap.callbackPort) || bootstrap.callbackPort < 1 || !bootstrap.callbackToken) throw new Error('Invalid bootstrap');
const bridge = new AndroidBridge(bootstrap);
await mkdir(bootstrap.workspace, { recursive: true });
await mkdir(bootstrap.home, { recursive: true });
const selfHome = bootstrap.selfHome ?? resolve(bootstrap.home, 'hoshino');
await mkdir(resolve(selfHome, 'skills'), { recursive: true });
process.chdir(bootstrap.workspace);
process.env.DSH_HOME = bootstrap.home;
process.env.DSH_TELEMETRY_DISABLED = '1';
const plugins = resolve(dirname(fileURLToPath(import.meta.url)), '../../plugins');
type Row = { id: string; name: string; config?: object; inject?: string[] };
const rows: Row[] = [];
const row = (id: string, pkg = id, config?: object, inject?: string[]) => rows.push({ id, name: `@deepseek-ai/dsh-${pkg}`, ...(config && {config}), ...(inject && {inject}) });
row('timer', '../cordis-plugin-timer'); rows[0]!.name = '@deepseek-ai/cordis-plugin-timer';
for (const id of ['llm', 'session', 'session-projection', 'agent', 'tools', 'working-directory', 'commands', 'llm-retry', 'jobs-local']) row(id);
row('token-meter');
row('agent-default-model', 'agent-default-model', { provider: 'unconfigured', model: 'unconfigured' });
row('session-title', 'session-title', { fallbackMaxWords: 5, fallbackMaxBytes: 40, maxTitleBytes: 80 });
row('agent-loop', 'agent-loop', { agents: [] });
row('system-prompt', 'system-prompt', { personaPrefix: 'You are Hoshino, an Android assistant. Use get_device_info when asked about this device. Use write for file creation.', includeRuntimeContext: false });
row('filesystem', 'fs-local', { cwd: bootstrap.workspace });
row('sandbox', 'sandbox-local');
row('sandbox-policy', 'sandbox-policy', { mode: 'danger-full-access', workspaceRoot: bootstrap.workspace });
row('tool-fs', 'tool-fs', { read: true, write: true, edit: true, readImage: true });
row('sessions', 'session-persistence-jsonl', { root: resolve(bootstrap.home, 'sessions'), compression: 'none' });
row('session-checkpoint-policy');
row('attachment-local');
row('session-query', 'session-query-sqlite', { path: ':memory:', openAt: 'first-search' });
row('storage'); row('storage-json', 'storage-json', { root: resolve(bootstrap.home, 'storages') });
row('storage-domain', 'storage-domain', { backend: 'json' }); row('workspace');
rows.push({ id: 'subprocess', name: resolve(plugins, 'android-subprocess', 'index.js') });
row('shell-env');
row('shell', 'bash-local', { cwd: bootstrap.workspace, timeoutMs: 120000, maxTimeoutMs: 600000, maxOutputBytes: 64000, maxSpillBytes: 67108864 });
row('tool-bash', 'tool-bash', { enableRunInBackground: false, promoteOnTimeout: false });
row('terminals', 'terminal');
row('terminal-bash', 'terminal-bash', { shellPath: '/bin/bash', timeoutMs: 120000 });
row('skills', 'skill');
rows.push({ id:'skill-filesystem',name:resolve(plugins,'android-skills','index.js'),config:{includeDefaultRoots:false,customSkillDirs:[resolve(selfHome,'skills')],watch:false} });
row('tool-skill');
row('tool-todo','tool-todo',{allowParallelInProgress:true});
row('subagent');row('subagent-spawn-in-process');row('subagent-fork-in-process');
row('tool-subagent','tool-subagent',{provider:'spawn',toolName:'subagent',maxDepth:3});
row('tool-subagent-fork','tool-subagent',{provider:'fork',toolName:'fork_agent',maxDepth:3});
row('tool-subagent-control');
row('goal');row('goal-round-driver');row('tool-goal');
row('schedule');row('tool-schedule');
row('typert', 'typert-registry'); row('remote-access', 'api-remote-access');
row('webserver', 'host-webserver', { host: '127.0.0.1', port: 0 });
row('connection', 'client-connection', {}, ['webServer']);
row('typert-gateway', 'api-gateway');
row('file-upload', 'client-file-upload');
row('session-controller', 'api-session-controller', { nativeOpen: false });
row('workspace-controller', 'api-workspace-controller');
row('terminal-controller', 'api-terminal-controller', { shell: { path: '/bin/bash', name: 'bash', args: ['--noprofile', '--norc', '-i'] }, unattendedTimeoutMs: 0 });
for (const id of ['android-credentials', 'android-policy', 'android-device-info', 'android-ui', 'android-shizuku', 'hoshino-m2', 'hoshino-self', 'android-api']) rows.push({ id, name: resolve(plugins, id, 'index.js') });
const configPath = resolve(bootstrap.home, 'mobile.cordis.yml');
await writeFile(configPath, JSON.stringify(rows));
let bootContext: Context | undefined;
const startupWatch=setTimeout(() => {
  for (const service of ['agents','fs','attachments','sessionController','connection','webServer','subprocess','shell','skills','terminals','terminalController']) {
    console.error(`hoshino: boot ${service} ${bootContext?.get(service) ? 'ready' : 'pending'}`);
  }
}, 10000).unref();
const ctx = await boot('hoshino', configPath, [], (ctx) => {
  bootContext=ctx;
  ctx.provide('androidBridge', bridge);
  ctx.provide(DSH_LAUNCH_ENVIRONMENT_KEY, loadLayeredEnv('hoshino'));
}, new URL('../../../package.json', import.meta.url).href);
clearTimeout(startupWatch);
for (const key of ['agents', 'sessionController', 'connection', 'webServer', 'mobileApi']) {
  if (!ctx.get(key)) throw new Error(`Kernel required service did not start: ${key}`);
}
const inputPath=resolve(dirname(fileURLToPath(import.meta.url)),'../../self-input.json');
await writeSelfDocs(ctx,rows,resolve(selfHome,'self'),inputPath);
if(process.argv.includes('--self-document-only')) {await ctx.fiber.dispose();process.exit(0);}
await bridge.call('ready', ctx.get('mobileApi'));
const shutdown = async () => { await ctx.fiber.dispose(); process.exit(0); };
process.once('SIGTERM', () => void shutdown());
process.once('SIGINT', () => void shutdown());
