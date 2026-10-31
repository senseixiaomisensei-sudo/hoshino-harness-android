// RT10 intent verdict for one turn (pure; unit-tested in node-host/test/m293-intent.test.mjs).
// m2.9.3: every human message of the turn is judged (the opening message and any interjection), not only the first
// verdict of the turn; an explicit instruction anywhere in the turn wins over a later greeting or a vague follow-up.
import type { Intent } from './rules.js';

export type HumanMessage = { index: number; text: string };
export type IntentVerdict = { intent: Intent | null; by: 'rule' | 'model' | 'none' };

const text = (content: readonly any[] = []) => content.filter(b => b?.type === 'text').map(b => String(b.text ?? '')).join('');

/**
 * Messages a person typed in this turn, oldest first. Only `source.kind === 'user'` counts (dsh's `user` and `user-rpc`
 * sources): runtime notices, stop notes, takeover sentences (they ride on tool results), subagent and schedule messages
 * are never judged. Attachments are not text blocks, so an image or file never changes the verdict.
 */
export function humanMessages(events: readonly any[]): HumanMessage[] {
  const out: HumanMessage[] = [];
  events.forEach((e, index) => {
    if (e?.type === 'user/message' && e.data?.source?.kind === 'user') {
      const t = text(e.data.content).trim();
      if (t) out.push({ index, text: t });
    }
  });
  return out;
}

/**
 * ACT/READ from rules on any message of the turn wins; a rule-decided DISCUSS needs the latest message to be the
 * discussion; otherwise the latest message goes to the light model (earlier ones as context), and with no model the
 * gate stays open (null = not gated).
 */
export async function judgeTurnIntent(
  humans: readonly HumanMessage[],
  rule: (text: string) => Intent | null,
  model: ((prompt: string) => Promise<Intent | null>) | null,
): Promise<IntentVerdict> {
  if (!humans.length) return { intent: null, by: 'none' };
  const verdicts = humans.map(h => rule(h.text));
  if (verdicts.includes('ACT')) return { intent: 'ACT', by: 'rule' };
  if (verdicts.includes('READ')) return { intent: 'READ', by: 'rule' };
  const latest = verdicts.at(-1);
  if (latest === 'DISCUSS') return { intent: 'DISCUSS', by: 'rule' };
  if (!model) return { intent: null, by: 'none' };
  const earlier = humans.slice(0, -1).slice(-3).map(h => `- ${h.text.slice(0, 400)}`).join('\n');
  const prompt = (earlier ? `Earlier messages from the user in this task:\n${earlier}\n\n` : '') + `The user's current message:\n${humans.at(-1)!.text}`;
  const intent = await model(prompt);
  return intent === null ? { intent: null, by: 'none' } : { intent, by: 'model' };
}
