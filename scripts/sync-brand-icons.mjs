import { readFile, mkdir, writeFile } from 'node:fs/promises';

// SVG 是唯一设计源；两套 Android 工程与鸿蒙复用相同轮廓和颜色。
const root = new URL('../', import.meta.url);
const source = (await readFile(new URL('docs/brand/rocks.svg', root), 'utf8')).replace(/\r\n/g, '\n');
const background = source.match(/<rect[^>]+fill="(#[A-Fa-f0-9]{6})"/)[1];
const paths = [...source.matchAll(/<path fill="([^"]+)"(?: fill-rule="([^"]+)")? d="([^"]+)"\/>/g)];
if (paths.length !== 3) throw new Error('品牌 SVG 应包含主轮廓与两个切面，请检查同步脚本。');
// 坐标变换与圆角参与 Android 转换；布局改变时必须显式更新适配，防止静默错位。
if (!source.includes('viewBox="0 0 72 72"') ||
    !source.includes('<rect width="72" height="72" rx="18"') ||
    !source.includes('transform="translate(-11.52 -11.52) scale(0.88)"')) {
  throw new Error('品牌 SVG 的画布、圆角或变换已改变，请同步更新 Android 转换逻辑。');
}
const check = process.argv.includes('--check');
let changed = false;

// 比对模式供 CI 使用，避免只改一端；普通模式只更新内容发生变化的资源。
async function output(path, content) {
  const target = new URL(path, root);
  const existing = await readFile(target, 'utf8').catch(error => {
    if (error.code === 'ENOENT') return null;
    throw error;
  });
  if (existing?.replace(/\r\n/g, '\n') === content) return;
  changed = true;
  if (check) {
    console.error(`图标资源未同步：${path}`);
    return;
  }
  await mkdir(new URL('./', target), { recursive: true });
  await writeFile(target, content);
  console.log(`已同步：${path}`);
}

// 自适应层使用 108dp 画布；系统裁切后的中心 72dp 与 SVG 完全对应。
function vector(adaptive) {
  const size = adaptive ? 108 : 72;
  const translation = adaptive ? 6.48 : -11.52;
  const shapes = paths.map(([, fill, rule, data]) =>
    `        <path android:fillColor="${fill}"${rule ? ' android:fillType="evenOdd"' : ''} android:pathData="${data}" />`).join('\n');
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/sync-brand-icons.mjs 同步，设计源为 docs/brand/rocks.svg。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${size}dp" android:height="${size}dp"
    android:viewportWidth="${size}" android:viewportHeight="${size}">
${adaptive ? '' : `    <path android:fillColor="${background}" android:pathData="M18 0H54A18 18 0 0 1 72 18V54A18 18 0 0 1 54 72H18A18 18 0 0 1 0 54V18A18 18 0 0 1 18 0Z" />\n`}    <group android:translateX="${translation}" android:translateY="${translation}" android:scaleX="0.88" android:scaleY="0.88">
${shapes}
    </group>
</vector>
`;
}

// 主题图标复用前景的透明度轮廓，系统会将所有不透明切面统一着色。
function adaptiveIcon(themed) {
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/sync-brand-icons.mjs 同步；背景延展至整个系统遮罩。 -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/rocks_icon_background" />
    <foreground android:drawable="@drawable/ic_rocks_foreground" />
${themed ? '    <monochrome android:drawable="@drawable/ic_rocks_foreground" />\n' : ''}</adaptive-icon>
`;
}

for (const project of ['android/gecko-demo', 'android/app']) {
  const resources = `${project}/src/main/res`;
  await output(`${resources}/drawable/ic_rocks.xml`, vector(false));
  await output(`${resources}/drawable/ic_rocks_foreground.xml`, vector(true));
  await output(`${resources}/values/brand_colors.xml`, `<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/sync-brand-icons.mjs 同步。 -->
<resources>
    <color name="rocks_icon_background">${background}</color>
</resources>
`);
  await output(`${resources}/mipmap-anydpi-v26/ic_rocks.xml`, adaptiveIcon(false));
  await output(`${resources}/mipmap-anydpi-v33/ic_rocks.xml`, adaptiveIcon(true));
}
await output('harmony/AppScope/resources/base/media/app_icon.svg', source);
if (check && changed) process.exitCode = 1;
else console.log(check ? '品牌图标已全部同步。' : '品牌图标同步完成。');
