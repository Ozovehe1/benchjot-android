#!/usr/bin/env node
// Regenerates core/src/test/resources/web/* from the web app's sources, the
// single source of truth for the rules the Android app mirrors. Run with
// node >= 22.6:  node --experimental-strip-types scripts/web-fixtures.mjs <path-to-web-checkout>
// CI runs it against a fresh checkout of Ozovehe1/benchjot and fails on any diff,
// so a rule changed on the web can't silently drift from the app.
import { copyFileSync, cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { register } from "node:module";

// The web's modules import each other without the ".ts" extension (as its bundler allows); plain node
// needs it, so a relative import that doesn't resolve is retried with ".ts".
// The web also imports through its "@/" alias (its src/ folder); that is mapped here the same way.
const webSrc = pathToFileURL(resolve(process.argv[2] ?? "../research-notebook", "src") + "/").href;
register("data:text/javascript," + encodeURIComponent(`
  const WEB_SRC = ${JSON.stringify(webSrc)};
  export async function resolve(specifier, context, next) {
    if (specifier.startsWith("@/")) {
      const url = WEB_SRC + specifier.slice(2);
      try { return await next(url, context); } catch { return next(url + ".ts", context); }
    }
    try { return await next(specifier, context); }
    catch (e) {
      if (/^\\.\\.?\\//.test(specifier) && !/\\.[cm]?[jt]s$/.test(specifier)) return next(specifier + ".ts", context);
      throw e;
    }
  }
  // The web imports JSON as its bundler allows (no "with { type: 'json' }"); plain node needs the attribute.
  export async function load(url, context, next) {
    if (url.endsWith(".json")) return next(url, { ...context, importAttributes: { ...context.importAttributes, type: "json" } });
    return next(url, context);
  }`));

const web = resolve(process.argv[2] ?? "../research-notebook");
const out = resolve(import.meta.dirname, "../core/src/test/resources/web");
mkdirSync(out, { recursive: true });
const load = (p) => import(pathToFileURL(join(web, p)).href);
const write = (name, data) => writeFileSync(join(out, name), typeof data === "string" ? data : JSON.stringify(data, null, 2) + "\n");

// The Research Map model contract (rules, tokens and expected graphs), copied as the web keeps it.
// Absent on a web checkout from before the Research Map; then the committed copy stands.
const mapModel = join(web, "tests/fixtures/map_model.json");
if (existsSync(mapModel)) copyFileSync(mapModel, join(out, "map_model.json"));

// Languages (data only)
const langs = await load("src/lib/code/languages.ts");
write("languages.json", { languages: langs.LANGUAGES, common: langs.COMMON_KEYS, default: langs.DEFAULT_LANGUAGE });

// Link pattern as note_links() writes it in SQL (the web tests it equals LINK_PATTERN)
const sql = readFileSync(join(web, "supabase/migrations/20261104040000_note_tree_and_links.sql"), "utf8");
const m = /regexp_matches\(e\.notes, '((?:[^']|'')*)'/.exec(sql);
if (!m) throw new Error("note_links() pattern not found");
write("link_pattern.txt", m[1].replace(/''/g, "'") + "\n");

// Brand + palette
const brand = await load("src/lib/ui/brand.ts");
const css = readFileSync(join(web, "src/app/globals.css"), "utf8");
const block = (sel) => {
  const b = new RegExp(`${sel}\\s*\\{([^}]*)\\}`).exec(css)?.[1] ?? "";
  return Object.fromEntries([...b.matchAll(/--([\w-]+):\s*([^;]+);/g)].map(([, k, v]) => [k, v.trim()]));
};
const resolveVar = (v) => v.replace(/var\(--charcoal\)/, brand.BRAND.charcoal).replace(/var\(--ash\)/, brand.BRAND.ash);
const theme = (sel) => Object.fromEntries(Object.entries(block(sel)).map(([k, v]) => [k, resolveVar(v)]));
write("brand.json", { brand: brand.BRAND, mark: brand.MARK, dark: theme(":root,\\s*\\.dark"), light: theme("\\.light") });

// codeHash golden values from the web's own function
const { codeHash } = await load("src/lib/run/last-run.ts");
const samples = ["", "print(1)", "print(2)", "héllo 🌍\nworld", "x = 1\nfor i in range(3):\n    print(i)\n"];
write("codehash.json", samples.map((s) => ({ code: s, hash: codeHash(s) })));

