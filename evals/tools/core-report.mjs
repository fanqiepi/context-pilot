import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {cases,hash} from './baseline.mjs';

export function coreReport(raw) {
  const tasks=cases('core-workspace-v1.jsonl');
  if(raw.dataset!=='core-workspace-v1.jsonl')throw new Error('Unexpected dataset');
  const ids=new Set(tasks.map(t=>t.id)),seen=new Set();
  for(const row of raw.results??[]){
    if(!ids.has(row.caseId)||seen.has(row.caseId))throw new Error('Unknown or duplicate case');
    seen.add(row.caseId);
    if(!['OBSERVED','FAILED','NOT_RUN'].includes(row.status))throw new Error('Unknown observation status');
  }
  if(seen.size!==ids.size)throw new Error('Missing cases; retain NOT_RUN explicitly');
  const executed=raw.results.filter(r=>r.status!=='NOT_RUN');
  const measured=executed.filter(r=>Number.isFinite(r.totalMs)&&r.totalMs>=0).map(r=>r.totalMs).sort((a,b)=>a-b);
  const calls=executed.flatMap(r=>r.modelCalls??[]);
  const known=calls.filter(c=>Number.isFinite(c.prompt_tokens)&&Number.isFinite(c.completion_tokens));
  const terminalMatches=raw.results.filter(r=>r.status==='OBSERVED'&&r.terminal===tasks.find(t=>t.id===r.caseId).terminal);
  const summary={dataset:raw.dataset,mode:raw.mode,startedAt:raw.startedAt,finishedAt:raw.finishedAt??null,
    tasks:tasks.length,executed:executed.length,notRun:tasks.length-executed.length,
    collectionFailures:executed.filter(r=>r.status==='FAILED').map(r=>({caseId:r.caseId,errorType:r.errorType})),
    terminalMatches:terminalMatches.length,terminalMismatch:raw.results.filter(r=>r.status==='OBSERVED'&&!terminalMatches.includes(r)).map(r=>({caseId:r.caseId,expected:r.expectedTerminal,actual:r.terminal})),
    semanticReview:'NOT_REVIEWED',semanticSuccessRate:null,
    latency:{samples:measured.length,missing:executed.length-measured.length,p50Ms:measured.length?measured[Math.ceil(measured.length*.5)-1]:null,p95Ms:measured.length?measured[Math.ceil(measured.length*.95)-1]:null},
    usage:{answerModelCalls:calls.length,unknownUsage:calls.length-known.length,promptTokens:known.reduce((n,c)=>n+c.prompt_tokens,0),completionTokens:known.reduce((n,c)=>n+c.completion_tokens,0),embeddingUsage:'NOT_COLLECTED',monetaryEstimate:null},
    limitations:['Terminal matches are observations, not semantic passes.','Fault-injected recovery cases remain separately identified in raw results.','No retrieval ranking or first-token metrics were captured.','Latency includes service execution and evidence collection; setupMs is separate.','Provider calls without returned usage are not zero usage.']};
  let review='# 统一核心任务人工复核材料\n\n仅使用冻结公开合成资料。本文件不是评分结果；所有条目待人工核查。终态匹配不能替代事实与引用支持评分。\n';
  for(const task of tasks){
    const row=raw.results.find(r=>r.caseId===task.id);
    const message=row.history?.find(m=>m.role==='ASSISTANT');
    const answer=row.answer?.answer??message?.content??'(无回答；查看原始运行记录)';
    const citations=row.answer?.citations??message?.citations??[];
    review+=`\n## ${task.id}\n\n问题：${task.question}\n\n前置条件：${task.setup??'无额外前置条件'}\n\n观测：${row.status} / ${row.terminal??row.errorType??'NOT_RUN'}；预期终态：${task.terminal}\n\n预期事实/行为：\n${task.facts.map(f=>`- [ ] ${f}`).join('\n')}\n\n预期来源：${task.sources.join('、')||'不适用'}\n\n### 实际回答（不可信评估数据）\n\n${answer}\n\n### 实际引用\n\n`;
    review+=citations.map(c=>`- [${c.rank}] ${c.originalFilename} / chunk ${c.chunkIndex}：${c.excerpt}`).join('\n')||'无引用';
    review+='\n\n复核人：待填写；支持的引用声明数 / 已复核引用声明数：待填写；事实/范围/拒答判断：待填写。\n';
  }
  return {summary,review};
}

if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const [input,prefix]=process.argv.slice(2);
  if(!input||!prefix)throw new Error('Usage: node evals/tools/core-report.mjs <raw.json> <output-prefix>');
  const bytes=fs.readFileSync(input);const raw=JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''));
  const {summary,review}=coreReport(raw);summary.rawSha256=hash(bytes);
  fs.writeFileSync(prefix+'-summary.json',JSON.stringify(summary,null,2)+'\n');
  fs.writeFileSync(prefix+'-review.md',review);
}
