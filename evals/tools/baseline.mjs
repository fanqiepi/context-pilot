import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = p => JSON.parse(fs.readFileSync(path.join(root, p), 'utf8'));
const fail = message => { throw new Error(message); };
export const hash = value => crypto.createHash('sha256').update(value).digest('hex');
export const cases = file => fs.readFileSync(path.join(root, 'datasets', file), 'utf8').trim().split(/\r?\n/).map(JSON.parse);
const config = () => read('configs/core-baseline-v1.json');
export function assets() {
  const files = ['configs/core-baseline-v1.json', 'configs/v6-upgrade-research-v1.json'];
  for (const dataset of config().datasets) {
    files.push('datasets/' + dataset.file);
    files.push(...fs.readdirSync(path.join(root, 'corpora', dataset.corpus)).map(f => `corpora/${dataset.corpus}/${f}`));
  }
  return Object.fromEntries(files.sort().map(f => [f, hash(fs.readFileSync(path.join(root, f)))]));
}
export function validate() {
  const ids = new Set(), signatures = new Set();
  for (const dataset of config().datasets) {
    const tasks = cases(dataset.file);
    if (tasks.length !== dataset.count) fail('Unexpected count: ' + dataset.file);
    const counts = {};
    for (const task of tasks) {
      if (!task.id || ids.has(task.id)) fail('Duplicate/empty task ID');
      ids.add(task.id);
      if (!task.question?.trim() || !task.facts?.length || !task.terminal || !Array.isArray(task.sources)) fail('Incomplete task: ' + task.id);
      const signature = hash(JSON.stringify([task.question.normalize('NFKC').replace(/\s/g, ''), task.setup ?? '', task.facts]));
      if (signatures.has(signature)) fail('Duplicate task: ' + task.id);
      signatures.add(signature);
      counts[task.category] = (counts[task.category] ?? 0) + 1;
      for (const source of task.sources) {
        const [file, anchor] = source.split('#');
        if (!/^[a-z0-9-]+\.md$/.test(file) || !/^[a-z_]+$/.test(anchor ?? '')) fail('Invalid source: ' + task.id);
        const text = fs.readFileSync(path.join(root, 'corpora', dataset.corpus, file), 'utf8');
        if (!text.split(/\r?\n/).includes('## ' + anchor)) fail('Missing source anchor: ' + task.id);
      }
    }
    if (dataset.categories && JSON.stringify(Object.entries(counts).sort()) !== JSON.stringify(Object.entries(dataset.categories).sort())) fail('Category counts differ');
  }
  const frozen = read('configs/core-baseline-v1.lock.json');
  if (JSON.stringify(frozen.sha256) !== JSON.stringify(assets())) fail('Frozen assets changed; publish a reviewed new version instead of silently rewriting the lock');
  return {status:'ASSETS_VERIFIED_NOT_TASK_ACCEPTANCE', datasets:config().datasets.map(({file, split, count}) => ({file,split,count}))};
}
export function template(file, mode) {
  if (!['ENGINEERING','MODEL_FIXED_RETRIEVAL','LIVE_END_TO_END'].includes(mode)) fail('Unknown evidence mode');
  if (!config().datasets.some(d => d.file === file)) fail('Unknown dataset');
  return {dataset:file, mode, rubricVersion:config().rubricVersion, assetDigest:hash(JSON.stringify(assets())),
    versions:{code:null,worktree:null,prompt:null,model:null,embeddingProfile:null,retrieval:null,budget:null,environment:null},
    results:cases(file).map(c => ({caseId:c.id, status:'NOT_RUN', terminal:null, reviewer:null, facts:[],
      safetyPassed:null, supportedCitations:null, reviewedCitations:null, totalMs:null, firstProgressMs:null,
      firstTextMs:null, inputTokens:null, outputTokens:null, traceId:null, failureCategory:null}))};
}
function nonnegative(value, label) {
  if (value !== null && (!Number.isFinite(value) || value < 0)) fail('Invalid metric: ' + label);
}
const percentile = (values, p) => values.length ? [...values].sort((a,b)=>a-b)[Math.ceil(values.length*p)-1] : null;
export function summarize(input) {
  const expected = template(input.dataset, input.mode);
  if (input.assetDigest !== expected.assetDigest || input.rubricVersion !== expected.rubricVersion) fail('Evidence contract mismatch');
  if (JSON.stringify(Object.keys(input.versions ?? {}).sort()) !== JSON.stringify(Object.keys(expected.versions).sort())) fail('Missing version fields');
  const taskMap = new Map(cases(input.dataset).map(c => [c.id,c]));
  const seen = new Set();
  let passed=0, failed=0, notRun=0, unreviewed=0, correctFacts=0, factCount=0, supported=0, reviewed=0;
  const latencies=[]; let unknownUsage=0, inputTokens=0, outputTokens=0;
  const results=[];
  for (const row of input.results ?? []) {
    const task=taskMap.get(row.caseId);
    if (!task || seen.has(row.caseId)) fail('Unknown/duplicate result');
    seen.add(row.caseId);
    if (!['NOT_RUN','COMPLETED','FAILED'].includes(row.status)) fail('Invalid status');
    for (const key of ['totalMs','firstProgressMs','firstTextMs','inputTokens','outputTokens','supportedCitations','reviewedCitations']) nonnegative(row[key], key);
    if (row.firstProgressMs !== null && row.totalMs !== null && row.firstProgressMs > row.totalMs) fail('Progress exceeds terminal latency');
    if (row.firstTextMs !== null && row.totalMs !== null && row.firstTextMs > row.totalMs) fail('Text exceeds terminal latency');
    if (row.status === 'NOT_RUN') { notRun++; results.push({caseId:row.caseId,passed:false,status:'NOT_RUN'}); continue; }
    if (Object.values(input.versions).some(v => typeof v !== 'string' || !v.trim())) fail('Executed results require all version fields (use explicit NOT_APPLICABLE where appropriate)');
    if (input.mode !== 'ENGINEERING' && !row.traceId) fail('Model evidence requires trace ID');
    if (row.status === 'FAILED' && !row.failureCategory) fail('Failure needs attribution');
    const signed=typeof row.reviewer === 'string' && row.reviewer.trim().length>0;
    const completeFacts=Array.isArray(row.facts) && row.facts.length===task.facts.length && row.facts.every(v=>typeof v==='boolean');
    if (signed && !completeFacts) fail('Signed review must score every expected fact');
    if (signed) {factCount+=row.facts.length; correctFacts+=row.facts.filter(Boolean).length;}
    if ((row.supportedCitations === null) !== (row.reviewedCitations === null)) fail('Citation numerator/denominator must both be known or unknown');
    if (row.reviewedCitations !== null) {
      if (!signed || row.supportedCitations > row.reviewedCitations || !Number.isInteger(row.reviewedCitations) || !Number.isInteger(row.supportedCitations)) fail('Invalid citation review');
      supported+=row.supportedCitations; reviewed+=row.reviewedCitations;
    }
    const citationGate = task.sources.length===0 || (row.reviewedCitations>0 && row.supportedCitations===row.reviewedCitations);
    const pass=row.status==='COMPLETED' && row.terminal===task.terminal && signed && completeFacts && row.facts.every(Boolean) && row.safetyPassed===true && citationGate;
    if(pass) passed++; else if(!signed && row.status==='COMPLETED') unreviewed++; else failed++;
    if(row.totalMs!==null) latencies.push(row.totalMs);
    if(row.inputTokens===null || row.outputTokens===null) unknownUsage++;
    else {inputTokens+=row.inputTokens;outputTokens+=row.outputTokens;}
    results.push({caseId:row.caseId,passed:pass,status:row.status,terminal:row.terminal,failureCategory:row.failureCategory});
  }
  if(seen.size!==taskMap.size) fail('Missing task results; record NOT_RUN explicitly');
  return {dataset:input.dataset,mode:input.mode,assetDigest:input.assetDigest,rubricVersion:input.rubricVersion,versions:input.versions,
    quality:{tasks:taskMap.size,passed,failed,notRun,unreviewed,successRate:passed/taskMap.size,correctFacts,factCount,factAccuracy:factCount?correctFacts/factCount:null,supportedCitations:supported,reviewedCitations:reviewed,citationSupport:reviewed?supported/reviewed:null},
    performance:{samples:latencies.length,missing:taskMap.size-notRun-latencies.length,p50Ms:percentile(latencies,.5),p95Ms:percentile(latencies,.95)},
    cost:{inputTokens,outputTokens,unknownUsage,monetaryEstimate:null},
    reliability:{executed:taskMap.size-notRun,failed,unreviewed},results};
}
export function compare(a,b) {
  for(const key of ['dataset','mode','assetDigest','rubricVersion']) if(a[key]!==b[key]) fail('Incomparable '+key);
  if(a.versions.environment!==b.versions.environment) fail('Incomparable environments');
  return {baseline:a,candidate:b,taskSuccessDelta:b.quality.successRate-a.quality.successRate,
    adoptionDecision:'MANUAL_REVIEW_REQUIRED',limitations:'Verify matching corpus, task execution and pricing conditions; no automatic adoption from pass counts.'};
}
if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const [command,...args]=process.argv.slice(2);
    let result;
    if(command==='validate') result=validate();
    else if(command==='template') {validate();result=template(args[0],args[1]);}
    else if(command==='report') {validate();result=summarize(JSON.parse(fs.readFileSync(args[0],'utf8')));}
    else if(command==='compare') {validate();result=compare(...args.map(f=>summarize(JSON.parse(fs.readFileSync(f,'utf8')))));}
    else fail('Usage: node evals/tools/baseline.mjs validate|template <dataset> <mode>|report <observations.json>|compare <baseline.json> <candidate.json>');
    process.stdout.write(JSON.stringify(result,null,2)+'\n');
  } catch(error) {process.stderr.write(error.message+'\n');process.exitCode=1;}
}