// The detection rules' expected answers on a shared sample set
const { detectLanguage } = await load("src/lib/code/detect-language.ts");
const detect = [
  "import numpy as np\nprint(np.zeros(3))", "const x = 1;\nconsole.log(x)", "interface A { b: string }", "#include <stdio.h>\nint main(){}",
  "#include <iostream>\nint main(){ std::cout << 1; }", "package main\nfunc main() {}", '{"a": [1, 2]}', "SELECT a FROM t WHERE b = 1",
  "x <- c(1, 2, 3)", "echo hello world", "name: x\nversion: 2", "# Title\n\nSome text", "hello", "def f(x):\n    return x",
];
write("detect.json", detect.map((code) => ({ code, language: detectLanguage(code) })));
// The signed-out landing's example note (title, body, outcome, one code block with its saved
// run) and its output files, bundled into the app's assets as the web serves them from public/.
const sample = await load("src/lib/sample-note.ts");
const sampleAssets = resolve(import.meta.dirname, "../app/src/main/assets/sample");
rmSync(sampleAssets, { recursive: true, force: true });
mkdirSync(sampleAssets, { recursive: true });
// Each cell's outputs go in a folder named by its cell's id (the web serves them from public/sample-outputs/<cell>/).
writeFileSync(join(sampleAssets, "note.json"), JSON.stringify({ note: sample.SAMPLE_NOTE, cells: sample.SAMPLE_CELLS }, null, 2) + "\n");
sample.SAMPLE_OUTPUT_URLS.forEach((files, i) => {
  const dir = join(sampleAssets, sample.SAMPLE_CELLS[i].id);
  mkdirSync(dir, { recursive: true });
  for (const [name, url] of Object.entries(files)) copyFileSync(join(web, "public", url), join(dir, name));
});

// List dates: the shared cases with the web's own answers (core listDateLocal must match)
const { listDateLocal } = await load("src/lib/ui/format.ts");
const { LIST_DATE_CASES } = await load("tests/fixtures/list-date.ts");
write("list_date.json", LIST_DATE_CASES.map(([name, then, now]) => ({ name, then, now, date: listDateLocal(then, now) })));

// List headings: the shared cases with the web's own answers (core listGroupLocal must match)
const { listGroupLocal } = await load("src/lib/ui/format.ts");
const { LIST_GROUP_CASES } = await load("tests/fixtures/list-group.ts");
write("list_group.json", LIST_GROUP_CASES.map(([name, then, now]) => ({ name, then, now, group: listGroupLocal(then, now) })));

// Long code folds: the shared cases with the web's own answers (core CodeFold.kt must match)
const fold = await load("src/lib/code/code-fold.ts");
const { CODE_FOLD_CASES } = await load("tests/fixtures/code-fold.ts");
write("code_fold.json", { fold_lines: fold.FOLD_LINES, cases: CODE_FOLD_CASES.map((code) => ({ code, lines: fold.lineCount(code), folds: fold.folds(code), closed: fold.foldLabel(code, false), open: fold.foldLabel(code, true) })) });

