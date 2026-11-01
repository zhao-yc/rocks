// 只绑定回环地址的原生浏览回归夹具，不接触外部服务、账号或本机文件。
import http from 'node:http';

/** 通过真实 HTTP 响应覆盖正常页面、长请求和空响应失败，全程不注入网页脚本。 */
const server = http.createServer((request, response) => {
  const route = request.url?.split('?')[0];
  if (route === '/fail') { response.destroy(); return; }
  if (route === '/delayed') {
    const timer = setTimeout(() => response.end('完成'), 45000);
    response.on('close', () => clearTimeout(timer));
    return;
  }
  if (['/bottom-white', '/bottom-dark', '/bottom-none', '/bottom-dynamic', '/bottom-obscured', '/bottom-sticky', '/bottom-sticky-covered'].includes(route)) {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    const fixed = route !== '/bottom-none';
    const color = route === '/bottom-dark' ? '#141e28' : '#ffffff';
    const requestedOffset = Number(new URL(request.url, 'http://127.0.0.1').searchParams.get('offset') || 0);
    const offset = Number.isFinite(requestedOffset) ? Math.max(0, Math.min(500, requestedOffset)) : 0;
    response.end(`<!doctype html><html lang="zh-CN"><meta name="viewport" content="width=device-width,initial-scale=1"><title>底栏背景回归</title>
      <style>body{margin:0;font:20px sans-serif;background:repeating-linear-gradient(#c74b63 0 80px,#386b94 80px 160px)}main{height:5000px;padding:24px}footer{position:fixed;bottom:0;left:0;right:0;height:56px;background:${color};color:#71955b}aside{position:absolute;inset:0;height:6000px;z-index:10;background:#654321}</style>
      ${route.startsWith('/bottom-sticky') ? `<style>footer{position:sticky;bottom:${offset}px}</style>` : ''}
      <main>底栏背景回归<input aria-label="网页测试输入框" placeholder="网页测试输入框"><button style="display:block;margin-top:16px;height:48px" onclick="document.documentElement.requestFullscreen()">网页全屏</button></main>${fixed ? '<footer id="bar">网页固定栏</footer>' : ''}
      ${route === '/bottom-obscured' ? '<aside>遮挡固定栏的普通内容</aside>' : ''}
      ${route === '/bottom-dynamic' ? '<script>setTimeout(()=>{document.getElementById("bar").style.background="#141e28"},3000);setTimeout(()=>{document.getElementById("bar").remove()},6000);</script>' : ''}</html>`);
    return;
  }
  if (!['/one', '/two', '/slow'].includes(route)) { response.writeHead(404); response.end(); return; }
  const name = route === '/two' ? 'Two' : route === '/slow' ? 'Slow' : 'One';
  response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  response.write(`<!doctype html><html lang="zh-CN"><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>日常体验 ${name}</title><style>body{margin:0;font:20px sans-serif;color:#173c2b;background:#fff}header{background:#d8e9f9;padding:24px}main{padding:24px}p{line-height:1.8;margin-bottom:100px}input{font-size:20px;width:90%}a{display:inline-block;margin:16px 24px 16px 0}footer{position:fixed;bottom:0;background:#edf4df;width:100%;padding:12px}.band{height:180px;background:#659ae6}</style></head><body><header>日常体验 ${name}</header><main><a href="/one">网页 One</a><a href="/two">网页 Two</a><input placeholder="网页表单"><div class="band"></div>${'<p>用于检查滚动、输入、标签切换与底部控件。浏览内容仅来自本机测试夹具。</p>'.repeat(16)}</main><footer>网页固定底栏</footer>${name === 'Slow' ? '<img src="/delayed" alt="慢速请求">' : ''}`);
  if (name === 'Slow') {
    const timer = setTimeout(() => response.end('</body></html>'), 45000);
    response.on('close', () => clearTimeout(timer));
  } else response.end('</body></html>');
});

server.listen(8878, '127.0.0.1', () => console.log('日常浏览夹具已启动：http://127.0.0.1:8878/one'));

/** 关闭夹具时结束慢速连接，避免留下端口占用或等待中的下载。 */
function stop() {
  server.closeAllConnections();
  server.close(() => process.exit(0));
}
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
