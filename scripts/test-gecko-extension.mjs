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

const bottomScript = readFileSync(new URL('../android/gecko-demo/src/main/assets/bottom-bar/content.js', import.meta.url), 'utf8');

/** 用真实脚本和可控布局/时间测试检测逻辑，不把此结果当成 Gecko 真机验收。 */
function bottomHarness() {
  let now = 0, focused = true, hit = null, observerCallback;
  const tasks = new Map(), listeners = new Map(), reports = [], ports = [];
  let nextTimer = 0;
  const style = { position: 'static', bottom: 'auto', opacity: '1', backgroundColor: 'rgba(0, 0, 0, 0)',
    backgroundImage: 'none', filter: 'none', backdropFilter: 'none', mixBlendMode: 'normal', display: 'block', visibility: 'visible' };
  const elements = [];
  const target = name => ({ addEventListener: (event, callback) => {
    const key = `${name}:${event}`; listeners.set(key, [...(listeners.get(key) || []), callback]);
  } });
  const viewport = { ...target('viewport'), offsetLeft: 0, offsetTop: 0, width: 400, height: 800 };
  const window = { ...target('window'), innerWidth: 400, innerHeight: 800, visualViewport: viewport };
  window.top = window;
  const document = { ...target('document'), visibilityState: 'visible', fullscreenElement: null,
    hasFocus: () => focused, querySelectorAll: () => elements,
    elementFromPoint: (x, y) => hit === undefined ? null : hit || [...elements].reverse().find(node => {
      const r = node.getBoundingClientRect(); return node.style.visibility === 'visible' && node.style.display !== 'none' && x >= r.left && x < r.right && y >= r.top && y < r.bottom;
    }) };
  const browser = { runtime: { connectNative: name => {
    assert.equal(name, 'rocksBottomBackground');
    const port = { postMessage: report => reports.push(JSON.parse(JSON.stringify(report))),
      onDisconnect: { addListener: callback => { port.disconnected = callback; } }, disconnect: () => port.disconnected?.() };
    ports.push(port); return port;
  } } };
  vm.runInNewContext(bottomScript, { window, document, location: { protocol: 'https:' }, browser,
    crypto: { randomUUID: () => 'test-document-1' }, getComputedStyle: node => node.style,
    MutationObserver: class { constructor(callback) { observerCallback = callback; } observe() {} disconnect() {} },
    performance: { now: () => now }, setTimeout: (callback, delay) => { const id = ++nextTimer; tasks.set(id, { callback, due: now + delay }); return id; },
    clearTimeout: id => tasks.delete(id) });
  return { reports, ports, viewport, document, tasks,
    add(overrides = {}, rect = { left: 0, top: 680, right: 400, bottom: 720, height: 40 }, parent = null) {
      const node = { style: { ...style, ...overrides }, isConnected: true, parentElement: parent, getBoundingClientRect: () => rect,
        contains: child => { for (let n = child; n; n = n.parentElement) if (n === node) return true; return false; } };
      elements.push(node); observerCallback(); return node;
    },
    event(name, event) { for (const callback of listeners.get(`${name}:${event}`) || []) callback(); },
    mutate() { observerCallback(); },
    focus(value) { focused = value; this.event('window', value ? 'focus' : 'blur'); },
    occlude(value) { hit = value; },
    advance(milliseconds = 100) {
      const end = now + milliseconds;
      for (;;) {
        const entry = [...tasks].sort((a, b) => a[1].due - b[1].due)[0];
        if (!entry || entry[1].due > end) break;
        tasks.delete(entry[0]); now = entry[1].due; entry[1].callback();
      }
      now = end;
    }
  };
}

test('底栏脚本上报白色与深色纯色，动态删除后恢复无候选', () => {
  const h = bottomHarness();
  const bar = h.add({ position: 'fixed', backgroundColor: 'rgb(255, 255, 255)' });
  h.advance(); assert.deepEqual(h.reports.at(-1).bars[0].color, [255, 255, 255]);
  bar.style.backgroundColor = 'rgb(20, 30, 40)'; h.mutate(); h.advance();
  assert.deepEqual(h.reports.at(-1).bars[0].color, [20, 30, 40]);
  bar.isConnected = false; h.mutate(); h.advance(); assert.deepEqual(h.reports.at(-1).bars, []);
});

