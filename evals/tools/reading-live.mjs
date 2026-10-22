import fs from 'node:fs';
import path from 'node:path';
import {createHash, randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {ROOT, loadAssets, validateAssets, verifyLock, hash, template} from './reading-eval.mjs';

export const endpoints = Object.freeze({generation:'https://api.deepseek.com/chat/completions',
  embedding:'https://llm-90adsh26dbyar5po.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/embeddings'});
export const PRICE='reading-cny-20261022-v1';
const lockFile='evals/configs/reading/acceptance-v7.lock.json';
export const RETEST='DEBUG_RETEST';
export const retestEntries=dataset=>planEntries(dataset).filter(e=>e.split==='DEBUG'&&e.kind==='TASK');
const read=f=>JSON.parse(fs.readFileSync(f,'utf8').replace(/^\uFEFF/,''));
const json=o=>JSON.stringify(o,null,2)+'\n';
const check=(v,message)=>{if(!v)throw Error(message);};
export function entryKey(batch,id,repeat=1) {
  const bytes=createHash('md5').update(`${batch}:${id}:${repeat}`).digest();bytes[6]=(bytes[6]&15)|48;bytes[8]=(bytes[8]&63)|128;
  const h=bytes.toString('hex');return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;
}
export function planEntries(dataset) {
  const rows=[];
  for(const split of ['DEBUG','TEST'])for(let repeat=1;repeat<=(split==='TEST'?2:1);repeat++) {
    const cases=dataset.cases.filter(c=>c.split===split).sort((a,b)=>a.corpusId.localeCompare(b.corpusId)||a.boundary-b.boundary||a.caseId.localeCompare(b.caseId));
    const previous=new Map();
    for(const c of cases) {
      if(repeat===1&&c.taskType!=='RECAP'&&(previous.get(c.corpusId)??0)<c.boundary) {
        rows.push({kind:'INDEX',entryId:`index:${c.corpusId}:${c.boundary}`,split,repeat:1,corpusId:c.corpusId,boundary:c.boundary,from:(previous.get(c.corpusId)??0)+1});
        previous.set(c.corpusId,c.boundary);
      }
      rows.push({kind:'TASK',entryId:c.caseId,split,repeat,corpusId:c.corpusId,boundary:c.boundary,case:c});
    }
  }
  return rows;
}
export function reservation(entry) {
  if(entry.kind==='INDEX')return {maxCalls:Math.ceil((entry.boundary-entry.from+1)/16),reserveCny:Math.ceil((entry.boundary-entry.from+1)/16)*1.024};
  const c=entry.case,recap=c.taskType==='RECAP',multi=c.mode==='MULTI_QUERY';
  return {maxCalls:recap?1:multi?5:2,reserveCny:(1048576*2+(recap?4096:2048)*8+(multi?1048576*2+512*8:0)+(recap?0:(multi?3:1)*128000*.5))/1e6};
}
export function localApi(base,fetcher=fetch) {
  const url=new URL(base);check(url.protocol==='http:'&&url.hostname==='127.0.0.1'&&url.port&&url.pathname==='/'&&!url.search&&!url.hash&&!url.username&&!url.password,'Use http://127.0.0.1:PORT only');
  return async(route,method='GET',body)=>{
    check(route.startsWith('/api/reading/')&&!route.includes('..'),'Invalid route');
    const form=body instanceof FormData;
    const response=await fetcher(new URL(route,url),{method,redirect:'error',signal:AbortSignal.timeout(15000),
      headers:body&&!form?{'Content-Type':'application/json'}:{},body:body?(form?body:JSON.stringify(body)):undefined});
    if(!response.ok){const error=await response.json().catch(()=>({}));throw Error(`HTTP_${response.status}:${/^[A-Z_0-9]+$/.test(error.code)?error.code:'READING_REQUEST_FAILED'}`);}
    return response.status===204?null:response.json();
  };
}
async function poll(api,route,deadline,accept) {
  while(Date.now()<deadline){const result=await api(route);if(accept(result))return result;await new Promise(r=>setTimeout(r,300));}
  throw Error('COLLECTOR_TIMEOUT_NO_RETRY');
}
export async function bind(api,dataset) {
  const bindings=[];
  for(const corpus of dataset.corpora) {
    const form=new FormData();form.set('file',new Blob([fs.readFileSync(path.join(ROOT,corpus.file))]),`${corpus.id}.txt`);
    form.set('clientRequestId',randomUUID());form.set('title',corpus.title);form.set('sourceDescription','固定阅读评估语料；来源与许可见 acceptance-v1/provenance.md');form.set('parserId',corpus.parserId);
    const created=await api('/api/reading/imports','POST',form);
    const preview=await poll(api,`/api/reading/imports/${created.id}`,Date.now()+120000,r=>r.status!=='PARSING');
    check(preview.status==='PREVIEW_READY'&&preview.paragraphCount===corpus.paragraphs.length&&preview.chapterCount===corpus.chapterCount,'Import preview mismatch');
    const confirmed=await api(`/api/reading/imports/${created.id}/confirm`,'POST',{previewHash:preview.previewHash});
    const chapters=await api(`/api/reading/versions/${confirmed.versionId}/chapters?size=100`);const paragraphs=[];
    for(const chapter of chapters.items){let cursor=null;do{
      const page=await api(`/api/reading/versions/${confirmed.versionId}/chapters/${chapter.id}/paragraphs?limit=100${cursor?'&cursor='+encodeURIComponent(cursor):''}`);
      for(const p of page.paragraphs){const expected=corpus.paragraphs[paragraphs.length];check(expected&&p.paragraphOrdinal===expected.ordinal&&p.textHash===expected.sha256&&hash(p.text)===expected.sha256,'Imported paragraph mismatch');paragraphs.push({id:p.id,ordinal:p.paragraphOrdinal,sha256:p.textHash});}
      cursor=page.nextCursor;
    }while(cursor);}
    check(paragraphs.length===corpus.paragraphs.length,'Incomplete paragraph binding');
    bindings.push({corpusId:corpus.id,corpusSha256:corpus.sha256,bookId:confirmed.bookId,versionId:confirmed.versionId,paragraphs});
  }
  return bindings;
}
export function makePlan(bindings,dataset,config,lockBytes) {
  check(bindings.length===dataset.corpora.length,'Four corpus bindings required');
  for(const corpus of dataset.corpora){const matches=bindings.filter(b=>b.corpusId===corpus.id);check(matches.length===1,'Duplicate or missing corpus');const b=matches[0];
    check(b.corpusSha256===corpus.sha256&&b.bookId&&b.versionId&&b.paragraphs.length===corpus.paragraphs.length,'Invalid corpus binding');
    corpus.paragraphs.forEach((p,i)=>check(b.paragraphs[i].ordinal===p.ordinal&&b.paragraphs[i].sha256===p.sha256&&b.paragraphs[i].id,'Invalid paragraph binding'));
  }
  return {schema:'reading-live-plan-v1',version:config.version,executionScope:RETEST,batchId:randomUUID(),implementationLockSha256:hash(lockBytes),
    datasetSha256:hash(fs.readFileSync(path.join(ROOT,'evals/datasets/reading/acceptance-v1.json'))),generationModel:config.generationModel,
    embeddingModel:config.embeddingModel,embeddingDimensions:1024,generationEndpoint:endpoints.generation,embeddingEndpoint:endpoints.embedding,bindings};
}
export function approvalTemplate(planBytes) {
  return {schema:'reading-live-approval-v1',status:'NOT_AUTHORIZED',authorizationId:randomUUID(),planSha256:hash(planBytes),
    executionScope:RETEST,previousAuthorizationId:null,previousApprovalDigest:null,
    pricingVersion:PRICE,pricingVerifiedAt:null,authorizedAt:null,expiresAt:null,limitCny:null,providerLimitsCny:{DEEPSEEK:null,DASHSCOPE:null},maxCalls:32,accountConditionsConfirmed:false};
}
function validateApproval(planBytes,approval) {
  const now=Date.now(),start=Date.parse(approval.authorizedAt),end=Date.parse(approval.expiresAt),verified=Date.parse(approval.pricingVerifiedAt);
  check(approval.schema==='reading-live-approval-v1'&&approval.status==='APPROVED'&&approval.accountConditionsConfirmed===true,'Independent paid approval required');
  check(approval.planSha256===hash(planBytes)&&approval.pricingVersion===PRICE&&approval.maxCalls===32,'Approval mismatch');
  check(JSON.parse(planBytes).executionScope===RETEST&&approval.executionScope===RETEST,'DEBUG retest only');
  check(/^[a-f0-9-]{36}$/.test(approval.previousAuthorizationId)&&/^[a-f0-9]{64}$/.test(approval.previousApprovalDigest)&&approval.previousAuthorizationId!==approval.authorizationId,'Stopped predecessor required');
  check(start<=now&&end>now&&end-start<=86400000&&verified<=start&&end-verified<=86400000,'Approval or price expired');
  const d=Number(approval.providerLimitsCny?.DEEPSEEK),e=Number(approval.providerLimitsCny?.DASHSCOPE);
  check(d>0&&d<=20&&e>0&&e<=10&&Number(approval.limitCny).toFixed(8)===(d+e).toFixed(8),'Invalid provider budgets');
}
export async function collect(api,plan,approval,raw,dataset,split,checkpoint) {
  check(['DEBUG','TEST'].includes(split),'Select DEBUG or TEST');
  const retest=plan.executionScope===RETEST;
  if(retest)check(split==='DEBUG','DEBUG retest forbids TEST');
  const status=await api('/api/reading/evaluation/status');
  check(status.liveAllowed&&status.authorizationId===approval.authorizationId&&status.planSha256===approval.planSha256&&status.implementationLockSha256===plan.implementationLockSha256,'Backend approval mismatch');
  if(split==='DEBUG')check(status.callsStarted===0,'DEBUG already started; recover the journal, do not rerun');
  if(split==='TEST')check(status.debugReviewed&&dataset.cases.filter(c=>c.split==='DEBUG').every(c=>raw.results.find(r=>r.caseId===c.caseId)?.status==='OBSERVED'),'Complete and review DEBUG first');
  raw.environment={...status.environment,codeFingerprint:plan.implementationLockSha256};raw.bindings=plan.bindings;
  raw.providerBudgets=status.providers;raw.authorizationId=approval.authorizationId;raw.costBasis='PEAK_UNCACHED_BUDGET_VALUE_NOT_INVOICE';raw.indexObservations??=[];raw.collectorEvents??=[];
  await checkpoint(raw);
  if(retest) {
    check(status.executionScope===RETEST&&status.maxCalls===32&&status.previousAuthorizationId===approval.previousAuthorizationId,'Retest backend scope mismatch');
    raw.reusedIndexes=[];
    for(const corpusId of new Set(retestEntries(dataset).map(e=>e.corpusId))) {
      const binding=plan.bindings.find(b=>b.corpusId===corpusId),requiredBoundary=Math.max(...retestEntries(dataset).filter(e=>e.corpusId===corpusId).map(e=>e.boundary));
      const state=await api(`/api/reading/versions/${binding.versionId}/state`);
      await api(`/api/reading/versions/${binding.versionId}/read-boundary`,'PUT',{clientRequestId:randomUUID(),expectedScopeRevision:state.scopeRevision,lastReadParagraphId:binding.paragraphs[requiredBoundary-1].id,confirmContiguousPrefix:true});
      const index=await api(`/api/reading/versions/${binding.versionId}/index`);
      check(index.ready&&index.readyBoundary>=requiredBoundary,'Retest requires existing complete index; no rebuild authorized');
      raw.reusedIndexes.push({corpusId,versionId:binding.versionId,requiredBoundary,index});await checkpoint(raw);
    }
  }
  for(const entry of (retest?retestEntries(dataset):planEntries(dataset).filter(e=>e.split===split))) {
    const binding=plan.bindings.find(b=>b.corpusId===entry.corpusId),version=binding.versionId;
    const row=entry.kind==='TASK'?raw.results.find(r=>r.caseId===entry.entryId&&r.repeat===entry.repeat):null;
    if(row)check(row.status==='NOT_RUN','Attempted task cannot be rerun');
    let work=null;const started=Date.now();
    try {
      const state=await api(`/api/reading/versions/${version}/state`);
      const applied=await api(`/api/reading/versions/${version}/read-boundary`,'PUT',{clientRequestId:randomUUID(),expectedScopeRevision:state.scopeRevision,lastReadParagraphId:binding.paragraphs[entry.boundary-1].id,confirmContiguousPrefix:true});
      const revision=applied.state.scopeRevision,requestKey=entryKey(plan.batchId,entry.entryId,entry.repeat);
      const preview=reservation(entry);raw.collectorEvents.push({entryId:entry.entryId,repeat:entry.repeat,at:new Date().toISOString(),event:'PREPARED',...preview});await checkpoint(raw);
      console.log(`${entry.entryId}/${entry.repeat}: <=${preview.maxCalls} HTTP, reserve <= CNY ${preview.reserveCny.toFixed(8)} (not invoice)`);
      if(entry.kind==='INDEX') {
        const index=await api(`/api/reading/versions/${version}/index`);if(index.ready)continue;
        const estimate=await api(`/api/reading/versions/${version}/index-estimate?expectedScopeRevision=${revision}`);
        check(estimate.fromBoundary===entry.from-1&&estimate.targetBoundary===entry.boundary,'Index plan changed');
        work=await api(`/api/reading/versions/${version}/index-jobs`,'POST',{clientRequestId:requestKey,expectedScopeRevision:revision,estimateHash:estimate.estimateHash});
      }else{
        const c=entry.case;
        work=await api('/api/reading/tasks','POST',{clientRequestId:requestKey,versionId:version,expectedScopeRevision:revision,type:c.taskType,
          chapterCount:c.taskType==='RECAP'?c.chapterCount:null,question:c.taskType==='RECAP'?null:c.input,mode:c.taskType==='RECAP'?null:c.mode});
      }
      raw.collectorEvents.push({entryId:entry.entryId,repeat:entry.repeat,event:'SUBMITTED',workId:work.id,traceId:work.traceId});await checkpoint(raw);
      work=await poll(api,`/api/reading/${entry.kind==='INDEX'?'index-jobs':'tasks'}/${work.id}`,Date.now()+(entry.kind==='INDEX'?340000:130000),r=>!['QUEUED','RUNNING'].includes(r.status));
      const calls=(work.usage?.calls??[]).map(c=>({kind:['ANSWER','PLAN'].includes(c.phase)?'GENERATION':'EMBEDDING',status:c.usageStatus==='UNKNOWN'?'UNKNOWN':c.status,
        inputTokens:c.inputTokens??null,outputTokens:c.outputTokens??null,reservedCny:c.reservedAmount==null?null:Number(c.reservedAmount),settledCny:c.settledAmount==null?null:Number(c.settledAmount),
        phase:c.phase,providerModel:c.providerModel??null,providerRequestId:c.providerRequestId??null,reservedInputTokens:c.reservedInputTokens??null,authorizationId:c.authorizationId??null}));
      if(entry.kind==='INDEX') {
        calls.forEach(c=>{c.indexJobId=work.id;});
        raw.indexObservations.push({entryId:entry.entryId,jobId:work.id,traceId:work.traceId,terminal:work.status,calls,elapsedMs:work.elapsedMillis??null});await checkpoint(raw);
        check(work.status==='READY'&&calls.every(c=>c.status!=='UNKNOWN'),'Index failed or usage unknown');continue;
      }
      const citations=[];
      for(const id of new Set((work.validatedResult?.facts??[]).flatMap(f=>f.evidenceIds))) {
        const citation=await api(`/api/reading/tasks/${work.id}/citations/${encodeURIComponent(id)}`);
        citations.push({id,text:citation.sources.map(s=>s.text).join('\n'),sources:citation.sources});
      }
      const success=['COMPLETED','PARTIAL','INSUFFICIENT_EVIDENCE'].includes(work.status);
      row.status=success?'OBSERVED':'FAILED';row.observation={taskId:work.id,traceId:work.traceId,terminal:work.status,
        answer:work.validatedResult?JSON.stringify(work.validatedResult):'',citations,calls,elapsedMs:work.elapsedMillis??null,errorCode:success?null:work.errorCode??'COLLECTOR_STOPPED',rawTask:work};
      // Attribute each successful shared index once; also retain the independent index audit above.
      for(const index of raw.indexObservations.filter(i=>!i.attributedTo)) { row.observation.calls.push(...index.calls);index.attributedTo=`${entry.entryId}/${entry.repeat}`; }
      await checkpoint(raw);check(success&&calls.every(c=>c.status!=='UNKNOWN'),'Task failed or usage unknown');
    }catch(error){
      // Preserve transport uncertainty separately; do not invent a task UUID or a successful observation.
      if(row&&row.status==='NOT_RUN') {
        row.status='FAILED';row.observation={taskId:work?.id??null,traceId:work?.traceId??null,terminal:'COLLECTOR_STOPPED',answer:'',citations:[],calls:[],elapsedMs:Date.now()-started,
          errorCode:'COLLECTOR_OBSERVATION_INCOMPLETE',provenanceStatus:'UNRESOLVED',clientRequestId:entryKey(plan.batchId,entry.entryId,entry.repeat)};
      }
      raw.collectorEvents.push({entryId:entry.entryId,repeat:entry.repeat,event:'STOPPED',workId:work?.id??null,reason:String(error.message).slice(0,180)});
      await checkpoint(raw);throw error;
    }
  }
  return raw;
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const [command,...args]=process.argv.slice(2),{dataset,config}=loadAssets();validateAssets(dataset,config);
  const lockBytes=fs.readFileSync(path.join(ROOT,lockFile));verifyLock(JSON.parse(lockBytes),{source:true});
  if(command==='bind') {
    const [base,output]=args;check(output&&!fs.existsSync(output),'New bindings path required');
    const bindings=await bind(localApi(base),dataset);fs.writeFileSync(output,json(bindings),{flag:'wx'});
  }else if(command==='prepare') {
    const [bindings,output]=args;check(output&&!fs.existsSync(output)&&!fs.existsSync(output+'.approval.json'),'New plan path required');
    const plan=makePlan(read(bindings),dataset,config,lockBytes),bytes=Buffer.from(json(plan));fs.writeFileSync(output,bytes,{flag:'wx'});
    fs.writeFileSync(output+'.approval.json',json(approvalTemplate(bytes)),{flag:'wx'});
    console.log(json({status:'NOT_AUTHORIZED',executionScope:RETEST,planSha256:hash(bytes),entries:retestEntries(dataset).length,maxHttp:32,
      aggregateReservationCny:retestEntries(dataset).reduce((n,e)=>n+reservation(e).reserveCny,0),providerStops:'REQUIRE_VERIFIED_PREDECESSOR_REMAINDER'}));
  }else if(command==='run') {
    const [base,planPath,approvalPath,output,split,debugRaw]=args;check(output&&!fs.existsSync(output)&&!fs.existsSync(output+'.journal.jsonl'),'New output path required');
    const planBytes=fs.readFileSync(planPath),plan=JSON.parse(planBytes),approval=read(approvalPath);validateApproval(planBytes,approval);
    check(plan.implementationLockSha256===hash(lockBytes),'Plan implementation changed');
    const raw=split==='TEST'?read(debugRaw):template(dataset,config,'LIVE_END_TO_END');
    if(split==='TEST')check(raw.authorizationId===approval.authorizationId&&raw.manifestSha256===hash(lockBytes),'DEBUG evidence mismatch');
    raw.manifestSha256=hash(lockBytes);
    const fd=fs.openSync(output+'.journal.jsonl','wx');
    const checkpoint=async state=>{fs.writeSync(fd,JSON.stringify(state)+'\n');fs.fsyncSync(fd);};
    try{await collect(localApi(base),plan,approval,raw,dataset,split,checkpoint);}
    finally{fs.closeSync(fd);fs.writeFileSync(output,json(raw),{flag:'wx'});}
  }else throw Error('Commands: bind BASE OUTPUT | prepare BINDINGS OUTPUT | run BASE PLAN APPROVAL OUTPUT DEBUG|TEST [DEBUG_RAW]');
}
