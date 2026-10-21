// Exercises the actual content and popup scripts with separate WebExtension contexts.
// This verifies extension logic, not GeckoView/device integration.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const content = readFileSync(new URL('../android/gecko-demo/src/main/assets/paper/content.js', import.meta.url), 'utf8');
const popup = readFileSync(new URL('../android/gecko-demo/src/main/assets/paper/popup.js', import.meta.url), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));

function harness() {
  const saved = {};
  const subscribers = [];
  const browser = { storage: {
    local: {
      get: async defaults => ({ ...defaults, ...saved }),
      set: async values => { Object.assign(saved, values); subscribers.forEach(listener => listener(values, 'local')); }
    },
    onChanged: { addListener: listener => subscribers.push(listener) }
  } };
  const nodes = [];
  const document = { createElement: () => ({ textContent: '' }), head: { appendChild: node => nodes.push(node) } };
  const elements = new Map();
  const popupDocument = { getElementById: id => {
    if (!elements.has(id)) elements.set(id, { checked: false, textContent: '', events: {}, addEventListener(type, callback) { this.events[type] = callback; } });
    return elements.get(id);
  } };
  vm.runInNewContext(content, { browser, document });
  vm.runInNewContext(popup, { browser, document: popupDocument, navigator: { language: 'zh-CN' } });
  return { saved, nodes, elements, browser };
}

test('popup changes reach the real content script and reset removes injected CSS', async () => {
  const h = harness(); await flush();
  assert.equal(h.nodes.length, 1);
  assert.match(h.nodes[0].textContent, /#f4efdf/);
  assert.equal(h.elements.get('enabled').checked, true);
  h.elements.get('spacious').checked = true;
  await h.elements.get('spacious').events.change(); await flush();
  assert.match(h.nodes[0].textContent, /line-height: 2\.1/);
  assert.match(h.elements.get('status').textContent, /已保存/);
  await h.elements.get('reset').events.click(); await flush();
  assert.equal(h.nodes[0].textContent, '');
  assert.equal(h.nodes.length, 1, 'updates reuse the injected stylesheet');
  assert.deepEqual(h.saved, { enabled: false, spacious: false });
});

test('a fresh page context restores extension settings from storage', async () => {
  const h = harness(); await flush();
  await h.browser.storage.local.set({ enabled: false });
  const nodes = [];
  vm.runInNewContext(content, { browser: h.browser, document: {
    createElement: () => ({ textContent: '' }), head: { appendChild: node => nodes.push(node) }
  } });
  await flush();
  assert.equal(nodes[0].textContent, '');
  await h.browser.storage.local.set({ enabled: true, spacious: true }); await flush();
  assert.match(nodes[0].textContent, /line-height: 2\.1/);
});
