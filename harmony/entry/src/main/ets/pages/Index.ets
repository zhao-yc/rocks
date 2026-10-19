import { webview } from '@kit.ArkWeb';
import { common } from '@kit.AbilityKit';
import { BrowserData, BrowserTab, isWebUrl, resolveAddress } from '../model/BrowserData';
import { SessionRepository } from '../storage/SessionRepository';

/** 原生鸿蒙首步页面；本机无 SDK，此实现尚未编译或运行验证。 */
@Entry
@Component
struct Index {
  @State session: BrowserData = new BrowserData();
  @State address: string = '';
  @State ready: boolean = false;
  @State message: string = '';
  private controller: webview.WebviewController = new webview.WebviewController();
  private repository: SessionRepository = new SessionRepository();

  aboutToAppear(): void {
    this.repository.load(getContext(this) as common.UIAbilityContext).then(data => {
      this.session = data;
      this.address = this.active().url;
      this.ready = true;
    }).catch(() => { this.message = '恢复失败，原始会话已保留，本次不保存更改。'; this.ready = true; });
  }

  private active(): BrowserTab {
    return this.session.tabs.find(tab => tab.id === this.session.activeTabId) || this.session.tabs[0];
  }

  private updateUrl(value: string): void {
    if (!isWebUrl(value)) return;
    const next = new BrowserData();
    next.activeTabId = this.session.activeTabId;
    next.tabs = this.session.tabs.map(tab => {
      const updated = new BrowserTab();
      updated.id = tab.id; updated.url = tab.id === next.activeTabId ? value : tab.url; updated.title = tab.title;
      return updated;
    });
    next.bookmarks = this.session.bookmarks; next.history = this.session.history; next.settings = this.session.settings;
    this.session = next; this.address = value;
    this.repository.save(next).catch(() => { this.message = '会话未保存，请检查存储状态。'; });
  }

  private navigate(): void {
    const target = resolveAddress(this.address, this.session.settings.searchEngine);
    if (target === '') { this.message = '请输入有效的 HTTP(S) 网址或搜索词。'; return; }
    const attached = this.active().url !== '';
    this.updateUrl(target);
    if (attached) this.controller.loadUrl(target);
  }

  build() {
    Column() {
      if (!this.ready) {
        LoadingProgress().layoutWeight(1)
      } else if (this.active().url === '') {
        Column() {
          Text('Rocks').fontSize(42).fontColor('#35614B')
          Text($r('app.string.home_tagline')).fontSize(18).margin({ top: 16 })
        }.layoutWeight(1).width('100%').justifyContent(FlexAlign.Center)
      } else {
        Web({ src: this.active().url, controller: this.controller })
          .layoutWeight(1).width('100%')
          .javaScriptAccess(true).domStorageAccess(true).fileAccess(false)
          .mixedMode(MixedMode.None)
          .onLoadIntercept(event => event ? !isWebUrl(event.data.getRequestUrl()) : true)
          .onPageBegin(event => { if (event) this.updateUrl(event.url); })
          .onSslErrorEventReceive(event => { event?.handler.cancel(); this.message = '证书错误，连接已阻止。'; })
          .onPermissionRequest(event => { event?.request.deny(); this.message = '原型暂不开放网站设备权限。'; })
      }
      if (this.message !== '') { Text(this.message).fontSize(13).padding(12) }
      TextInput({ text: this.address, placeholder: $r('app.string.search_hint') })
        .enabled(this.ready).onChange(value => { this.address = value; }).onSubmit(() => { this.navigate(); })
        .enterKeyType(EnterKeyType.Go).margin({ left: 12, right: 12 })
      Row() {
        Button('后退').onClick(() => { if (this.active().url !== '' && this.controller.accessBackward()) this.controller.backward(); })
        Button('前进').onClick(() => { if (this.active().url !== '' && this.controller.accessForward()) this.controller.forward(); })
        Button('刷新').onClick(() => { if (this.active().url !== '') this.controller.refresh(); })
        Button('访问').onClick(() => { this.navigate(); })
      }.justifyContent(FlexAlign.SpaceAround).width('100%').padding(12)
    }.width('100%').height('100%').backgroundColor('#F8FAF7')
  }
}
