(() => {
  const chinese = navigator.language.toLowerCase().startsWith('zh');
  const labels = chinese ? {
    intro: '这是真正在网页中运行的扩展。切换后关闭面板，看看页面的变化。',
    paperLabel: '暖纸阅读背景', spaceLabel: '增加文字间距', reset: '恢复网页原样',
    saved: '已保存，网页效果即时更新。', error: '未能保存，请重试。'
  } : {
    intro: 'A real extension running on websites. Change a setting, then close this panel to see the result.',
    paperLabel: 'Warm paper background', spaceLabel: 'More text spacing', reset: 'Restore original styles',
    saved: 'Saved. Page styles update immediately.', error: 'Could not save. Please retry.'
  };
  for (const id of ['intro', 'paperLabel', 'spaceLabel', 'reset']) document.getElementById(id).textContent = labels[id];
  const enabled = document.getElementById('enabled');
  const spacious = document.getElementById('spacious');
  const status = document.getElementById('status');
  const load = async () => {
    const settings = await browser.storage.local.get({ enabled: true, spacious: false });
    enabled.checked = settings.enabled; spacious.checked = settings.spacious;
  };
  const save = async () => {
    try {
      await browser.storage.local.set({ enabled: enabled.checked, spacious: spacious.checked });
      status.textContent = labels.saved;
    } catch (_) { status.textContent = labels.error; }
  };
  enabled.addEventListener('change', save);
  spacious.addEventListener('change', save);
  document.getElementById('reset').addEventListener('click', async () => {
    enabled.checked = false; spacious.checked = false; await save();
  });
  load().catch(() => { status.textContent = labels.error; });
})();
