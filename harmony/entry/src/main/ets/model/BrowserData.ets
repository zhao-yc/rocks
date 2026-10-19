export class BrowserTab {
  id: string = 'initial-home';
  url: string = '';
  title: string = '新标签页';
}

export class PageRecord {
  url: string = '';
  title: string = '';
  timestamp: number = 0;
}

export class BrowserSettings {
  searchEngine: string = 'bing';
}

/** 与 shared/browser-data.schema.json 对齐；平台引擎的 cookie 不进入该结构。 */
export class BrowserData {
  schemaVersion: number = 1;
  tabs: BrowserTab[] = [new BrowserTab()];
  activeTabId: string = 'initial-home';
  bookmarks: PageRecord[] = [];
  history: PageRecord[] = [];
  settings: BrowserSettings = new BrowserSettings();
}

/** 鸿蒙首步仅接受严格 ASCII HTTP(S) 网址；国际域名支持需完成双端一致性测试。 */
export function isWebUrl(value: string): boolean {
  return value.length <= 8192 && /^https?:\/\/[a-zA-Z0-9.-]+(?::[0-9]{1,5})?(?:[/?#][^\s\\]*)?$/.test(value) &&
    !value.substring(0, value.indexOf('/', 8) > 0 ? value.indexOf('/', 8) : value.length).includes('@');
}

export function resolveAddress(input: string, engine: string): string {
  const value = input.trim();
  if (value.length === 0 || value.length > 8192 || /[\r\n\t]/.test(value)) return '';
  if (/^https?:\/\//.test(value)) return isWebUrl(value) ? value : '';
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(value)) return '';
  if (!/\s/.test(value) && value.split('/')[0].includes('.')) {
    return isWebUrl('https://' + value) ? 'https://' + value : '';
  }
  let prefix = 'https://cn.bing.com/search?q=';
  if (engine === 'baidu') prefix = 'https://www.baidu.com/s?wd=';
  if (engine === 'google') prefix = 'https://www.google.com/search?q=';
  if (engine === 'duckduckgo') prefix = 'https://duckduckgo.com/?q=';
  return prefix + encodeURIComponent(value);
}
