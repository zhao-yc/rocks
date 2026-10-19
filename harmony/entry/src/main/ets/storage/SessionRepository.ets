import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { BrowserData, isWebUrl } from '../model/BrowserData';

/** 本地 Preferences 保存同一 JSON 载荷；未知格式拒绝保存，避免覆盖原始数据。 */
export class SessionRepository {
  private store: preferences.Preferences | undefined = undefined;
  private writable: boolean = false;
  private pending: Promise<void> = Promise.resolve();

  async load(context: common.UIAbilityContext): Promise<BrowserData> {
    this.store = await preferences.getPreferences(context, { name: 'rocks-browser' });
    const value = await this.store.get('session', '') as string;
    if (value === '') { this.writable = true; return new BrowserData(); }
    if (value.length > 4 * 1024 * 1024) throw new Error('会话过大');
    const data = JSON.parse(value) as BrowserData;
    if (data.schemaVersion !== 1 || !Array.isArray(data.tabs) || data.tabs.length === 0 || data.tabs.length > 24) {
      throw new Error('不支持的会话格式');
    }
    const ids: string[] = [];
    data.tabs.forEach(tab => {
      if (!tab.id || ids.includes(tab.id) || (tab.url !== '' && !isWebUrl(tab.url))) throw new Error('无效标签');
      ids.push(tab.id);
    });
    if (!ids.includes(data.activeTabId)) throw new Error('无效活动标签');
    this.writable = true;
    return data;
  }

  save(data: BrowserData): Promise<void> {
    if (!this.writable || this.store === undefined) return Promise.reject(new Error('会话不可写'));
    const payload = JSON.stringify(data);
    this.pending = this.pending.catch(() => {}).then(async () => {
      await this.store?.put('session', payload);
      await this.store?.flush();
    });
    return this.pending;
  }
}
