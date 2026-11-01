// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// 来源：Mika core/skills/SkillDrafter.validate、core/memory/SensitiveFilter、core/record/Redactor、
// core/skills/PersonalSkills.version、core/general/SkillSave（5b97ea4）。改写为 TypeScript，接到 self_create_skill。
import { createHash } from 'node:crypto';

/** 整份 SKILL.md（含 front matter）的字符上限，与 Mika SkillDrafter.MAX_CHARS 相同。 */
export const MAX_SKILL_CHARS = 4000;
export const MAX_DESCRIPTION_CHARS = 300;
/** 五个小节；英文标题也认（`## Entry` 等），便于英文技能。 */
export const SECTIONS: ReadonlyArray<readonly [string, ...string[]]> = [
  ['入口', 'Entry', 'When to use'],
  ['步骤', 'Steps'],
  ['容易出错的地方', 'Pitfalls'],
  ['怎样算办成', 'Done when', 'Success'],
  ['什么时候停下交给用户', 'Hand off', 'When to stop'],
];
export const SKILL_NAME = /^[a-z][a-z0-9-]{0,63}$/;
/** 反引号里形如工具名的标识符（只认带下划线或已注册的名字，见 unknownTools）。 */
const TOOL_MENTION = /`([a-z][a-z0-9_]*)`/g;

export type SkillProblem = 'invalid_name' | 'missing_description' | 'description_too_long' | 'missing_section' | 'too_long' | 'unknown_tool' | 'sensitive';

export const PROBLEM_TEXT: Record<SkillProblem, string> = {
  invalid_name: 'Skill not saved: name must be lowercase letters, digits and hyphens, starting with a letter (max 64).',
  missing_description: 'Skill not saved: description is required (one sentence: when to use it).',
  description_too_long: `Skill not saved: description must be one line of at most ${MAX_DESCRIPTION_CHARS} characters.`,
  missing_section: 'Skill not saved: content needs the sections ' + SECTIONS.map(s => '## ' + s[0]).join(', ') + '.',
  too_long: `Skill not saved: the whole SKILL.md must be at most ${MAX_SKILL_CHARS} characters.`,
  unknown_tool: 'Skill not saved: backticks are only for real tool names; a backticked name is not a registered tool.',
  sensitive: 'Skill not saved: it contains a credential, password/code, ID or card number. Never put secrets in a skill.',
};

/** 版本号：整份 SKILL.md 的 SHA-256 前 12 位（读了再改，改的时候带上它；对不上说明中途被改过）。 */
export function skillVersion(markdown: string): string {
  return createHash('sha256').update(markdown, 'utf8').digest('hex').slice(0, 12);
}

/** 正文若自带 front matter（模型把整份文档传进来）先去掉，name/description 只认参数。 */
export function stripFrontMatter(content: string): string {
  const text = content.replace(/\r\n/g, '\n').replace(/^\uFEFF/, '');
  return text.replace(/^---\n[\s\S]*?\n---\n/, '');
}

/** 代码生成 front matter：名字、描述由参数给，正文不允许自带 front matter（避免覆盖 name）。 */
export function renderSkill(name: string, description: string, content: string): string {
  const body = stripFrontMatter(content).trim();
  return `---\nname: ${name}\ndescription: ${JSON.stringify(description.trim())}\n---\n${body}\n`;
}

