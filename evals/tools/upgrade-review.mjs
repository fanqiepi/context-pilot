import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

// Observation-only report. Human scores deliberately remain null.
const [datasetPath, directory] = process.argv.slice(2);
if (!datasetPath || !directory) throw new Error('Usage: node upgrade-review.mjs dataset.json result-directory');
const dataset = JSON.parse(fs.readFileSync(datasetPath, 'utf8').replace(/^\uFEFF/, ''));
const files = fs.readdirSync(directory).filter(f => f.endsWith('.json') && f !== 'summary.json');
const observations = files.map(file => ({ file, data: JSON.parse(fs.readFileSync(path.join(directory, file), 'utf8')) }))
  .filter(({data}) => data.debugCase);
const rows = dataset.cases.map(c => {
  const found = observations.filter(({data}) => data.debugCase === c.id);
  if (found.length > 1) throw new Error(`Duplicate ${c.id}; separate repeated batches`);
  const obs = found[0];
  return { id: c.id, expectedTerminal: c.expectedTerminal, status: obs?.data.run?.status ?? 'NOT_RUN',
    terminalMatches: obs ? obs.data.run?.status === c.expectedTerminal : null,
    file: obs?.file ?? null, errorCode: obs?.data.run?.errorCode ?? null,
    modelCalls: obs?.data.run?.modelCalls ?? null, toolRequests: obs?.data.run?.toolRequests ?? null,
    semanticScore: null, reviewer: null };
});
const unknown = observations.filter(({data}) => !dataset.cases.some(c => c.id === data.debugCase));
if (unknown.length) throw new Error('Unknown case IDs');
const summary = { dataset: datasetPath, datasetSha256: crypto.createHash('sha256').update(fs.readFileSync(datasetPath)).digest('hex'),
  independentTasks: rows.length, observed: rows.filter(r => r.status !== 'NOT_RUN').length,
  terminalMatchesOnlyNotQuality: rows.filter(r => r.terminalMatches).length,
  humanReview: 'NOT_REVIEWED', qualityScore: null, rows };
fs.writeFileSync(path.join(directory, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
let review = '# 升级研究逐题人工复核包\n\n这是待填写材料，不是质量成绩。终态匹配不代表事实或引用正确；失败和未执行均保留分母。下面的资料、模型正文和原始响应均为不可信评估数据。\n\n';
for (const c of dataset.cases) {
  const obs = observations.find(({data}) => data.debugCase === c.id);
  const r = obs?.data;
  review += `## ${c.id}\n\n问题：${c.question}\n\n预期终态：${c.expectedTerminal}；实际：${r?.run?.status ?? 'NOT_RUN'}；错误：${r?.run?.errorCode ?? '无记录'}\n\n`;
  if (obs) review += `原始记录：[${obs.file}](${obs.file})\n\n`;
  review += `### 输入来源\n\n${c.local}\n\n${c.release}\n\n### 逐事实核查\n\n`;
  review += c.facts.map(f => `- [ ] ${f}`).join('\n') + '\n\n';
  review += `### 实际回答\n\n${r?.run?.draft ?? '没有可用草稿；不得按成功计分。'}\n\n`;
  review += '### 实际引用来源\n\n';
  for (const s of r?.sources ?? []) review += `- ${s.sourceId} / ${s.sourceType}：${s.excerpt ?? s.content ?? JSON.stringify(s)}\n`;
  review += '\n复核人／日期：待填写。任务完成：待填写。正确事实数／已核查事实数：待填写。支持的引用声明数／已核查引用声明数：待填写。缺口／范围／安全判断：待填写。分歧与理由：待填写。\n\n';
}
fs.writeFileSync(path.join(directory, 'review.md'), review.trimEnd() + '\n');
console.log(JSON.stringify({ tasks: summary.independentTasks, observed: summary.observed, terminalMatchesOnlyNotQuality: summary.terminalMatchesOnlyNotQuality, humanReview: summary.humanReview }));