// The note body's format bar and live view: the web's hand-written cases as it writes them
// (tests/fixtures/*.json, the authority for both platforms; core MarkdownFormat.kt and LiveMarkdown.kt
// must match), and the buttons in the web's order with the ones in the "+" sheet. Also the cell
// notes' cases (labels, collapse rule, [[links]] and search over body + cell notes; core CellNotes.kt).
// (Read from the source text: markdown-format.ts imports live-markdown without an extension, which
// plain node can't load.)
const mdSrc = readFileSync(join(web, "src/lib/editor/markdown-format.ts"), "utf8");
const constList = (name) => {
  const m = new RegExp(`export const ${name}[^=]*=\\s*\\[([^\\]]*)\\]`).exec(mdSrc);
  if (!m) throw new Error(`${name} not found in markdown-format.ts`);
  return [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
};
for (const f of ["markdown_format.json", "live_markdown.json", "cell_notes.json"]) copyFileSync(join(web, "tests/fixtures", f), join(out, f));
write("markdown_format_buttons.json", constList("MARKDOWN_FORMATS"));
write("markdown_format_sheet.json", constList("SHEET_FORMATS"));

// Which links in a note body open (http, https, mailto): the web's own answers (core isOpenableLink).
const { isOpenableLink } = await load("src/lib/web/safe-next.ts");
const { OPENABLE_LINK_CASES } = await load("tests/fixtures/openable-links.ts");
write("openable_links.json", OPENABLE_LINK_CASES.map((url) => ({ url, open: isOpenableLink(url) })));

// Error messages: the shared table and the web's own answers on the shared cases (core UserMessage.kt).
const um = await load("src/lib/ui/user-message.ts");
const { ERROR_CASES } = await load("tests/fixtures/error-messages.ts");
write("error_messages.json", {
  text: um.ERROR_TEXT, retry: um.RETRY_TEXT, actions: um.ACTION_TEXT, overrides: um.ACTION_OVERRIDES,
  cases: ERROR_CASES.map(([name, facts, action, kind]) => ({ name, facts, action, kind: um.errorKind(facts), expected: kind, text: um.userText(facts, action ?? undefined) })),
});

// Rich run outputs: the capture fixtures (manifests + chart specs made by the web's
// scripts/output-fixtures.py) for the chart renderer's mapping tests, and the web's own
// download formats and labels for each, which core/Outputs.kt must match.
const outputsSrc = join(web, "tests/fixtures/outputs");
rmSync(join(out, "outputs"), { recursive: true, force: true });
cpSync(outputsSrc, join(out, "outputs"), { recursive: true });
const outputs = await load("src/lib/run/outputs.ts");
const formats = {};
for (const c of readdirSync(outputsSrc).sort()) {
  const m = JSON.parse(readFileSync(join(outputsSrc, c, "manifest.json"), "utf8"));
  formats[c] = m.items.map((item) => ({ label: outputLabel(outputs, item), formats: outputs.downloadFormats(item) }));
}
function outputLabel(o, item) { return o.outputLabel(o.outputItem.parse(item)); }
write("output_formats.json", formats);
write("output_names.json", ["train.py", "", "my run (v2).py", "a.b.c"].flatMap((b) => [0, 4].map((i) => ({ base: b, index: i, name: outputs.downloadName(b, i, "png") }))));
// The interactive chart page (the "HTML (interactive)" download): the web's own pages on shared specs.
const { chartHtml } = await load("src/lib/run/chart-html.ts");
const htmlCases = [
  ["plotly", "6.0.0", '{"data":[{"type":"scatter","x":[1,2],"y":[3,4]}],"layout":{"title":"</script><!-- x"}}', "Orbit <1> & \"two\""],
  ["vega-lite", "5.0", '{"mark":"bar","data":{"values":[{"a":1}]}}', ""],
  ["bokeh", "3.6.2.dev1", '{"target_id":null,"root_id":"x","doc":{"roots":[]}}', "B"],
];
write("chart_html.json", htmlCases.map(([lib, version, spec, title]) => ({ lib, version, spec, title, html: chartHtml(lib, version, spec, title) })));
// Export note (Jupyter, web page, Markdown + files): the web's own exports of one shared note, which
// core NoteExport.kt must reproduce exactly. The web page's Markdown renderer is a stand-in here (each
// app renders Markdown its own way); everything around it must match.
{
  const exp = await load("src/lib/files/note-export.ts");
  const te = new TextEncoder();
  const png = Uint8Array.from([137, 80, 78, 71, 13, 10, 26, 10, 0, 1, 2, 250]);
  const run = (over) => ({ outcome: "ok", stdout: "", stderr: "", error_line: null, line: null, ms: 1234, where: "browser", code_hash: "h", ran_at: "2026-11-19T09:00:00.000Z", ...over });
  const note = {
    title: "Does warmup help? (v2) — été",
    body: "Hypothesis: [[Baseline run|abc]] beats [[Other]].\n\n- a\n- b",
    outcome: "success",
    exportedAt: "2026-11-19T10:00:00.000Z",
    cells: [
      {
        filename: "train.py", language: "python", code: "print(1)\n", noteBefore: "Expect 1.", noteAfter: "Got [[it]].",
        run: run({
          stdout: "1\n100%|██████████| 9.91M/9.91M [00:00<00:00, 12.5MB/s]\nacc 0.97\n",
          stderr: "[Benchjot] Ran on a T4 GPU.\n[Benchjot] Server ready in 26 s; your code ran for 4 s.\nwarn: slow\n",
          ms: 30_000, where: "server",
          outputs: { dir: "d", items: [
            { kind: "table", title: "", columns: ["a", "b"], index_columns: 0, rows: [[1, "x|y"], [2.5, null]], total_rows: 3, total_columns: 2, files: { csv: "0.csv" } },
            { kind: "image", source: "matplotlib", title: "Loss", width: 10, height: 10, files: { png: "1.png", mpl: "1.mpl", pdf: "1.pdf" } },
            { kind: "chart", lib: "plotly", version: "6.0.0", title: "Orbit", files: { json: "2.json", png: "2.png" } },
          ] },
        }),
        files: { "0.csv": te.encode("a,b\n1,x|y\n"), "1.png": png, "1.mpl": png, "1.pdf": te.encode("%PDF-1.4"), "2.json": te.encode('{"data":[{"x":[1]}],"layout":{}}'), "2.png": png },
      },
      { filename: "", language: "r", code: "x <- 1\nprint(x)", noteBefore: "", noteAfter: "", run: run({ outcome: "error", stderr: "Error: boom\n", error_line: "Error: boom", line: 2, where: "server" }), files: {} },
      { filename: "b.py", language: "python", code: "1/0", noteBefore: "", noteAfter: "", run: run({ outcome: "error", stderr: "Traceback (most recent call last):\n  File \"<exec>\", line 1\nZeroDivisionError: division by zero\n", error_line: "ZeroDivisionError: division by zero", line: 1, ms: 12 }), files: {} },
      { filename: "empty.py", language: "python", code: "", noteBefore: "", noteAfter: "", run: null, files: {} },
    ],
  };
  const b64 = (u8) => Buffer.from(u8).toString("base64");
  const render = (md) => `<p>${md.replace(/&/g, "&amp;").replace(/</g, "&lt;")}</p>`;
  write("note_export.json", {
    note: { ...note, cells: note.cells.map((c) => ({ ...c, files: Object.fromEntries(Object.entries(c.files).map(([k, v]) => [k, b64(v)])) })) },
    base: exp.exportBaseName(note.title),
    ipynb: exp.toNotebook(note),
    html: exp.toWebPage(note, render),
    md: exp.markdownFiles(note).map((f) => ({ name: f.name, data: b64(f.data) })),
    summaries: note.cells.filter((c) => c.run).map((c) => exp.runSummary(c.run)),
  });
}
// The note Assistant: the web's own answers on the shared cases (tests/fixtures/assistant.ts). Core
// AssistantOnline.kt (the note as read, the history sent back) must match.
const assistant = await load("src/lib/ai/assistant.ts");
const hf = await load("tests/fixtures/assistant.ts");
write("assistant.json", {
  limits: assistant.ASSISTANT_LIMITS,
  history_limits: assistant.ASSISTANT_HISTORY,
  context: hf.CONTEXT_CASES.map((k) => {
    const cells = assistant.assistantCells(k.cells);
    const r = assistant.noteContext(k.body, cells, k.max);
    return { name: k.name, body: k.body, rows: k.cells, max: k.max ?? null, cells, text: r.text, cut: r.cut };
  }),
  history: hf.HISTORY_CASES.map((k) => {
    const h = assistant.assistantHistory(k.summary, k.turns);
    return { name: k.name, summary: k.summary, turns: k.turns, expected: h, size: assistant.historySize(h) };
  }),
  // Where text the assistant writes into the note goes, and Undo taking it out again.
  writes: [
    ["Does depth help?\n\n**Result** *(after the runs)*\n\n## Method\nTwo spirals.", "Depth doesn't help.", "Result (after the runs)"],
    ["Does depth help?\n\n**Result** *(after the runs)*\n## Method", "Line one\n\n- two", "result"],
    ["Intro\n\n## Result", "Scores ~20%.", "## Result"],
    ["Intro", "Ends here.", ""],
    ["Intro\n\n\n", "Ends here.", "No such line"],
    ["", "First.", ""],
  ].map(([body, text, after]) => {
    const placed = assistant.placeNoteText(body, { text, after });
    return { body, text, after, placed: placed.body, under: placed.under, undone: assistant.removeNoteText(placed.body, text) };
  }),
});
// The agent: how it reads the note (cells numbered), what a run reports back, the steps as sent
// back and the summary kept with the Assistant's turns. Core Agent.kt must match.
const agent = await load("src/lib/ai/agent.ts");
// The cases this morning's loop came from: an animation, a video, a written image, a cell changed since its run.
const DIR = `${"0".repeat(8)}-0000-4000-8000-${"0".repeat(12)}`;
const GRAPH_ITEMS = [
  { kind: "image", source: "file", name: "animation-1.gif", width: 460, height: 345, title: "Grain growth", files: { gif: "0.gif", mp4: "0.mp4" } },
  { kind: "image", source: "file", name: "clip.mp4", width: 0, height: 0, title: "", files: { mp4: "1.mp4" } },
  { kind: "image", source: "file", name: "plot.png", width: 10, height: 10, title: "plot.png", files: { png: "2.png" } },
  { kind: "image", source: "pil", width: 10, height: 10, title: "", files: { png: "3.png" } },
  { kind: "chart", lib: "bokeh", version: "3", title: "Sweep", files: { json: "4.json" } },
  { kind: "image", source: "matplotlib", width: 640, height: 480, title: "Mean size", files: { png: "5.png", svg: "5.svg" }, seen: "Mean grain size rises from 4.1 nm   to 9.3 nm over 200 MCS, levelling off after 150." },
  { kind: "chart", lib: "plotly", version: "5", title: "Acc", files: { json: "6.json", png: "6.png" } },
  { kind: "table", index_columns: 1, columns: ["", "mcs", "grains", "mean_d"], rows: [[0, 10, 239, 4.1]], total_rows: 20, total_columns: 3, files: { csv: "7.csv" } },
];
// What the seer would look at, and the same with its words kept (outputs.ts toSee / withSeen).
const SEE_SETS = [[{ kind: "image", source: "file", name: "animation-1.gif", width: 4, height: 4, title: "Strip", files: { gif: "0.gif", mp4: "0.mp4", strip: "0.png" } }], GRAPH_ITEMS, GRAPH_ITEMS.map((i) => ({ ...i, seen: undefined })), Array.from({ length: 5 }, (_, n) => ({ kind: "image", source: "matplotlib", width: 1, height: 1, title: `F${n}`, files: { png: `${n}.png` } }))];
const ranOk = (code, extra = {}) => ({ outcome: "ok", stdout: "", stderr: "", error_line: null, line: null, ms: 12_600, where: "browser", code_hash: codeHash(code), ran_at: "2026-11-20T10:59:59.000Z", ...extra });
const ANIM = "# Potts grain growth, animated\nimport numpy as np\nfrom matplotlib.animation import FuncAnimation\nanim = FuncAnimation(fig, update)";
const GRAPH_EXTRA = [
  {
    title: "Grain growth", body: "**Question** Does annealing coarsen the grains?\n\n**Setup** Potts model, 200 MCS.\n\n## Result\n\nGrains grew 5%.", outcome: "success",
    cells: [
      { id: "c1", user_id: "u", created_at: "2026-11-20T10:00:00Z", updated_at: "2026-11-20T10:00:00Z", note_id: "n", language: "python", content: ANIM, note_before: "Expect: grains grow.", note_after: "They did.", position: 0, last_run: ranOk(ANIM, { stdout: "MCS 10: 239 grains\nFinal: 216 grains, mean d = 9.34 nm\n", outputs: { version: 1, dir: `${DIR}/${DIR}/${DIR}`, notes: ["Notebook-only lines (% and !) were skipped."], items: GRAPH_ITEMS } }) },
      { id: "c2", user_id: "u", created_at: "2026-11-20T10:00:00Z", updated_at: "2026-11-20T10:00:00Z", note_id: "n", language: "python", content: "print('new')", note_before: "", note_after: "", position: 1, last_run: ranOk("print('old')") },
      { id: "c3", user_id: "u", created_at: "2026-11-20T10:00:00Z", updated_at: "2026-11-20T10:00:00Z", note_id: "n", language: "python", content: "import torch\n1/0", note_before: "", note_after: "", position: 2, last_run: { ...ranOk("import torch\n1/0"), outcome: "error", error_line: "ZeroDivisionError: division by zero", where: "server", ms: 840 } },
      { id: "c4", user_id: "u", created_at: "2026-11-20T10:00:00Z", updated_at: "2026-11-20T10:00:00Z", note_id: "n", language: "r", content: "x <- 1", note_before: "", note_after: "", position: 3, last_run: null },
    ],
  },
  { title: "", body: "Just thinking out loud about dropout and its effect on small nets.", outcome: null, cells: [] },
  { title: "Empty", body: "", outcome: "failed", cells: [] },
  { title: "Linked", body: `**Question** Does it match [[Annealing at 600 C|${DIR}]]?\n\nSee also [[Old attempt]] and [[ Annealing at 600 C |${DIR}]].`, outcome: null, cells: [] },
];
// Connected notes: find_notes as the web says it (a fake set of other notes), and the request an asked note gets.
const OTHER = [
  { id: "a", title: "Annealing at 600 C", outcome: "success", updated_at: "2026-11-18T09:00:00.000Z", why: "linked from this note" },
  { id: "b", title: "  ", outcome: "failed", updated_at: "2026-11-10T09:00:00.000Z" },
  { id: "c", title: "Sweep", outcome: null, updated_at: "2026-11-01T09:00:00.000Z", why: "matches" },
];
const reachFor = () => ({ others: { find: async (q) => (q === "none" ? [] : OTHER), open: async () => null }, refs: new Map(), ask: async () => ({ message: "", calls: [], done: true }), budget: { left: agent.NOTES_REACH.budget }, depth: 0, path: [], from: "" });
const fakeNote = { read: async () => ({ title: "T", body: "", cells: [] }), add: async () => "", setCode: async () => {}, run: async () => {}, remove: async () => {}, setBody: async () => {} };
const findCases = [];
for (const query of ["", "anneal", "none"]) {
  const reach = reachFor();
  const r = await agent.makeCall({ tool: "find_notes", query }, fakeNote, { added: [], changed: [], note: [] }, undefined, reach);
  findCases.push({ query, found: query === "none" ? [] : OTHER, result: r.result, action: r.action.text });
}
const { OTHER_NOTES_CASES } = await load("tests/fixtures/other-notes.ts");
const otherNotes = await load("src/lib/notes/other-notes.ts");
const call = (tool, extra) => ({ tool, ...extra });
write("agent.json", {
  limits: agent.AGENT_LIMITS,
  context: hf.CONTEXT_CASES.map((k) => ({ name: k.name, body: k.body, cells: k.cells, max: k.max ?? null, text: assistant.noteContext(k.body, agent.agentCells(k.cells), k.max).text })),
  reports: hf.CONTEXT_CASES.flatMap((k) => k.cells.map((s, i) => ({ n: i + 1, cell: s, text: agent.runReport(i + 1, s) }))),
  // The note's context graph (core NoteGraph.kt), for every case and each outcome, and how each output is said.
  graphs: hf.CONTEXT_CASES.flatMap((k, i) => [["success", "failed", null][i % 3]].map((outcome) => ({ name: k.name, title: k.name, body: k.body, outcome, cells: k.cells, text: assistant.noteGraph(k.name, k.body, outcome, k.cells) }))),
  findNotes: findCases,
  askRequests: [["What grain size did it reach?", "Grain growth"], ["Why did it fail?", ""], ["x", "  " + "T".repeat(130)]].map(([q, from]) => ({ question: q, from, text: agent.askNoteRequest(q, from) })),
  otherNotes: OTHER_NOTES_CASES.map((c) => ({ ...c, similar: c.similar ?? [], list: otherNotes.rankOtherNotes(c.from, c.rows, c.matched, c.max, c.similar) })),
  notesReach: agent.NOTES_REACH,
  readOnly: agent.READ_ONLY_NOTE,
  produced: [...hf.CONTEXT_CASES.flatMap((k) => k.cells.flatMap((s) => (s.last_run?.outputs?.items ?? []))), ...GRAPH_ITEMS].map((item) => ({ item, text: assistant.producedLabel(item), short: assistant.producedLabel(item, 30) })),
  toSee: SEE_SETS.map((items) => {
    const o = { version: 1, dir: `${DIR}/${DIR}/${DIR}`, notes: [], items: items.map((i) => JSON.parse(JSON.stringify(i))) };
    const see = outputs.toSee(o);
    return { outputs: o, see, kept: outputs.withSeen(o, see.map((x) => ({ index: x.index, text: ` seen ${x.index} ` }))) };
  }),
  shown: [...hf.CONTEXT_CASES.flatMap((k) => k.cells.flatMap((s) => (s.last_run?.outputs?.items ?? []))), ...GRAPH_ITEMS].map((item) => ({ item, text: assistant.shownLabel(item) })),
  graphExtra: GRAPH_EXTRA.map((k) => ({ ...k, text: assistant.noteGraph(k.title, k.body, k.outcome, k.cells) })),
  timings: [0, 999, 1000, 1149, 1150, 1250, 12_345, 3_600_000].map((ms) => ({ ms, text: agent.runReport(1, { language: "python", content: "", note_before: "", note_after: "", last_run: { outcome: "ok", stdout: "", stderr: "", error_line: null, line: null, ms, where: "browser", code_hash: "h", ran_at: "2026-11-19T00:00:00.000Z" } }) })),
  steps: [
    { message: "Adding it.", calls: [call("add_cell", { language: "python", code: "x".repeat(2000) }), call("run_cell", { cell: 2 })], results: ["Added cell 2 (Python).", "r".repeat(4000)] },
    { message: "m".repeat(5000), calls: [call("edit_cell", { cell: 1, code: "print(1)" })], results: ["Changed cell 1."] },
  ].map((input) => ({ input, expected: agent.sentStep(input), size: agent.stepsSize([agent.sentStep(input)]) })),
  summaries: [
    [],
    [{ message: "Done.", actions: [] }],
    [{ message: "Writing it.", actions: [{ text: "Added cell 2 · Python", tone: "plain" }, { text: "Ran cell 2 · 1.2 s", tone: "ok" }] }, { message: "  ", actions: [] }],
    [{ message: "First.", actions: [{ text: "Changed cell 1", tone: "plain" }] }, { message: "It prints 2.", actions: [] }],
  ].map((steps) => ({ steps, text: agent.agentSummary(steps) })),
  // Code the agent may not put in the note (it would fake a result), and code it may.
  smells: [
    'try:\n    net2 = nets["spirals"]\nexcept NameError:\n    net2 = None\nax.set_title("Spiral boundaries (placeholder)")',
    "try:\n    x = load()\nexcept Exception:\n    pass",
    "try:\n    m = train()\nexcept Exception as e:\n    print(e)\n    m = None",
    "if 'nets' in globals():\n    use(nets)",
    "scores = globals().get('scores')",
    "plt.title('Accuracy (dummy data)')",
    "import numpy as np\nprint(np.mean([1, 2]))",
    "try:\n    import torch\nexcept ImportError:\n    raise SystemExit('needs torch')",
  ].map((code) => ({ code, smell: agent.fakeSmell(code) })),
  // Where the agent's run goes, decided by the app from the cell's imports (core Agent.kt runPlace).
  device_python: [...agent.DEVICE_PYTHON].sort(),
  server_imports: [
    "import numpy as np\nimport matplotlib.pyplot as plt",
    "import torch\nimport numpy as np",
    "from xgboost import XGBClassifier\nimport os, json, sys",
    "from IPython.display import HTML, display\nimport seaborn as sns",
    "import statsmodels.api as sm  # a comment\nfrom sklearn.linear_model import LinearRegression",
    "import transformers; import collections.abc",
    "from . import local\nimport tensorflow as tf",
  ].map((code) => ({ code, server: agent.serverOnlyImports(code) })),
});
// The standard library's module names, so the phone tells them from libraries only the server has.
copyFileSync(join(web, "src/lib/run/python-stdlib.json"), resolve(import.meta.dirname, "../core/src/main/resources/python-stdlib.json"));
// The phone runs the same capture module as the browser and the server.
copyFileSync(join(web, "public/workers/bj_capture.py"), resolve(import.meta.dirname, "../app/src/main/python/bj_capture.py"));
console.log(`web fixtures written to ${out}`);
