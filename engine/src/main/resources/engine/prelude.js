// The JS sandbox, as the web's public/workers/js.js: console goes to the output,
// network and storage APIs are removed, timers wait in Kotlin (no event loop here).
// Host functions: __bj_out(stream, text), __bj_sleep(ms) (async).
(() => {
  const out = globalThis.__bj_out;
  const sleep = globalThis.__bj_sleep;

  function blocked(name) {
    return function () {
      throw new Error(name + " is not available when running here");
    };
  }
  for (const name of ["fetch", "XMLHttpRequest", "WebSocket", "EventSource", "importScripts", "indexedDB", "caches", "BroadcastChannel"]) {
    const value = blocked(name);
    Object.defineProperty(globalThis, name, { get: () => value, configurable: false });
  }

  function show(v) {
    if (typeof v === "string") return v;
    if (v instanceof Error) return v.stack ? String(v) + "\n" + v.stack : String(v);
    try {
      const s = JSON.stringify(v, (_k, x) => (typeof x === "bigint" ? x.toString() + "n" : x));
      return s === undefined ? String(v) : s;
    } catch {
      return String(v);
    }
  }
  const line = (stream) => (...args) => out(stream, args.map(show).join(" ") + "\n");
  globalThis.console = { log: line("stdout"), info: line("stdout"), debug: line("stdout"), warn: line("stderr"), error: line("stderr") };

  let nextTimer = 1;
  const live = new Set();
  function later(fn, ms, args, repeat) {
    const id = nextTimer++;
    live.add(id);
    const tick = () =>
      sleep(Math.max(0, Number(ms) || 0)).then(() => {
        if (!live.has(id)) return;
        if (!repeat) live.delete(id);
        if (typeof fn === "function") fn(...args);
        if (repeat && live.has(id)) tick();
      });
    tick();
    return id;
  }
  globalThis.setTimeout = (fn, ms, ...args) => later(fn, ms, args, false);
  globalThis.setInterval = (fn, ms, ...args) => later(fn, ms, args, true);
  globalThis.clearTimeout = globalThis.clearInterval = (id) => void live.delete(id);
  if (typeof globalThis.queueMicrotask !== "function") globalThis.queueMicrotask = (fn) => void Promise.resolve().then(fn);
  delete globalThis.__bj_sleep;
})();
