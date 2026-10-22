import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { p95, summarize, startServer } from './reading-performance.mjs';
const sample = (width,kind='continue-reading',extra={}) => ({viewport:{width,height:900},kind,status:'READY',documentVisibility:'visible',elapsedMs:100,error:null,horizontalOverflow:false,
  selectedParagraph:'第 9610 段',firstParagraph:'第 9610 段',paragraphCount:40,readingStatus:'已读边界：0 段',resources:[{path:'/api/reading/books'}],...extra});
const complete = () => [sample(1280,'shelf-navigation'), ...[1280,390].flatMap(w=>['continue-reading','shelf-return'].flatMap(k=>Array.from({length:30},()=>sample(w,k))))];
test('nearest-rank p95 retains slow tail rather than dropping outliers',()=>{
  assert.equal(p95([]),null);assert.equal(p95([...Array(28).fill(10),600,1000]),600);
});
test('isolated harness blocks cross-origin writes, paid actions and non-asset files; samples persist without overwrite',async()=>{
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'reading-performance-test-'));
  const dist=path.join(directory,'dist');fs.mkdirSync(dist);fs.writeFileSync(path.join(dist,'index.html'),'<head></head>');
  fs.writeFileSync(path.join(directory,'fixture.json'),JSON.stringify({port:18080,mode:'OFFLINE_ENGINEERING',versionId:'fixture',fixture:{resumeParagraph:9610},paragraphSamplesMs:[10],positionWriteSamplesMs:[10]}));
  const server=startServer(directory,0,dist);
  try {
    await once(server,'listening');const origin=`http://127.0.0.1:${server.address().port}`;
    assert.equal((await fetch(origin+'/books')).status,200);
    assert.equal((await fetch(origin+'/__performance/sample',{method:'POST',body:'{}'})).status,403);
    assert.equal((await fetch(origin+'/api/reading/versions/fixture/tasks',{method:'POST'})).status,403);
    assert.equal((await fetch(origin+'/api/reading/versions/fixture/read-boundary',{method:'PUT'})).status,403);
    assert.equal((await fetch(origin+'/api/reading/versions/other/resume-position',{method:'PUT'})).status,403);
    assert.equal((await fetch(origin+'/.env')).status,404);
    assert.equal((await fetch(origin+'/__performance/sample',{method:'POST',headers:{Origin:origin},body:'{}'})).status,400);
    assert.equal((await fetch(origin+'/__performance/sample',{method:'POST',headers:{Origin:origin},body:JSON.stringify({...sample(1280),resources:[]})})).status,201);
    assert.equal(JSON.parse(fs.readFileSync(path.join(directory,'report.json'))).samples.length,1);
    assert.throws(()=>startServer(directory,0,dist),/EEXIST/);
  } finally { await new Promise(resolve=>server.close(resolve));fs.rmSync(directory,{recursive:true,force:true}); }
});
test('a quick single run or a missing viewport cannot complete the benchmark',()=>{
  assert.equal(summarize([sample(1280)]).status,'INCOMPLETE_OR_FAILED');
  assert.equal(summarize(complete().filter(s=>s.viewport.width===1280)).status,'INCOMPLETE_OR_FAILED');
  assert.equal(summarize(complete()).status,'BROWSER_SAMPLES_PASS');
});
test('hidden, failed, overflowing, invalid and over-budget samples cannot be silently discarded',()=>{
  for(const bad of [{status:'TIMEOUT'},{documentVisibility:'hidden'},{error:'wrong location'},{horizontalOverflow:true},{elapsedMs:-1},{elapsedMs:2001},
    {selectedParagraph:'第 9611 段'},{paragraphCount:400},{readingStatus:'已读边界：1 段'},{resources:[]}]) {
    const result=summarize([...complete(),sample(1280,'continue-reading',bad)]);
    assert.equal(result.status,'INCOMPLETE_OR_FAILED');
    assert.equal(result.groups['1280x900/continue-reading'].count,31);
  }
});
