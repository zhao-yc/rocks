/* 只在扩展内部转发浏览器的标签激活状态，不读取网址、正文或调用原生通道。 */
let activeId = null;

/** 通知旧标签停止检测、新标签重新检测；页面尚未加载脚本时允许没有接收者。 */
function activate(tabId) {
  const previous = activeId;
  activeId = tabId;
  if (previous !== null && previous !== tabId) browser.tabs.sendMessage(previous, { kind: 'bottom-activity', active: false }).catch(() => {});
  browser.tabs.sendMessage(tabId, { kind: 'bottom-activity', active: true }).catch(() => {});
}

browser.tabs.onActivated.addListener(({ tabId }) => activate(tabId));
browser.runtime.onMessage.addListener((message, sender) => {
  if (sender.id !== browser.runtime.id || sender.tab?.id === undefined || message?.kind !== 'bottom-activity') return undefined;
  return browser.tabs.get(sender.tab.id).then(tab => {
    if (tab.active) activate(tab.id);
    return { active: tab.active === true };
  });
});
