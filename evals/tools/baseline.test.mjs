import test from 'node:test';
import assert from 'node:assert/strict';
import {validate,template,summarize,compare,cases} from './baseline.mjs';

const fresh=()=>template('core-debug-v1.jsonl','ENGINEERING');
function executed() {
  const input=fresh();
  for(const key of Object.keys(input.versions)) input.versions[key]='test-fixture';
  for(const [i,row] of input.results.entries()) Object.assign(row,{
    status:'COMPLETED',terminal:cases(input.dataset)[i].terminal,reviewer:'fixture-reviewer',
    facts:cases(input.dataset)[i].facts.map(()=>true),safetyPassed:true,
    reviewedCitations:1,supportedCitations:1,totalMs:(i+1)*100,inputTokens:10,outputTokens:5
  });
  return input;
}
test('frozen datasets and evidence anchors validate',()=>assert.equal(validate().datasets[0].count,50));
test('unexecuted templates never become accepted scores',()=>{
  const report=summarize(fresh());assert.equal(report.quality.passed,0);assert.equal(report.quality.notRun,3);
  assert.equal(report.quality.citationSupport,null);assert.equal(report.performance.p95Ms,null);
});
test('missing and duplicate results fail instead of reducing denominator',()=>{
  const input=fresh();input.results.pop();assert.throws(()=>summarize(input),/Missing/);
  input.results.push(input.results[0]);assert.throws(()=>summarize(input),/duplicate/);
});
test('human review, citations and every fact are required',()=>{
  const input=executed();assert.equal(summarize(input).quality.passed,3);
  input.results[0].facts[0]=false;assert.equal(summarize(input).quality.passed,2);
  input.results[1].reviewer=null;input.results[1].supportedCitations=null;input.results[1].reviewedCitations=null;
  assert.equal(summarize(input).quality.unreviewed,1);
  input.results[2].supportedCitations=0;assert.equal(summarize(input).quality.passed,0);
});
test('timeouts remain in latency percentiles; unknown usage is not zero usage',()=>{
  const input=executed();Object.assign(input.results[2],{status:'FAILED',terminal:'FAILED',totalMs:180000,
    failureCategory:'TIMEOUT',inputTokens:null,outputTokens:null});
  const report=summarize(input);assert.equal(report.performance.p95Ms,180000);
  assert.equal(report.cost.unknownUsage,1);assert.equal(report.quality.failed,1);
});
test('invalid measurements, incomplete versions, altered hashes and modes are rejected',()=>{
  let input=executed();input.results[0].totalMs=-1;assert.throws(()=>summarize(input),/Invalid metric/);
  input=executed();input.versions.prompt=null;assert.throws(()=>summarize(input),/version/);
  input=executed();input.assetDigest='tampered';assert.throws(()=>summarize(input),/contract/);
  const a=summarize(executed()),b=structuredClone(a);b.mode='LIVE_END_TO_END';assert.throws(()=>compare(a,b),/Incomparable/);
});
test('model runs require trace evidence and signed citation numerators cannot exceed denominators',()=>{
  const input=executed();input.mode='LIVE_END_TO_END';assert.throws(()=>summarize(input),/trace/);
  input.mode='ENGINEERING';input.results[0].supportedCitations=2;assert.throws(()=>summarize(input),/citation/);
});
