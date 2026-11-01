// 只绑定回环地址的原生浏览回归夹具，不接触外部服务、账号或本机文件。
import http from 'node:http';
import { deflateSync } from 'node:zlib';

const iconMode = process.argv.includes('--icons');
const iconStats = { requests: 0, active: 0, maximumActive: 0, sensitive: false, paths: {} };

/** 生成不含外部素材的真实 PNG 与嵌入 PNG 的 ICO，交给 Gecko 的真实解码器验证。 */
function pngChunk(type, data) {
  const source = Buffer.concat([Buffer.from(type), data]);
  let checksum = 0xffffffff;
  for (const byte of source) {
    checksum ^= byte;
    for (let bit = 0; bit < 8; bit++) checksum = (checksum >>> 1) ^ ((checksum & 1) ? 0xedb88320 : 0);
  }
  const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4); crc.writeUInt32BE((checksum ^ 0xffffffff) >>> 0);
  return Buffer.concat([length, source, crc]);
}
const header = Buffer.alloc(13); header.writeUInt32BE(64, 0); header.writeUInt32BE(64, 4); header[8] = 8; header[9] = 6;
const pixels = Buffer.alloc((64 * 4 + 1) * 64);
for (let row = 0; row < 64; row++) for (let col = 0; col < 64; col++) {
  const offset = row * 257 + 1 + col * 4;
  pixels.set([53, 97, 75, 255], offset);
}
const png = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), pngChunk('IHDR', header),
  pngChunk('IDAT', deflateSync(pixels)), pngChunk('IEND', Buffer.alloc(0))]);
const icoHeader = Buffer.alloc(22); icoHeader.writeUInt16LE(1, 2); icoHeader.writeUInt16LE(1, 4);
icoHeader[6] = 64; icoHeader[7] = 64; icoHeader.writeUInt16LE(1, 10); icoHeader.writeUInt16LE(32, 12);
icoHeader.writeUInt32LE(png.length, 14); icoHeader.writeUInt32LE(22, 18);
const ico = Buffer.concat([icoHeader, png]);

/** 统计只覆盖合成图标资源，不记录真实网址、页面内容或请求头的值。 */
function iconRoute(request, response, negative = false) {
  const route = request.url?.split('?')[0] ?? '';
  if (route === '/icon-stats') {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(iconStats)); return true;
  }
  if (!route.startsWith('/icon-case/') && !route.startsWith('/icons/') && route !== '/favicon.ico') return false;
  const key = `${negative ? 'negative:' : ''}${route}`;
  iconStats.requests++; iconStats.paths[key] = (iconStats.paths[key] ?? 0) + 1;
  iconStats.sensitive ||= Boolean(request.headers.cookie || request.headers.authorization || request.headers.referer);
  iconStats.active++; iconStats.maximumActive = Math.max(iconStats.maximumActive, iconStats.active);
  let finished = false;
  const finish = () => { if (!finished) { finished = true; iconStats.active--; } };
  response.once('finish', finish); response.once('close', finish);
  response.setHeader('Cache-Control', 'no-store');
  if (route.startsWith('/icon-case/')) {
    const name = route.split('/')[2];
    const target = ({ png: '/icons/green.png', ico: '/icons/green.ico', relative: '../../icons/green.png',
      redirect: '/icons/redirect', loop: '/icons/loop', timeout: '/icons/slow', oversized: '/icons/oversized',
      broken: '/icons/broken', failure: '/icons/broken', parallel: '/icons/wait' })[name];
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    response.end(`<html><head><title>图标夹具</title>${target ? `<link rel="icon" href="${target}">` : ''}</head><body>图标夹具</body></html>`);
  } else if (negative) { response.writeHead(404); response.end(); }
  else if (route === '/icons/redirect' || route === '/icons/loop') {
    response.writeHead(302, { Location: route === '/icons/loop' ? '/icons/loop' : '/icons/green.png' }); response.end();
  } else if (route === '/icons/slow' || route === '/icons/wait') {
    const timer = setTimeout(() => response.end(png), route === '/icons/wait' ? 200 : 8000);
    response.on('close', () => clearTimeout(timer));
  } else if (route === '/icons/oversized') { response.end(Buffer.alloc(256 * 1024 + 1)); }
  else if (route === '/icons/broken') { response.end('无法解码的测试图标'); }
  else if (route === '/icons/green.png' || route === '/favicon.ico') {
    response.writeHead(200, { 'Content-Type': 'image/png' }); response.end(png);
  } else if (route === '/icons/green.ico') {
    response.writeHead(200, { 'Content-Type': 'image/x-icon' }); response.end(ico);
  } else { response.writeHead(404); response.end(); }
  return true;
}

/** 通过真实 HTTP 响应覆盖正常页面、长请求和空响应失败，全程不注入网页脚本。 */
const server = http.createServer((request, response) => {
  if (iconMode && iconRoute(request, response)) return;
  const route = request.url?.split('?')[0];
  if (route === '/fail') { response.destroy(); return; }
  if (route === '/delayed') {
    const timer = setTimeout(() => response.end('完成'), 45000);
    response.on('close', () => clearTimeout(timer));
    return;
  }
  if (!['/one', '/two', '/slow'].includes(route)) { response.writeHead(404); response.end(); return; }
  const name = route === '/two' ? 'Two' : route === '/slow' ? 'Slow' : 'One';
  response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store',
    'Set-Cookie': 'rocks_icon_fixture=present; Path=/; SameSite=Lax' });
  response.write(`<!doctype html><html lang="zh-CN"><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>日常体验 ${name}</title><style>body{margin:0;font:20px sans-serif;color:#173c2b;background:#fff}header{background:#d8e9f9;padding:24px}main{padding:24px}p{line-height:1.8;margin-bottom:100px}input{font-size:20px;width:90%}a{display:inline-block;margin:16px 24px 16px 0}footer{position:fixed;bottom:0;background:#edf4df;width:100%;padding:12px}.band{height:180px;background:#659ae6}</style></head><body><header>日常体验 ${name}</header><main><a href="/one">网页 One</a><a href="/two">网页 Two</a><input placeholder="网页表单"><div class="band"></div>${'<p>用于检查滚动、输入、标签切换与底部控件。浏览内容仅来自本机测试夹具。</p>'.repeat(16)}</main><footer>网页固定底栏</footer>${name === 'Slow' ? '<img src="/delayed" alt="慢速请求">' : ''}`);
  if (name === 'Slow') {
    const timer = setTimeout(() => response.end('</body></html>'), 45000);
    response.on('close', () => clearTimeout(timer));
  } else response.end('</body></html>');
});

server.listen(8878, '127.0.0.1', () => console.log('日常浏览夹具已启动：http://127.0.0.1:8878/one'));
const negative = iconMode ? http.createServer((request, response) => {
  if (!iconRoute(request, response, true)) { response.writeHead(404); response.end(); }
}) : null;
negative?.listen(8879, '127.0.0.1', () => console.log('图标失败夹具已启动：http://127.0.0.1:8879/，仅用于显式图标验收'));

/** 关闭夹具时结束慢速连接，避免留下端口占用或等待中的下载。 */
function stop() {
  negative?.closeAllConnections(); negative?.close();
  server.closeAllConnections();
  server.close(() => process.exit(0));
}
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
