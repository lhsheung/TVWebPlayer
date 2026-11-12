/*
 * 解析器冒烟测试
 *
 * 用真实 Chrome 加载线上页面 → 注入 app/src/main/assets/extract.js → 断言结果。
 * 解析器改动后跑一遍，能立刻发现"某个结构不再被识别"。
 *
 * 用法：
 *   node tools/smoke.js                      # 跑默认用例
 *   node tools/smoke.js <url> [url...]       # 指定 URL（只做通用断言）
 *
 * 依赖：playwright-core + 本机 Chrome
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const EXTRACT = path.join(ROOT, 'app/src/main/assets/extract.js');

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
];

function findChrome() {
  for (const p of CHROME_CANDIDATES) if (fs.existsSync(p)) return p;
  throw new Error('找不到 Chrome/Edge，请安装或修改 CHROME_CANDIDATES');
}

const UA =
  'Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/131.0.0.0 Safari/537.36';

// 默认用例：断言是"结构级"的，不绑定具体条目文字，站点换内容也不会误报
const CASES = [
  {
    name: '首页：应识别出多个海报区块',
    url: 'https://www.ymvid.com/',
    check: (d) => {
      const posters = d.sections.filter((s) => s.kind === 'poster');
      assert(posters.length >= 2, `海报区块只有 ${posters.length} 个，期望 >=2`);
      const total = posters.reduce((a, s) => a + s.items.length, 0);
      assert(total >= 10, `海报条目只有 ${total} 个，期望 >=10`);
      const withImg = posters.flatMap((s) => s.items).filter((i) => i.i).length;
      assert(withImg / total >= 0.7, `带图比例 ${(withImg / total).toFixed(2)}，期望 >=0.7`);
    },
  },
  {
    name: '分类列表：应有大量卡片且识别出下一页',
    url: 'https://www.ymvid.com/list/1/c1-s0-v0-l0-t0-y0/time_desc',
    check: (d) => {
      const poster = d.sections.find((s) => s.kind === 'poster');
      assert(!!poster, '没有识别出任何海报区块');
      assert(poster.items.length >= 10, `卡片只有 ${poster.items.length} 个，期望 >=10`);
      assert(!!d.next, '没有识别出「下一页」');
      // 筛选器不应被当成选集
      const ep = d.sections.find((s) => s.title === '选集');
      assert(!ep, '把筛选器误判成了「选集」');
    },
  },
  {
    name: '详情页：应识别选集并检测到播放器',
    url: 'https://www.ymvid.com/play/7054/119198',
    check: (d) => {
      assert(d.hasVideo, '没有检测到播放器');
      const ep = d.sections.find((s) => s.title === '选集');
      assert(!!ep, '没有识别出「选集」行');
      assert(ep.items.length >= 3, `选集只有 ${ep.items.length} 集`);
    },
  },
];

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

async function main() {
  const { chromium } = require('playwright-core');
  const script = fs.readFileSync(EXTRACT, 'utf8');
  const extra = process.argv.slice(2);
  const cases = extra.length
    ? extra.map((u) => ({
        name: u,
        url: u,
        check: (d) => assert(d.sections.length > 0, '一个区块都没识别出来'),
      }))
    : CASES;

  const browser = await chromium.launch({
    executablePath: findChrome(),
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const ctx = await browser.newContext({
    viewport: { width: 1920, height: 1080 },
    userAgent: UA,
    locale: 'zh-CN',
  });
  const page = await ctx.newPage();

  let failed = 0;
  for (const c of cases) {
    process.stdout.write(`- ${c.name}\n  ${c.url}\n`);
    try {
      await page.goto(c.url, { waitUntil: 'domcontentloaded', timeout: 45000 });
      await page.waitForTimeout(2500);
      const raw = await page.evaluate(script);
      const data = JSON.parse(raw);
      c.check(data);
      const n = data.sections.reduce((a, s) => a + s.items.length, 0);
      console.log(`  ✅ 通过  sections=${data.sections.length} items=${n} next=${!!data.next}`);
    } catch (e) {
      failed++;
      console.log(`  ❌ 失败  ${e.message.split('\n')[0]}`);
    }
    console.log('');
  }

  await browser.close();
  console.log(failed === 0 ? `全部通过（${cases.length} 项）` : `${failed}/${cases.length} 项失败`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('FATAL:', e.message);
  process.exit(2);
});