test('普通色块、悬浮小按钮、隐藏与遮挡元素不会成为背景来源', () => {
  const h = bottomHarness();
  h.add({ backgroundColor: 'rgb(255, 255, 255)' });
  h.add({ position: 'fixed', backgroundColor: 'rgb(1, 2, 3)' }, { left: 340, top: 680, right: 400, bottom: 720, height: 40 });
  h.advance(); assert.deepEqual(h.reports.at(-1).bars, []);
  const bar = h.add({ position: 'fixed', visibility: 'hidden', backgroundColor: 'rgb(255, 255, 255)' });
  h.advance(); assert.deepEqual(h.reports.at(-1).bars, []);
  bar.style.visibility = 'visible'; h.occlude({}); h.mutate(); h.advance();
  assert.deepEqual(h.reports.at(-1).bars, []);
});

test('透明背景向祖先取色，半透明合成，图片与祖先透明度保守回退', () => {
  const h = bottomHarness();
  const parent = h.add({ backgroundColor: 'rgb(10, 20, 30)' });
  const bar = h.add({ position: 'fixed' }, undefined, parent);
  h.advance(); assert.deepEqual(h.reports.at(-1).bars[0].color, [10, 20, 30]);
  bar.style.backgroundColor = 'rgba(110, 120, 130, 0.5)'; h.mutate(); h.advance();
  assert.deepEqual(h.reports.at(-1).bars[0].color, [60, 70, 80]);
  bar.style.backgroundImage = 'url(test)'; h.mutate(); h.advance(); assert.deepEqual(h.reports.at(-1).bars, []);
  bar.style.backgroundImage = 'none'; parent.style.opacity = '0.5'; h.mutate(); h.advance(); assert.deepEqual(h.reports.at(-1).bars, []);
});

test('吸附候选必须设置 bottom，百分之八十宽度边界可识别', () => {
  const h = bottomHarness();
  const bar = h.add({ position: 'sticky', backgroundColor: 'rgb(1, 2, 3)' }, { left: 40, top: 680, right: 360, bottom: 720, height: 40 });
  h.advance(); assert.deepEqual(h.reports.at(-1).bars, []);
  bar.style.bottom = '0px'; h.mutate(); h.advance(); assert.equal(h.reports.at(-1).bars.length, 1);
});

test('事件合并限频、相同结果不重发，空闲无轮询，后台停止且恢复重新上报', () => {
  const h = bottomHarness(); h.add({ position: 'fixed', backgroundColor: 'rgb(1, 2, 3)' }); h.advance();
  for (let i = 0; i < 100; i++) h.event('window', 'scroll');
  h.advance(); assert.equal(h.reports.length, 1); assert.equal(h.tasks.size, 0);
  h.focus(false); h.mutate(); h.event('window', 'scroll'); h.advance(1000); assert.equal(h.reports.length, 1);
  h.focus(true); h.advance(); assert.equal(h.reports.length, 2);
  h.document.fullscreenElement = {}; h.event('document', 'fullscreenchange'); h.advance(); assert.equal(h.reports.length, 2);
  h.document.fullscreenElement = null; h.event('document', 'fullscreenchange'); h.advance(); assert.equal(h.reports.length, 3);
});

test('连接失败仅有界重试，离开文档清理端口与所有任务', () => {
  const h = bottomHarness(); h.advance();
  for (let i = 0; i < 4; i++) { h.ports.at(-1).disconnected(); h.advance(1000); }
  assert.equal(h.ports.length, 5);
  h.ports.at(-1).disconnected(); h.advance(10000); assert.equal(h.ports.length, 5);
  h.event('window', 'focus'); h.advance(); assert.equal(h.ports.length, 6);
  h.event('window', 'scroll'); h.event('window', 'pagehide'); assert.equal(h.tasks.size, 0);
});

test('内置扩展仅匹配顶层 HTTP/HTTPS，没有存储、网络或外部消息入口', () => {
  const manifest = JSON.parse(readFileSync(new URL('../android/gecko-demo/src/main/assets/bottom-bar/manifest.json', import.meta.url), 'utf8'));
  assert.equal(manifest.content_scripts[0].all_frames, false);
  assert.deepEqual(manifest.content_scripts[0].matches, ['http://*/*', 'https://*/*']);
  assert.deepEqual(manifest.permissions, ['geckoViewAddons', 'nativeMessaging', 'nativeMessagingFromContent']);
  assert.equal(manifest.externally_connectable, undefined);
});
