// 用相邻 rAF 时间戳估算网页回调节奏；不假定 60Hz，也不代表实际显示合成帧。
(function (root) {
  'use strict';
  class FrameMeter {
    constructor() { this.reset(); }
    reset(timestamp = null) { this.previous = timestamp; this.intervals = []; this.elapsed = 0; }
    push(timestamp) {
      if (!Number.isFinite(timestamp)) return null;
      if (this.previous === null) { this.previous = timestamp; return null; }
      const delta = timestamp - this.previous;
      if (delta <= 0) return null;
      this.previous = timestamp;
      // 后台恢复或长时间暂停后重新采样，不把停顿伪算为低刷新率。
      if (delta > 1000) { this.reset(timestamp); return null; }
      this.intervals.push(delta);
      this.elapsed += delta;
      if (this.elapsed < 1000) return null;
      const sorted = [...this.intervals].sort((a, b) => a - b);
      const result = { fps: this.intervals.length * 1000 / this.elapsed,
        p95: sorted[Math.ceil(sorted.length * 0.95) - 1], samples: this.intervals.length };
      this.reset(timestamp);
      return result;
    }
  }
  if (typeof module !== 'undefined') module.exports = FrameMeter;
  root.RocksFrameMeter = FrameMeter;
})(globalThis);
