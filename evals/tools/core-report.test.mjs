import test from 'node:test';
import assert from 'node:assert/strict';
import {coreReport} from './core-report.mjs';
import {cases} from './baseline.mjs';
const fresh=()=>({dataset:'core-workspace-v1.jsonl',results:cases('core-workspace-v1.jsonl').map(t=>({caseId:t.id,status:'NOT_RUN'}))});
test('missing and duplicated rows cannot shrink the frozen denominator',()=>{
  const raw=fresh();raw.results.pop();assert.throws(()=>coreReport(raw),/Missing/);
  raw.results.push(raw.results[0]);assert.throws(()=>coreReport(raw),/duplicate/);
});
test('matching terminals never become semantic scores and unknown usage stays unknown',()=>{
  const raw=fresh();Object.assign(raw.results[0],{status:'OBSERVED',terminal:'COMPLETED',totalMs:12,modelCalls:[{prompt_tokens:null,completion_tokens:null}]});
  Object.assign(raw.results[1],{status:'FAILED',errorType:'Timeout',totalMs:90000});
  const {summary}=coreReport(raw);assert.equal(summary.executed,2);assert.equal(summary.notRun,48);
  assert.equal(summary.semanticSuccessRate,null);assert.equal(summary.terminalMatches,1);
  assert.equal(summary.usage.unknownUsage,1);assert.equal(summary.latency.p95Ms,90000);
});
