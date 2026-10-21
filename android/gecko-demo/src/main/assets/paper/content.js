/* First-party WebExtension: style changes and extension-local storage only. */
(() => {
  let sheet;
  const apply = (settings) => {
    if (!sheet) {
      sheet = document.createElement('style');
      sheet.id = 'rocks-paper-reading-style';
      (document.head || document.documentElement).appendChild(sheet);
    }
    sheet.textContent = settings.enabled === false ? '' : `
      html, body { background: #f4efdf !important; color: #283b30 !important; }
      main, article { background: #f4efdf !important; color: #283b30 !important; }
      p, li, blockquote { line-height: ${settings.spacious ? '2.1' : '1.7'} !important;
        letter-spacing: ${settings.spacious ? '.045em' : 'normal'} !important; }
      a { color: #35614b !important; }
      ::selection { background: #b8d4b3 !important; color: #173c2b !important; }
    `;
  };
  browser.storage.local.get({ enabled: true, spacious: false }).then(apply);
  browser.storage.onChanged.addListener((_changes, area) => {
    if (area === 'local') browser.storage.local.get({ enabled: true, spacious: false }).then(apply);
  });
})();
