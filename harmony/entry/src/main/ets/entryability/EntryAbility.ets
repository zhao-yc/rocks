import { UIAbility } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';

/** Stage 模型入口：真实加载 ArkUI 页面，不复用 Android WebView 壳。 */
export default class EntryAbility extends UIAbility {
  onWindowStageCreate(stage: window.WindowStage): void {
    stage.loadContent('pages/Index');
  }
}
