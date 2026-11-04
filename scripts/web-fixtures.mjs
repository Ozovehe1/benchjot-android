#!/usr/bin/env node
// Regenerates core/src/test/resources/web/* from the web app's sources, the
// single source of truth for the rules the Android app mirrors. Run with
// node >= 22.6:  node --experimental-strip-types scripts/web-fixtures.mjs <path-to-web-checkout>
// CI runs it against a fresh checkout of Ozovehe1/benchjot and fails on any diff,
// so a rule changed on the web can't silently drift from the app.
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const web = resolve(process.argv[2] ?? "../research-notebook");
const out = resolve(import.meta.dirname, "../core/src/test/resources/web");
mkdirSync(out, { recursive: true });
const load = (p) => import(pathToFileURL(join(web, p)).href);
const write = (name, data) => writeFileSync(join(out, name), typeof data === "string" ? data : JSON.stringify(data, null, 2) + "\n");

// Languages (data only)
const langs = await load("src/lib/languages.ts");
write("languages.json", { languages: langs.LANGUAGES, common: langs.COMMON_KEYS, default: langs.DEFAULT_LANGUAGE });

// Link pattern as note_links() writes it in SQL (the web tests it equals LINK_PATTERN)
const sql = readFileSync(join(web, "supabase/migrations/20261104040000_note_tree_and_links.sql"), "utf8");
const m = /regexp_matches\(e\.notes, '((?:[^']|'')*)'/.exec(sql);
if (!m) throw new Error("note_links() pattern not found");
write("link_pattern.txt", m[1].replace(/''/g, "'") + "\n");

// Brand + palette
const brand = await load("src/lib/brand.ts");
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
const { detectLanguage } = await load("src/lib/detect-language.ts");
const detect = [
  "import numpy as np\nprint(np.zeros(3))", "const x = 1;\nconsole.log(x)", "interface A { b: string }", "#include <stdio.h>\nint main(){}",
  "#include <iostream>\nint main(){ std::cout << 1; }", "package main\nfunc main() {}", '{"a": [1, 2]}', "SELECT a FROM t WHERE b = 1",
  "x <- c(1, 2, 3)", "echo hello world", "name: x\nversion: 2", "# Title\n\nSome text", "hello", "def f(x):\n    return x",
];
write("detect.json", detect.map((code) => ({ code, language: detectLanguage(code) })));
console.log(`web fixtures written to ${out}`);
