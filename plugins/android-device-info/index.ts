import type { Context } from '../../node-host/src/platform.js';
import { defineTool } from '@deepseek-ai/dsh-tools';
export const name = 'android-device-info';
export const inject = ['tools', 'androidBridge'];
export function apply(ctx: Context): void {
  ctx.tools.register(defineTool({
    name: 'get_device_info', description: 'Read Android device model, OS version and battery percent.', parameters: {},
    output: {
      schema: { type: 'object', additionalProperties: false, properties: {
        model: { type: 'string', required: true }, androidVersion: { type: 'string', required: true },
        battery: { type: 'number', required: true },
      } },
      render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }],
    },
    execute: (_args, exec) => ctx.androidBridge.call('device-info', {}, exec.signal),
  }));
}
