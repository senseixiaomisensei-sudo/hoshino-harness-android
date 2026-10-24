import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { LocalSubprocessRuntime } from '@deepseek-ai/dsh-subprocess-local';
import type { Context } from '../../node-host/src/platform.js';

/** Reuses the upstream process/PTY owner; changes only the deployment-owned spill directory. */
export default class AndroidSubprocessRuntime extends LocalSubprocessRuntime {
  static inject = ['androidBridge'];
  constructor(ctx: Context) {
    super(ctx);
    const spillDir = resolve(ctx.androidBridge.bootstrap.workspace, '.hoshino-output');
    mkdirSync(spillDir, { recursive: true });
    this.internals = { ...this.internals, spillDir };
  }
}
