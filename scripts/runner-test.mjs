#!/usr/bin/env node
// Checks app/src/main/assets/runner.html against the live web workers in a real
// Chromium (the engine Android's WebView is built on): Python via Pyodide,
// JavaScript, TypeScript, errors with line numbers, and killing a runaway run.
// The page is served under the web app's origin, as DeviceRunner loads it.
//   npm i playwright && npx playwright install chromium && node scripts/runner-test.mjs
import { readFileSync } from "node:fs";
import { chromium } from "playwright";

const WEB = process.env.WEB_URL ?? "https://benchjot.vercel.app";
const html = readFileSync(new URL("../app/src/main/assets/runner.html", import.meta.url), "utf8");

const browser = await chromium.launch();
const page = await browser.newPage();
await page.route(`${WEB}/__runner`, (r) => r.fulfill({ contentType: "text/html", body: html }));
await page.addInitScript(() => {
  window.__events = [];
  window.BJ = { post: (s) => window.__events.push(JSON.parse(s)) };
});
await page.goto(`${WEB}/__runner`);
await page.waitForFunction(() => window.__events.some((e) => e.type === "ready"));

let nextId = 1;
async function run(lang, code, { killAfterStartMs } = {}) {
  const id = nextId++;
  await page.evaluate(([id, lang, code]) => window.bjRun(id, lang, code), [id, lang, code]);
  const deadline = Date.now() + 120_000;
  let killed = false;
  for (;;) {
    const evs = await page.evaluate((id) => window.__events.filter((e) => e.id === id), id);
    const end = evs.find((e) => ["done", "error", "syntax"].includes(e.type));
    if (end) return evs;
    if (killAfterStartMs && !killed && evs.some((e) => e.type === "start")) {
      await page.waitForTimeout(killAfterStartMs);
      await page.evaluate((id) => window.bjKill(id), id);
      killed = true;
      await page.waitForTimeout(500);
      return await page.evaluate((id) => window.__events.filter((e) => e.id === id), id);
    }
    if (Date.now() > deadline) throw new Error(`${lang}: no result in 120 s: ${JSON.stringify(evs)}`);
    await page.waitForTimeout(250);
  }
}
const out = (evs) => evs.filter((e) => e.type === "out").map((e) => e.text).join("");
const check = (cond, msg, evs) => {
  if (!cond) throw new Error(`${msg}\n${JSON.stringify(evs, null, 1)}`);
  console.log(`ok - ${msg}`);
};

let evs = await run("python", "import sys\nprint(sum(range(10)))\nprint('warn', file=sys.stderr)");
check(out(evs) === "45\nwarn\n" && evs.at(-1).type === "done", "python prints and finishes", evs);
evs = await run("python", "x = 1\n1/0");
check(evs.at(-1).type === "error" && /File "<exec>", line 2/.test(evs.at(-1).text) && /ZeroDivisionError/.test(evs.at(-1).text), "python error points at line 2", evs);
evs = await run("javascript", "console.log(1 + 1, {a: 1})\nawait new Promise(r => setTimeout(r, 10))\nconsole.log('after')");
check(out(evs) === '2 {"a":1}\nafter\n' && evs.at(-1).type === "done", "javascript prints, top-level await", evs);
evs = await run("javascript", "const a = 1\nnull.x");
check(evs.at(-1).type === "error" && /snippet\.js:3:/.test(evs.at(-1).text), "javascript error carries snippet.js line (offset 1)", evs);
evs = await run("javascript", "fetch('https://example.com')");
check(evs.at(-1).type === "error" && /not available/.test(evs.at(-1).text), "javascript has no network", evs);
evs = await run("typescript", "const n: number = 2\ninterface A { b: string }\nconsole.log(n * 21)");
check(out(evs) === "42\n" && evs.at(-1).type === "done", "typescript is transpiled and runs", evs);
evs = await run("typescript", "const = ;");
check(evs.at(-1).type === "syntax" && evs.at(-1).line === 1, "typescript syntax error has a line", evs);
evs = await run("javascript", "while (true) {}", { killAfterStartMs: 300 });
check(!evs.some((e) => e.type === "done"), "a runaway javascript run is killed", evs);
evs = await run("python", "while True: pass", { killAfterStartMs: 300 });
check(!evs.some((e) => e.type === "done"), "a runaway python run is killed", evs);
evs = await run("python", "print('fresh')");
check(out(evs) === "fresh\n", "python works again after a kill", evs);

await browser.close();
console.log("runner: all checks passed");
