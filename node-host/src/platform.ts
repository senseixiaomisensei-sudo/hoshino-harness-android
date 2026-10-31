import type { Context } from '@deepseek-ai/cordis';
export interface Provider {
  id: string; type: 'anthropic' | 'openai-compatible' | 'openai-codex'; baseURL: string;
  apiKey: string; models: string[];
  headers?: Record<string,string>;
  metadata?: { name?: string; models?: Array<{ id: string; contextLength?: number; maxOutput?: number; vision?: boolean; reasoning?: boolean; defaultEffort?: string;
    /** Wire reasoning levels the model documents, e.g. none/low/medium/high/xhigh/max. */
    reasoningLevels?: string[];
    /** Hosted web search override (auto/on/off) and the detected method id (see core-bridge WebSearchProbe). */
    webSearchMode?: string; webSearchMethod?: string }> };
}
export interface Bootstrap { callbackPort: number; callbackToken: string; workspace: string; home: string; selfHome?: string; safeMode?: boolean }
/**
 * Error text for a failed Android callback. Android answers 500 with {"error": message} where the message is
 * already credential-redacted (AndroidCallbackServer.errorBody); older hosts answer an empty body.
 */
export function callbackError(status: number, body: string): string {
  let message = '';
  try { const value = JSON.parse(body) as unknown; if (value && typeof value === 'object' && typeof (value as {error?: unknown}).error === 'string') message = (value as {error: string}).error; } catch {}
  message = message.trim().slice(0, 2000);
  return `Android callback failed (${status})${message ? `: ${message}` : ''}`;
}
export class AndroidBridge {
  private acceptReady!:()=>void;
  readonly whenReady=new Promise<void>(accept=>{this.acceptReady=accept;});
  markReady(){this.acceptReady();}
  constructor(readonly bootstrap: Bootstrap) {}
  async call<T>(method: string, body: unknown = {}, signal?: AbortSignal): Promise<T> {
    const response = await fetch(`http://127.0.0.1:${this.bootstrap.callbackPort}/${method}`, {
      method: 'POST', headers: { Authorization: `Bearer ${this.bootstrap.callbackToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body), signal: signal ?? AbortSignal.timeout(300_000),
    });
    if (!response.ok) throw new Error(callbackError(response.status, await response.text().catch(() => '')));
    return await response.json() as T;
  }
}
declare module '@deepseek-ai/cordis' { interface Context { androidBridge: AndroidBridge } }
export type { Context };