// ---------------------------------------------------------------- 敏感内容（Mika SensitiveFilter + Redactor 规则）
const KEYWORDS = '支付密码|密码|口令|验证码|校验码|动态码|安全码|安全问题|(?<![A-Za-z])(?:password|passcode|passwd|cvv|cvc|verification code|security code|security question|one-time code)(?![A-Za-z])';
const DIGIT_KEYWORDS = '(?<![A-Za-z])(?:pin|otp)(?![A-Za-z])';
const GAP = '[\\s:：=,，是为叫]{0,4}(?:(?:is|was|are)\\s+)?';
const OWNER = '\\s*(?:就是|是|为)(?:我的|我|用户的)?\\s*';
const KEYWORD_RULES = [
  new RegExp(`(?:${KEYWORDS})${GAP}[A-Za-z0-9]{4,}`, 'i'),
  new RegExp(`(?<![A-Za-z0-9])[A-Za-z0-9]{4,}${OWNER}(?:${KEYWORDS})`, 'i'),
  new RegExp(`${DIGIT_KEYWORDS}${GAP}[0-9]{4,}`, 'i'),
  new RegExp(`(?<![A-Za-z0-9])[0-9]{4,}${OWNER}${DIGIT_KEYWORDS}`, 'i'),
];
const KEY_PATTERNS = [
  /\bsk-[A-Za-z0-9_-]{16,}/, /\bBearer\s+[A-Za-z0-9._~+/=-]{8,}/, /\bAIza[0-9A-Za-z_-]{30,}/, /\bAQ\.[0-9A-Za-z_-]{20,}/, /\bgh[pousr]_[A-Za-z0-9]{20,}/,
  /\bgithub_pat_[A-Za-z0-9_]{20,}/, /\bxox[abprs]-[A-Za-z0-9-]{10,}/, /-----BEGIN [A-Z ]*PRIVATE KEY-----/,
  // 原 assertNoSecrets 的口径：JSON/YAML 形式写出凭据字段
  /(?:api[_-]?key|authorization|password|access[_-]?token)\s*["']?\s*:/i,
];
const ID_NUMBER = /(?<![0-9])[1-9][0-9]{16}[0-9Xx](?![0-9A-Za-z])/g;
const CARD_LIKE = /(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])/g;
const ID_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2];
const ID_CHECK = '10X98765432';

function validId(id: string): boolean {
  const y = Number(id.slice(6, 10)), m = Number(id.slice(10, 12)), d = Number(id.slice(12, 14));
  const date = new Date(Date.UTC(y, m - 1, d));
  if (y < 1900 || date.getUTCFullYear() !== y || date.getUTCMonth() !== m - 1 || date.getUTCDate() !== d) return false;
  let sum = 0; for (let i = 0; i < 17; i++) sum += (id.charCodeAt(i) - 48) * ID_WEIGHTS[i]!;
  return ID_CHECK[sum % 11] === id[17]!.toUpperCase();
}
function luhn(digits: string): boolean {
  if (digits.length < 13 || digits.length > 19) return false;
  let sum = 0;
  [...digits].reverse().forEach((c, i) => { let n = c.charCodeAt(0) - 48; if (i % 2 === 1) { n *= 2; if (n > 9) n -= 9; } sum += n; });
  return sum % 10 === 0;
}

/** 命中的类别（api_key | id_number | card_number | password），未命中 null。 */
export function sensitiveHit(text: string): string | null {
  const t = text.normalize('NFKC'); const compact = t.replace(/\s+/g, '');
  if (KEY_PATTERNS.some(p => p.test(t))) return 'api_key';
  if ([...t.matchAll(ID_NUMBER)].some(m => validId(m[0]))) return 'id_number';
  if ([...t.matchAll(CARD_LIKE)].some(m => luhn(m[0].replace(/\D/g, '')))) return 'card_number';
  if (KEYWORD_RULES.some(r => r.test(t) || r.test(compact))) return 'password';
  return null;
}

/**
 * 结构校验（Mika SkillDrafter.validate 的口径）：名字合法、有一行描述、五个小节齐全、整份不超长、
 * 反引号里的工具名都已注册、敏感过滤不命中。返回第一个问题；全部通过为 null。
 */
export function validateSkill(input: { name: unknown; description: unknown; content: unknown; toolNames: ReadonlySet<string> }): SkillProblem | null {
  const { name, description, content, toolNames } = input;
  if (typeof name !== 'string' || !SKILL_NAME.test(name)) return 'invalid_name';
  if (typeof description !== 'string' || !description.trim()) return 'missing_description';
  if (description.length > MAX_DESCRIPTION_CHARS || /[\r\n]/.test(description.trim())) return 'description_too_long';
  if (typeof content !== 'string') return 'missing_section';
  const md = renderSkill(name, description, content);
  const headings = stripFrontMatter(content).split('\n').filter(l => l.startsWith('## ')).map(l => l.slice(3).trim().toLowerCase());
  if (SECTIONS.some(aliases => !headings.some(h => aliases.some(a => h.startsWith(a.toLowerCase()))))) return 'missing_section';
  if (md.length > MAX_SKILL_CHARS) return 'too_long';
  for (const m of md.matchAll(TOOL_MENTION)) if (!toolNames.has(m[1]!)) return 'unknown_tool';
  if (sensitiveHit(md) !== null) return 'sensitive';
  return null;
}
