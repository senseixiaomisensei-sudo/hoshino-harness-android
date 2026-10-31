// EV4 架构依赖守卫（插件侧；Kotlin 侧见 core-bridge/src/test/java/dev/hoshino/bridge/arch/ArchitectureGuardTest.kt）。
// 只读源码，不需要先 build：
// 1. 插件之间只经 Cordis 服务协作：可以 `import type {} from '../<别的插件>/index.js'` 取服务的类型声明，
//    不能 import 别的插件的内部文件（值导入）。现有违例记在 BASELINE，只减不增。
// 2. 插件只能 import：自己目录里的文件、node 内置模块、@deepseek-ai/*、锁文件里有的包、../../node-host/src/platform.js（只取类型）。
// 3. 不装载遥测/匿名标识类 dsh 包（roadmap 附录「不得装载」），插件里也不许 import。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, dirname, resolve, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { builtinModules } from 'node:module';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '../..');
const pluginsDir = join(repo, 'plugins');

const FORBIDDEN = [
  '@deepseek-ai/dsh-host-product-telemetry-otel', '@deepseek-ai/dsh-client-product-analytics', '@deepseek-ai/dsh-session-telemetry-otel',
  '@deepseek-ai/dsh-otel', '@deepseek-ai/dsh-anonymous-user-id', '@deepseek-ai/dsh-personalized-recommendations',
];

// 2026-10-31（codex/m2 bce6285）已有的跨插件值导入：只减不增。修掉一处就删掉对应一行（测试也会提示过期的条目）。
const BASELINE = new Set([
  'plugins/hoshino-m2/approval.ts -> plugins/android-api/provider-config.js',
  'plugins/hoshino-m2/index.ts -> plugins/hoshino-self/overrides.js',
  'plugins/hoshino-m2/index.ts -> plugins/android-policy/paths.js',
  'plugins/hoshino-m2/operations.ts -> plugins/android-policy/paths.js',
  'plugins/hoshino-self/index.ts -> plugins/android-policy/paths.js',
  // 2026-10-31 合入 codex/m2 2d075de（0.2.1-m2.8.1 热修复，已发版）时带进来的：system_shell 拒绝文案与隐藏规则以 android-policy/tier.ts 为准。
  // 不是本分支新增；待后续把 tier 的纯函数挪进 hh-bridge 或改走 ctx.mobilePolicy 后删掉这两行。
  'plugins/android-shizuku/index.ts -> plugins/android-policy/tier.js',
  'plugins/hoshino-m2/tool-mask.ts -> plugins/android-policy/tier.js',
]);

const walk = dir => readdirSync(dir).flatMap(name => {
  const p = join(dir, name);
  if (name === 'node_modules' || name === 'dist') return [];
  return statSync(p).isDirectory() ? walk(p) : (/\.(ts|mts|js|mjs)$/.test(name) && !name.endsWith('.d.ts') ? [p] : []);
});

