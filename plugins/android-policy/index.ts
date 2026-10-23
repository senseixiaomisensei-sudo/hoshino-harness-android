import type { Context } from '../../node-host/src/platform.js';
import { realpath, mkdir } from 'node:fs/promises';
import { isAbsolute, relative, resolve } from 'node:path';
export const name = 'android-policy';
export const inject = ['androidBridge', 'tools', 'fs'];
export function apply(ctx: Context): void {
  // M0 exposes only device info and dsh read/write. All other tools fail closed.
  ctx.on('tools/pre-execute', async (exec, next) => {
    if (!['get_device_info', 'read', 'write'].includes(exec.name)) return { kind: 'deny', reason: 'Tool unavailable in M0' };
    if (exec.name !== 'get_device_info') {
      const root = await realpath(ctx.androidBridge.bootstrap.workspace);
      const path = resolve(root, String((exec.arguments as Record<string, unknown>).file_path ?? ''));
      const rel = relative(root, path);
      if (rel.startsWith('..') || isAbsolute(rel)) return { kind: 'deny', reason: 'Outside workspace' };
      let canonical: string;
      try { canonical = await realpath(path); } catch {
        try { canonical = resolve(await realpath(resolve(path, '..')), path.split(/[\\/]/).pop()!); }
        catch { return { kind: 'deny', reason: 'Parent must already exist' }; }
      }
      const canonicalRel = relative(root, canonical);
      if (canonicalRel.startsWith('..') || isAbsolute(canonicalRel)) return { kind: 'deny', reason: 'Symlink outside workspace' };
      if (exec.name === 'write') {
        const approved = await ctx.androidBridge.call<{ approved: boolean }>('approve', { kind: 'tool', tool: exec.name, path }, exec.signal);
        if (!approved.approved) return { kind: 'deny', reason: 'User declined' };
      }
    }
    return next();
  });
  ctx.on('agent/request', async (payload, next) => {
    const approved = await ctx.androidBridge.call<{ approved: boolean }>('approve', { kind: 'network' }, payload.signal);
    if (!approved.approved) throw new Error('Network request declined');
    return next();
  });
}

