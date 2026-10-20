import test from 'node:test';
import assert from 'node:assert/strict';
import {loadAssets, validateAssets, template, report, observationHash, verifyLock} from './reading-eval.mjs';

const {dataset, config} = loadAssets();
const clone = value => structuredClone(value);
function observed(index = 0) {
  const raw = template(dataset,config), c = dataset.cases[index], row = raw.results.find(r=>r.caseId===c.caseId);
  row.status = 'OBSERVED';
  row.observation = {terminal:'COMPLETED',answer:c.expectedFacts[0]?.text ?? '在当前已读范围内无法确认',
    citations:[{id:'E1',text:c.expectedFacts[0]?.text ?? '引用'}],calls:[],elapsedMs:10};
  row.review = {observationSha256:observationHash(row.observation), reviewer:'unit-test-reviewer',
    reviewedAt:'2026-10-20T10:00:00+08:00',rationale:'Synthetic unit test only',allClaimsReviewed:true,
    spoiler:false,fatalError:false,correctRefusal:false,facts:c.expectedFacts.length ? [{
      text:c.expectedFacts[0].text,verdict:'supported',citations:[{id:'E1',verdict:'supported'}],
      coveredExpectedFactIds:[c.expectedFacts[0].id],
    }] : []};
  return {raw,row,c};
}
test('asset layout, counts, hashes and split isolation are valid', () => {
  const result = validateAssets(dataset,config);
  assert.equal(result.cases,60);
  assert.equal(result.corpora,4);
});
test('scope, wrong-version evidence and shortened recap window are rejected', () => {
  for (const mutate of [
    d=>d.cases[0].expectedEvidence[0].ordinal=2,
    d=>d.cases[0].expectedEvidence[0].paragraphId='harbor-v2:p1',
    d=>d.cases[2].expectedEvidence.pop(),
    d=>d.cases[0].boundary=-1,
  ]) { const d=clone(dataset);mutate(d);assert.throws(()=>validateAssets(d,config)); }
});
test('source family leakage, duplicate requests, altered bytes and case counts are rejected', () => {
  for (const mutate of [
    d=>d.corpora[2].family='harbor',
    d=>d.cases.push(clone(d.cases[0])),
    d=>d.cases.pop(),
  ]) { const d=clone(dataset);mutate(d);assert.throws(()=>validateAssets(d,config)); }
  assert.throws(()=>validateAssets(dataset,config,()=>Buffer.from('altered')));
});
test('template reserves every repetition and cannot imply semantic success', () => {
  const raw = template(dataset,config), out=report(dataset,config,raw);
  assert.equal(raw.results.length,108);
  assert.equal(out.reviewStatus,'INCOMPLETE');
  assert.equal(out.acceptance,'NOT_DETERMINED');
  assert.ok(out.groups.every(g=>g.facts.pooled.rate===null && g.notRun===g.total));
});
test('missing, duplicated, unknown and extra repeats cannot shrink denominator', () => {
  for (const mutate of [r=>r.results.pop(),r=>r.results.push(r.results[0]),r=>r.results[0].caseId='other',r=>r.results[0].repeat=3]) {
    const raw=template(dataset,config);mutate(raw);assert.throws(()=>report(dataset,config,raw));
  }
});
test('terminal success alone has no semantic score', () => {
  const {raw,row}=observed();row.review=null;
  const out=report(dataset,config,raw),group=out.groups.find(g=>g.split==='TEST'&&g.repeat===1&&g.taskType==='RECAP');
  assert.equal(group.observed,1);assert.equal(group.reviewed,0);assert.equal(group.facts.pooled.rate,null);
});
test('review binds exact observation and requires complete human fields', () => {
  for(const mutate of [r=>r.observation.answer+='changed',r=>r.review.reviewer='',r=>r.review.allClaimsReviewed=false,
    r=>r.review.facts[0].coveredExpectedFactIds=['other'],r=>r.review.facts[0].citations[0].id='fake']) {
    const {raw,row}=observed();mutate(row);assert.throws(()=>report(dataset,config,raw));
  }
});
test('missing citations count as failed links, hallucinations reduce fact rate', () => {
  const {raw,row}=observed();
  row.review.facts.push({text:'unsupported claim',verdict:'unsupported',citations:[],coveredExpectedFactIds:[]});
  const out=report(dataset,config,raw),g=out.groups.find(g=>g.split==='TEST'&&g.repeat===1&&g.taskType==='RECAP');
  assert.equal(g.facts.pooled.rate,.5);assert.equal(g.citations.pooled.rate,.5);
  assert.equal(g.usable.denominator,16);assert.equal(g.usable.numerator,1);
});
test('empty answers are not 100 percent, macro and pooled are distinct', () => {
  const {raw,row}=observed();row.review.facts=[];
  const g=report(dataset,config,raw).groups.find(g=>g.split==='TEST'&&g.repeat===1&&g.taskType==='RECAP');
  assert.equal(g.facts.pooled.rate,null);assert.equal(g.facts.macro,null);assert.equal(g.facts.empty,1);
  assert.equal(g.usable.numerator,0);assert.equal(g.recapRecall.perCase[0].recall.rate,0);
  const a=observed(), b=observed(1);
  b.row.review.facts.push({text:'wrong',verdict:'unsupported',citations:[],coveredExpectedFactIds:[]},
    {text:'wrong again',verdict:'unsupported',citations:[],coveredExpectedFactIds:[]});
  a.raw.results[a.raw.results.findIndex(r=>r.caseId===b.row.caseId&&r.repeat===1)]=b.row;
  const mixed=report(dataset,config,a.raw).groups.find(g=>g.split==='TEST'&&g.repeat===1&&g.taskType==='RECAP');
  assert.equal(mixed.facts.pooled.rate,.5);assert.equal(mixed.facts.macro,(1+1/3)/2);
});
test('any spoiler blocks even when the remaining rows have not run', () => {
  const {raw,row}=observed();row.review.spoiler=true;
  assert.equal(report(dataset,config,raw).reviewStatus,'BLOCKED_SPOILER');
  row.review.spoiler=false;row.review.facts[0].verdict='spoiler';row.review.facts[0].coveredExpectedFactIds=[];
  assert.equal(report(dataset,config,raw).reviewStatus,'BLOCKED_SPOILER');
});
test('correct refusal requires appropriate terminal and no unsupported facts', () => {
  const index=dataset.cases.findIndex(c=>c.expectedOutcome==='INSUFFICIENT_EVIDENCE');
  const {raw,row}=observed(index);
  row.observation.terminal='INSUFFICIENT_EVIDENCE';row.review.observationSha256=observationHash(row.observation);
  row.review.correctRefusal=true;
  const group=()=>report(dataset,config,raw).groups.find(g=>g.split==='TEST'&&g.repeat===1&&g.taskType==='CHARACTER_LOOKUP');
  assert.equal(group().correctRefusal.numerator,1);
  row.review.facts=[{text:'spoilerless but false',verdict:'unsupported',citations:[],coveredExpectedFactIds:[]}];
  assert.equal(group().correctRefusal.numerator,0);
});
test('failures stay in denominator and unknown usage is not zero cost', () => {
  const {raw,row}=observed();row.status='FAILED';row.review=null;row.observation.errorCode='TIMEOUT';
  row.observation.calls=[{kind:'GENERATION',status:'UNKNOWN',inputTokens:null,outputTokens:null,reservedCny:.1,settledCny:null}];
  const out=report(dataset,config,raw);
  assert.equal(out.reviewStatus,'INCOMPLETE');assert.equal(out.calls.usage.settledCny.unknownCalls,1);
  assert.equal(out.calls.usage.reservedCny.knownSubtotal,.1);
  row.observation.calls[0].settledCny=0;
  assert.throws(()=>report(dataset,config,raw),/Unknown calls/);
});
test('invalid numeric fields and fabricated NOT_RUN evidence are rejected', () => {
  const {raw,row}=observed();row.observation.elapsedMs=-1;
  assert.throws(()=>report(dataset,config,raw));
  row.status='NOT_RUN';assert.throws(()=>report(dataset,config,raw));
});
test('freeze mismatch must fail without re-freezing', () => {
  assert.throws(()=>verifyLock({assetHashes:{'evals/tools/reading-eval.mjs':'bad'}}),/Frozen file changed/);
});
test('live observations require environment and actual import location mappings', () => {
  const {raw,row}=observed();raw.mode='LIVE_END_TO_END';
  assert.throws(()=>report(dataset,config,raw),/live environment/);
  raw.environment=Object.fromEntries(['codeFingerprint','model','embeddingProfile','os','java','database'].map(k=>[k,'test']));
  assert.throws(()=>report(dataset,config,raw),/live corpus binding/);
  const corpus=dataset.corpora[0];
  raw.bindings=[{corpusId:corpus.id,bookId:'test-book',versionId:'test-version',corpusSha256:corpus.sha256,
    paragraphs:corpus.paragraphs.map(p=>({ordinal:p.ordinal,sha256:p.sha256,id:p.id}))}];
  assert.throws(()=>report(dataset,config,raw),/live task provenance/);
  row.observation.taskId='test-task';row.observation.traceId='test-trace';
  row.review.observationSha256=observationHash(row.observation);
  assert.equal(report(dataset,config,raw).acceptance,'NOT_DETERMINED');
});
test('pending terminal cannot be reviewed and direct/multi latency are separate', () => {
  const {raw,row}=observed();row.observation.terminal='QUEUED';
  assert.throws(()=>report(dataset,config,raw),/Non-answer terminal/);
  const out=report(dataset,config,template(dataset,config));
  assert.equal(out.latencyByMode.length,9);
  assert.ok(out.latencyByMode.every(g=>g.samples===0&&g.p95Ms===null));
});
