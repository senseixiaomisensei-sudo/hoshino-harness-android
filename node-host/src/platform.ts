import type { Context } from '@deepseek-ai/cordis';
export interface Provider {
  id: string; type: 'anthropic' | 'openai-compatible'; baseURL: string;
  apiKey: string; models: string[];
  headers?: Record<string,string>;
  metadata?: { name?: string; models?: Array<{ id: string; contextLength?: number; maxOutput?: number; vision?: boolean; reasoning?: boolean; defaultEffort?: string }> };
}
export interface Bootstrap { callbackPort: number; callbackToken: string; workspace: string; home: string; selfHome?: string; safeMode?: boolean }
export class AndroidBridge {
  constructor(readonly bootstrap: Bootstrap) {}
  async call<T>(method: string, body: unknown = {}, signal?: AbortSignal): Promise<T> {
    const response = await fetch(`http://127.0.0.1:${this.bootstrap.callbackPort}/${method}`, {
      method: 'POST', headers: { Authorization: `Bearer ${this.bootstrap.callbackToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body), signal: signal ?? AbortSignal.timeout(300_000),
    });
    if (!response.ok) throw new Error(`Android callback failed (${response.status})`);
    return await response.json() as T;
  }
}
declare module '@deepseek-ai/cordis' { interface Context { androidBridge: AndroidBridge } }
export type { Context };
