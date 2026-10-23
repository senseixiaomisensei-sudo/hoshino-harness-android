import { CredentialProvider } from '@deepseek-ai/dsh-credentials';
import type { CredentialKey, CredentialRecord, CredentialRef } from '@deepseek-ai/dsh-credentials';
import type { Context } from '../../node-host/src/platform.js';
export const name = 'android-credentials';
export const inject = ['androidBridge'];
/** Browser signing records live only for this launch; API keys are resolved from Kotlin. */
export default class AndroidCredentials extends CredentialProvider {
  private records = new Map<CredentialKey, CredentialRecord>();
  constructor(ctx: Context) { super(ctx); }
  async resolve(ref: CredentialRef) { return this.ctx.androidBridge.call<{ value: string; source: string } | undefined>('credential', { ref }); }
  async describe(ref: CredentialRef) { const hit = await this.resolve(ref); return { configured: !!hit?.value, source: 'android-keystore', writable: false }; }
  async set(): Promise<void> { throw new Error('Edit credentials in Android settings'); }
  async unset(): Promise<void> { throw new Error('Edit credentials in Android settings'); }
  async readRecord(key: CredentialKey) { return this.records.get(key); }
  async describeRecord(key: CredentialKey) { const r = this.records.get(key); return { configured: !!r, kind: r?.kind, writable: true }; }
  async listRecords() { return [...this.records].map(([key, record]) => ({ key, kind: record.kind })); }
  async modifyRecord(key: CredentialKey, mutate: (value: CredentialRecord | undefined) => Promise<CredentialRecord | undefined>) {
    // Connection uses this for a process-local signing grant; no provider login writes are composed.
    const record = await mutate(this.records.get(key));
    if (record) this.records.set(key, record);
    return this.records.get(key);
  }
  async deleteRecord(key: CredentialKey) { this.records.delete(key); }
}
