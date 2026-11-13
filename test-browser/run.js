// Runs the :advanced browser probe in a vm context with window, document,
// navigator, and matchMedia, but no process, require, module, or Buffer, like
// a browser. Usage: node test-browser/run.js <bundle> <dark|light|none> <expected>
const vm = require('vm'), fs = require('fs');
const [bundle, scheme, expected] = process.argv.slice(2);
const lines = [];
const sandbox = {
  console: { log: (...a) => lines.push(a.join(' ')), error: (...a) => lines.push('ERR ' + a.join(' ')), warn: () => {}, info: () => {} },
  document: {},
  navigator: { userAgent: 'Mozilla/5.0 (Macintosh) Chrome/130' },
  setTimeout, clearTimeout,
};
if (scheme !== 'none') {
  sandbox.matchMedia = (q) => ({ matches: q === '(prefers-color-scheme: dark)' && scheme === 'dark' });
}
sandbox.window = sandbox; sandbox.self = sandbox; sandbox.globalThis = sandbox;
try { vm.runInNewContext(fs.readFileSync(bundle, 'utf8'), sandbox); }
catch (e) { lines.push('THREW ' + e.message); }
const actual = lines.join('\n');
const ok = actual === expected;
console.log(`${ok ? 'PASS' : 'FAIL'} prefers-color-scheme ${scheme}: ${actual}`);
if (!ok) { console.log(`     expected: ${expected}`); process.exitCode = 1; }