const posix = p => p.split(sep).join('/');
const IMPORT = /^\s*(import|export)\s+(type\s+)?(?:[^'"]*?\s+from\s+)?['"]([^'"]+)['"]/gm;
const DYNAMIC = /\bimport\(\s*['"]([^'"]+)['"]\s*\)/g;

/** 一个文件里的 import：[{spec, typeOnly}]。整行注释里的不算。 */
export function importsOf(source) {
  const code = source.split('\n').filter(l => !/^\s*(\/\/|\*|\/\*)/.test(l)).join('\n');
  const out = [];
  for (const m of code.matchAll(IMPORT)) out.push({ spec: m[3], typeOnly: Boolean(m[2]) });
  for (const m of code.matchAll(DYNAMIC)) out.push({ spec: m[1], typeOnly: false });
  return out;
}

const lockPackages = (() => {
  const lock = JSON.parse(readFileSync(join(repo, 'node-host/package-lock.json'), 'utf8'));
  return new Set(Object.keys(lock.packages ?? {}).filter(k => k.startsWith('node_modules/')).map(k => k.slice('node_modules/'.length).split('/node_modules/').pop()));
})();
const packageName = spec => spec.startsWith('@') ? spec.split('/').slice(0, 2).join('/') : spec.split('/')[0];
const builtins = new Set(builtinModules);

/** 按规则检查 [files]，返回 {crossPlugin:[...], disallowed:[...], forbidden:[...]}。 */
export function check(files, root = repo) {
  const crossPlugin = [], disallowed = [], forbidden = [];
  for (const file of files) {
    const rel = posix(relative(root, file));
    const plugin = rel.split('/').slice(0, 2).join('/');
    for (const { spec, typeOnly } of importsOf(readFileSync(file, 'utf8'))) {
      if (FORBIDDEN.some(f => spec === f || spec.startsWith(f + '/'))) { forbidden.push(`${rel} -> ${spec}`); continue; }
      if (spec.startsWith('.')) {
        const target = posix(relative(root, resolve(dirname(file), spec)));
        if (target.startsWith(plugin + '/')) continue;
        if (target === 'node-host/src/platform.js') { if (!typeOnly) disallowed.push(`${rel} -> ${spec}(platform 只能取类型)`); continue; }
        if (target.startsWith('plugins/')) {
          // 取别的插件的服务类型声明（Cordis 的 context 扩展）可以；值导入不行
          if (typeOnly) continue;
          crossPlugin.push(`${rel} -> ${target}`);
          continue;
        }
        disallowed.push(`${rel} -> ${spec}`);
        continue;
      }
      if (spec.startsWith('node:') || builtins.has(spec)) continue;
      if (spec.startsWith('@deepseek-ai/')) continue;
      if (spec === '@hh/bridge') continue; // plugins/hh-bridge 发布给第三方插件用的名字
      if (lockPackages.has(packageName(spec))) continue;
      disallowed.push(`${rel} -> ${spec}(不在 node-host/package-lock.json 里)`);
    }
  }
  return { crossPlugin, disallowed, forbidden };
}

test('plugins only cooperate through Cordis services (no new cross-plugin value imports)', () => {
  const files = walk(pluginsDir);
  assert.ok(files.length > 10, 'plugin sources not found');
  const { crossPlugin, disallowed, forbidden } = check(files);
  const fresh = crossPlugin.filter(x => !BASELINE.has(x));
  assert.deepEqual(fresh, [], '插件 import 了别的插件的内部文件；改成经 Cordis 服务（ctx.<service>）协作，或把共用的纯函数放进 hh-bridge');
  const stale = [...BASELINE].filter(x => !crossPlugin.includes(x));
  assert.deepEqual(stale, [], 'BASELINE 里有已经修掉的条目，请删掉');
  assert.deepEqual(disallowed, []);
  assert.deepEqual(forbidden, []);
});

test('the kernel composition never mounts telemetry or anonymous-id packages', () => {
  const main = readFileSync(join(repo, 'node-host/src/main.ts'), 'utf8');
  for (const pkg of FORBIDDEN) {
    const short = pkg.replace('@deepseek-ai/dsh-', '');
    assert.ok(!main.includes(pkg) && !new RegExp(`row\\([^)]*['"]${short}['"]`).test(main), `main.ts 装载了 ${pkg}`);
  }
  assert.match(main, /DSH_TELEMETRY_DISABLED\s*=\s*'1'/);
});

test('the guard itself catches violations', () => {
  const imports = importsOf(`import type {Context} from '../../node-host/src/platform.js';\nimport {x} from '../other/inner.js';\n// import {y} from '../commented/out.js';\nimport type {} from '../svc/index.js';\nconst m = await import('@deepseek-ai/dsh-otel');`);
  assert.deepEqual(imports.map(i => [i.spec, i.typeOnly]), [
    ['../../node-host/src/platform.js', true], ['../other/inner.js', false], ['../svc/index.js', true], ['@deepseek-ai/dsh-otel', false],
  ]);
  assert.ok(existsSync(join(pluginsDir, 'hoshino-m2/index.ts')));
});
