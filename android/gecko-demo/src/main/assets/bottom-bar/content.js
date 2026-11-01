/* 内置隔离脚本：不接收网页消息、不修改 DOM，只读取布局和背景颜色。 */
(() => {
  if (window !== window.top || !/^https?:$/.test(location.protocol)) return;
  // 普通 HTTP 页面没有 randomUUID；getRandomValues 在非安全上下文仍可用。
  const documentId = typeof crypto.randomUUID === 'function' ? crypto.randomUUID() :
    Array.from(crypto.getRandomValues(new Uint8Array(16)), value => value.toString(16).padStart(2, '0')).join('');
  let port = null, timer = null, lastRun = -Infinity, lastResult = null;
  let retryTimer = null, retries = 0;
  let dirty = true, candidates = [], observing = false;
  let selected = false;
  let activityRevision = 0;

  /** 解析浏览器计算后的 sRGB 颜色；不支持的颜色空间保守回退为透明。 */
  function rgba(value) {
    const match = /^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:\s*[,/]\s*([\d.]+))?\s*\)$/.exec(value);
    if (!match) return null;
    const result = [+match[1], +match[2], +match[3], match[4] === undefined ? 1 : +match[4]];
    return result.every(Number.isFinite) && result.slice(0, 3).every(n => n >= 0 && n <= 255) && result[3] >= 0 && result[3] <= 1 ? result : null;
  }

  /** 沿祖先合成半透明背景；图片、滤镜或无法解析的底色不猜测。 */
  function background(element) {
    let color = [0, 0, 0], alpha = 0;
    for (let node = element; node; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (+style.opacity !== 1 || (alpha < 0.999 && style.backgroundImage !== 'none') || style.filter !== 'none' ||
          (style.backdropFilter && style.backdropFilter !== 'none') || style.mixBlendMode !== 'normal') return null;
      if (alpha < 0.999) {
        const next = rgba(style.backgroundColor);
        if (!next) return null;
        const weight = (1 - alpha) * next[3];
        color = color.map((value, i) => value + weight * next[i]);
        alpha += weight;
      }
      // 即使本层已不透明，也要检查祖先的整体透明度、滤镜与可见性。
      if (style.display === 'none' || style.visibility !== 'visible') return null;
    }
    return alpha >= 0.999 ? color.map(n => Math.round(n)) : null;
  }

  /** 通过多个命中点排除被弹层遮住的栏，允许栏内按钮和文字成为命中目标。 */
  function visibleBar(element, rect, left, width, top, bottom) {
    const y = Math.min(rect.bottom - 1, bottom - 1);
    if (y < Math.max(rect.top, top)) return false;
    return [0.1, 0.3, 0.5, 0.7, 0.9].every(part => {
      const start = Math.max(left, rect.left), end = Math.min(left + width, rect.right);
      const hit = document.elementFromPoint(start + (end - start) * part, y);
      return hit && (hit === element || element.contains(hit));
    });
  }

  /** 缓存固定候选；滚动只复核候选几何，不反复扫描整棵 DOM。 */
  function detect() {
    const viewport = window.visualViewport;
    const left = viewport?.offsetLeft || 0, top = viewport?.offsetTop || 0;
    const width = viewport?.width || window.innerWidth, height = viewport?.height || window.innerHeight;
    if (!(width > 0 && height > 0)) return null;
    if (dirty) {
      candidates = Array.from(document.querySelectorAll('*')).filter(node => {
        const position = getComputedStyle(node).position;
        return position === 'fixed' || position === 'sticky';
      });
      dirty = false;
    }
    const bars = [];
    for (const element of candidates) {
      if (!element.isConnected) continue;
      const style = getComputedStyle(element), rect = element.getBoundingClientRect();
      if (!['fixed', 'sticky'].includes(style.position) || style.display === 'none' || style.visibility !== 'visible' ||
          (style.position === 'sticky' && style.bottom === 'auto') || rect.height <= 0 || rect.top >= top + height ||
          Math.min(rect.right, left + width) - Math.max(rect.left, left) < width * 0.8) continue;
      if (!visibleBar(element, rect, left, width, top, top + height)) continue;
      const color = background(element);
      if (color) bars.push({ left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom, color, position: style.position });
      if (bars.length === 32) break;
    }
    return { documentId, viewport: { left, top, width, height }, bars };
  }

  /** 每个文档建立独立端口；只对启动/提交竞态作四次有界重试，离开文档立即取消。 */
  function connect() {
    if (port || !active()) return;
    try {
      const connected = browser.runtime.connectNative('rocksBottomBackground');
      port = connected;
      connected.onDisconnect.addListener(() => {
        if (port !== connected) return;
        port = null; lastResult = null;
        if (active() && retryTimer === null && retries < 4) {
          retryTimer = setTimeout(() => { retryTimer = null; schedule(); }, 100 * 2 ** retries++);
        }
      });
    } catch { port = null; }
  }

  function active() { return selected && document.visibilityState === 'visible' && !document.fullscreenElement; }

  /** Gecko 的网页焦点和原生焦点不同步；使用扩展内部的实际标签激活状态。 */
  function refreshActivity() {
    const expected = activityRevision;
    browser.runtime.sendMessage({ kind: 'bottom-activity' }).then(result => {
      if (expected !== activityRevision) return;
      selected = result?.active === true; activityChanged();
    }).catch(() => { if (expected === activityRevision) { selected = false; activityChanged(); } });
  }

  /** 合并事件，最多每秒检测十次；仅发送变化，不进行空闲轮询。 */
  function schedule() {
    if (!active() || timer !== null) return;
    timer = setTimeout(() => {
      timer = null;
      if (!active()) return;
      lastRun = performance.now();
      connect();
      if (!port) return;
      const result = detect();
      if (!result) return;
      const serialized = JSON.stringify(result);
      if (serialized === lastResult) return;
      try { port.postMessage(result); lastResult = serialized; } catch { port = null; lastResult = null; }
    }, Math.max(0, 100 - (performance.now() - lastRun)));
  }

  const observer = new MutationObserver(() => { dirty = true; schedule(); });
  /** 后台和离开文档时取消待检测任务；重新激活后必须重新上报。 */
  function activityChanged() {
    lastResult = null; dirty = true;
    if (active()) {
      retries = 0;
      if (!observing) { observer.observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'style', 'hidden', 'open'] }); observing = true; }
      // 留出一次原生焦点/全屏状态提交的时间，避免生命周期回调先后顺序导致误拒绝。
      lastRun = performance.now(); schedule();
    } else {
      observer.disconnect(); observing = false;
      if (timer !== null) { clearTimeout(timer); timer = null; }
      if (retryTimer !== null) { clearTimeout(retryTimer); retryTimer = null; }
    }
  }
  window.addEventListener('scroll', schedule, { passive: true, capture: true });
  for (const event of ['resize', 'blur', 'popstate', 'hashchange']) window.addEventListener(event, activityChanged);
  for (const event of ['focus', 'pageshow']) window.addEventListener(event, refreshActivity);
  for (const event of ['visibilitychange', 'fullscreenchange']) document.addEventListener(event, activityChanged);
  for (const event of ['load', 'transitionend', 'animationend']) document.addEventListener(event, () => { dirty = true; schedule(); }, true);
  window.visualViewport?.addEventListener('resize', activityChanged);
  window.visualViewport?.addEventListener('scroll', schedule);
  window.addEventListener('pagehide', () => {
    observer.disconnect(); observing = false;
    if (timer !== null) { clearTimeout(timer); timer = null; }
    if (retryTimer !== null) { clearTimeout(retryTimer); retryTimer = null; }
    const old = port; port = null; old?.disconnect(); lastResult = null;
  });
  browser.runtime.onMessage.addListener((message, sender) => {
    if (sender.id === browser.runtime.id && message?.kind === 'bottom-activity') {
      activityRevision++;
      selected = message.active === true; activityChanged();
    }
  });
  refreshActivity();
})();
