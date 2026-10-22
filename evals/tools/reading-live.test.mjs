import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {ROOT,loadAssets,template,report} from './reading-eval.mjs';
import {planEntries,reservation,entryKey,approvalTemplate,localApi,collect} from './reading-live.mjs';
const {dataset,config}=loadAssets();
test('fixed batch preserves all repetitions and incremental prefix indexes',()=>{
  const entries=planEntries(dataset);assert.equal(entries.length,135);
  assert.equal(entries.filter(e=>e.kind==='TASK').length,108);
  assert.equal(entries.reduce((n,e)=>n+reservation(e).maxCalls,0),346);
  assert.equal(new Set(entries.map(e=>entryKey('00000000-0000-0000-0000-000000000001',e.entryId,e.repeat))).size,135);
  for(const corpus of dataset.corpora){const indexes=entries.filter(e=>e.kind==='INDEX'&&e.corpusId===corpus.id);let from=1;for(const i of indexes){assert.equal(i.from,from);from=i.boundary+1;}}
  assert.equal(reservation(entries.find(e=>e.kind==='TASK'&&e.case.taskType==='RECAP')).reserveCny,2.12992);
});
test('approval template never authorizes paid traffic',()=>{
  const a=approvalTemplate(Buffer.from('{}'));assert.equal(a.status,'NOT_AUTHORIZED');assert.equal(a.authorizedAt,null);assert.equal(a.accountConditionsConfirmed,false);
});
test('collector cannot use provider addresses or redirects',async()=>{
  for(const url of ['https://api.deepseek.com','http://localhost:8080','http://127.0.0.1:8080/other','http://user@127.0.0.1:8080'])assert.throws(()=>localApi(url));
  let calls=0;const api=localApi('http://127.0.0.1:8080',async(url,options)=>{calls++;assert.equal(options.redirect,'error');return {ok:false,status:503,json:async()=>({})};});
  await assert.rejects(api('/api/reading/evaluation/status'),/HTTP_503/);assert.equal(calls,1);
});
test('missing approval stops collector before any mutation',async()=>{
  let calls=0;await assert.rejects(collect(async route=>{calls++;assert.equal(route,'/api/reading/evaluation/status');return {liveAllowed:false};},{},{},template(dataset,config,'LIVE_END_TO_END'),dataset,'DEBUG',async()=>{}));assert.equal(calls,1);
});
test('transport uncertainty remains a failed observation and stops without retry',async()=>{
  let submits=0,checkpoints=0;const bindings=dataset.corpora.map(c=>({corpusId:c.id,corpusSha256:c.sha256,bookId:'book',versionId:'version',paragraphs:c.paragraphs.map(p=>({id:p.id,ordinal:p.ordinal,sha256:p.sha256}))}));
  const plan={batchId:'00000000-0000-0000-0000-000000000001',implementationLockSha256:'lock',bindings};
  const approval={authorizationId:'approval',planSha256:'plan'};const raw=template(dataset,config,'LIVE_END_TO_END');
  const api=async(route,method)=>{
    if(route.endsWith('/evaluation/status'))return {liveAllowed:true,authorizationId:'approval',planSha256:'plan',implementationLockSha256:'lock',callsStarted:0,environment:{os:'fixture',java:'21',database:'fixture',model:config.generationModel,embeddingProfile:'fixture'}};
    if(route.endsWith('/state'))return {scopeRevision:1};
    if(route.endsWith('/read-boundary'))return {state:{scopeRevision:2}};
    if(route.endsWith('/tasks')&&method==='POST'){submits++;throw Error('HTTP_TIMEOUT');}throw Error('Unexpected route');
  };
  await assert.rejects(collect(api,plan,approval,raw,dataset,'DEBUG',async()=>{checkpoints++;}),/HTTP_TIMEOUT/);
  assert.equal(submits,1);assert(checkpoints>=2);assert.equal(raw.results.filter(r=>r.status==='FAILED').length,1);
  const summary=report(dataset,config,raw);assert.equal(summary.unresolvedAttempts,1);assert.equal(summary.reviewStatus,'INCOMPLETE');
});
test('old locks and data remain separate files',()=>{
  for(const name of ['acceptance-v1.lock.json','acceptance-v2.lock.json','acceptance-v3.lock.json'])assert(fs.existsSync(path.join(ROOT,'evals/configs/reading',name)));
});
test('successful local collector fixture keeps sources, timing and shared index costs once',async()=>{
  const bindings=dataset.corpora.map(c=>({corpusId:c.id,corpusSha256:c.sha256,bookId:'book-'+c.id,versionId:c.id,paragraphs:c.paragraphs.map(p=>({id:p.id,ordinal:p.ordinal,sha256:p.sha256}))}));
  const plan={batchId:'00000000-0000-0000-0000-000000000002',implementationLockSha256:'lock',bindings};const approval={authorizationId:'approval',planSha256:'plan'};
  const raw=template(dataset,config,'LIVE_END_TO_END'),works=new Map();let boundary=0,ready=0,submitted=0;
  const call=phase=>({phase,status:'SUCCEEDED',usageStatus:'ACTUAL',inputTokens:10,outputTokens:phase==='ANSWER'?5:0,reservedAmount:1,settledAmount:.001,authorizationId:'approval'});
  const api=async(route,method='GET',body)=>{
    if(route.endsWith('/evaluation/status'))return {liveAllowed:true,authorizationId:'approval',planSha256:'plan',implementationLockSha256:'lock',callsStarted:0,environment:{os:'fixture',java:'21',database:'fixture',model:config.generationModel,embeddingProfile:'fixture'}};
    if(route.endsWith('/state'))return {scopeRevision:1};
    if(route.endsWith('/read-boundary')){boundary=Number(body.lastReadParagraphId.split(':p')[1]);return {state:{scopeRevision:2}};}
    if(route.endsWith('/index'))return {ready:ready>=boundary};
    if(route.includes('/index-estimate?'))return {fromBoundary:ready,targetBoundary:boundary,estimateHash:'fixture'};
    if(method==='POST') {
      const index=route.endsWith('/index-jobs'),id='work-'+(++submitted);if(index)ready=boundary;
      works.set(id,{id,traceId:'trace-'+id,status:index?'READY':'COMPLETED',validatedResult:index?null:{facts:[{text:'离线固定结果',evidenceIds:['E1']}],partial:false},usage:{calls:[call(index?'INDEX_0':'ANSWER')]},elapsedMillis:23});
      return {id,traceId:'trace-'+id,status:'QUEUED'};
    }
    if(route.includes('/citations/'))return {sources:[{text:'离线原文',paragraphOrdinal:1}]};
    return works.get(route.split('/').at(-1));
  };
  await collect(api,plan,approval,raw,dataset,'DEBUG',async()=>{});
  assert.equal(submitted,18);assert.equal(raw.results.filter(r=>r.status==='OBSERVED').length,12);assert.equal(raw.indexObservations.length,6);
  assert.equal(raw.results.flatMap(r=>r.observation?.calls??[]).length,18);assert(raw.indexObservations.every(i=>i.attributedTo));
  assert.equal(raw.results.find(r=>r.status==='OBSERVED').observation.elapsedMs,23);
  const summary=report(dataset,config,raw);assert.equal(summary.calls.total,18);assert.equal(summary.reviewStatus,'INCOMPLETE');
});
