// CX2 automatic compaction: the original dsh `BasicCompactionEngine` (trigger, retention, tool-pair-safe ranges,
// overflow recovery, durable bracket transaction) with only its documented `summarize()` hook overridden:
//  - every human message in the folded span is appended verbatim ("user words are never trimmed, restored after");
//  - `strategy: rules` (or any model-summary failure) writes a deterministic, model-free checkpoint instead;
//  - the summary model follows Hoshino routing.json `compact` when set.
// dsh itself is not forked or patched; this file only subclasses its exported engine.
import { BasicCompactionEngine } from '@deepseek-ai/dsh-compaction-basic';
import type { Context } from '../../node-host/src/platform.js';
import { extractPins, renderPins, ruleSummary, type Msg } from './core.js';
import { settings } from './state.js';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

type SummaryInput = { messages: readonly Msg[]; tools?: unknown[] };
type SummaryResult = { summary: Array<{ type: 'text'; text: string }>; provider: string; model: string; rawOutput?: unknown; [k: string]: unknown };

const size = (messages: readonly Msg[]) => messages.reduce((n, m) => n + m.content.reduce((k, b) => k + String(b.text ?? '').length + String(b.arguments ?? '').length, 0), 0);

export default class HoshinoCompactionEngine extends BasicCompactionEngine {
  constructor(ctx: any, config: Record<string, unknown> = {}) {
    // The user-tunable trigger lives in <selfHome>/config/context.json (read synchronously: the engine validates at construction).
    let ratio: unknown;
    try { const b = ctx.get('androidBridge')?.bootstrap; if (b) ratio = JSON.parse(readFileSync(join(b.selfHome ?? join(b.home, 'hoshino'), 'config', 'context.json'), 'utf8'))?.compaction?.thresholdRatio; } catch { /* defaults */ }
    super(ctx, typeof ratio === 'number' && ratio >= 0.3 && ratio <= 0.95 ? { ...config, thresholdRatio: ratio } : config);
  }
  /** Model-free summaries written since start (for status/diagnostics). */
  ruleSummaries = 0;
  modelSummaries = 0;

  // @ts-ignore dsh declares summarize on the base class; signature kept identical.
  override async summarize(input: SummaryInput, agent: any, signal?: AbortSignal): Promise<SummaryResult> {
    const pins = extractPins(input.messages);
    const routed = agent?.session?.requestHeader?.()?.config;
    if (settings.compaction.strategy !== 'rules') {
      const self = this as any;
      const saved = self.config;
      try {
        const route = (this.ctx as Context).get('mobileM2')?.config.get('routing.json')?.compact;
        if (route?.providerId && route?.modelId) self.config = { ...saved, summarizationProvider: String(route.providerId), summarizationModel: String(route.modelId) };
        const result = await (super.summarize as any)(input, agent, signal) as SummaryResult;
        const pinned = renderPins(pins);
        const out = pinned ? { ...result, summary: [...result.summary, { type: 'text' as const, text: '\n\n' + pinned }] } : result;
        // The engine rejects a checkpoint that does not shrink its span; prefer the compact rule text over a wasted model call.
        if (out.summary.reduce((n, b) => n + b.text.length, 0) < size(input.messages) * 0.8) { this.modelSummaries++; return out; }
        (this.ctx as Context).logger.info('model summary was not smaller than its span; using the model-free checkpoint');
      } catch (error) {
        if (signal?.aborted) throw error;
        (this.ctx as Context).logger.warn('model summary failed (%s); using the model-free checkpoint', error instanceof Error ? error.message.slice(0, 200) : 'error');
      } finally { self.config = saved; }
    }
    this.ruleSummaries++;
    return { summary: [{ type: 'text', text: ruleSummary(input.messages, pins) }], provider: routed?.provider || 'hoshino', model: 'hoshino-rules' };
  }
}
