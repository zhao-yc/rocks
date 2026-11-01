import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const base=new URL('../android/kernel-compare/src/main/assets/',import.meta.url);
const context={module:{exports:{}}};vm.createContext(context);
vm.runInContext(readFileSync(new URL('frame-meter.js',base),'utf8'),context);
const FrameMeter=context.module.exports;

test('计数覆盖 60/90/120/144Hz，不将第一帧多算为一个间隔',()=>{
 for(const hz of [60,90,120,144]){
  const meter=new FrameMeter();let result=null;
  for(let i=0;i<=hz+2&&!result;i++)result=meter.push(i*1000/hz);
  assert.ok(result);assert.ok(Math.abs(result.fps-hz)<0.001);
 }
});
test('后台长暂停重新计数，非单调与非法时间戳不污染结果',()=>{
 const meter=new FrameMeter();meter.push(0);meter.push(10);meter.push(NaN);meter.push(9);
 assert.equal(meter.push(5000),null);let result=null;
 for(let i=1;i<=122&&!result;i++)result=meter.push(5000+i*1000/120);
 assert.ok(result);assert.ok(Math.abs(result.fps-120)<0.001);
});
test('P95 能识别长帧，不以平均帧率代替帧间隔',()=>{
 const meter=new FrameMeter();let time=0,result=null;meter.push(time);
 for(let i=0;i<100&&!result;i++){time+=i%10===0?40:10;result=meter.push(time);}
 assert.equal(result.p95,40);assert.ok(result.fps<100);
});
test('夹具内嵌计数器且无外部资源、采集或原生桥',()=>{
 const html=readFileSync(new URL('test-page.html',base),'utf8');
 assert.equal(html.split('/* FRAME_METER_SOURCE */').length,2);
 assert.doesNotMatch(html,/<(?:script|link|img)[^>]+(?:src|href)=/i);
 assert.doesNotMatch(html,/fetch\(|XMLHttpRequest|addJavascriptInterface|moz-extension/);
});
