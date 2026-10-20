# 续读闭环演示与试用脚本

2026-10-20。演示对象为本机/可信网络单用户。界面名仍为“研序｜技术研究 Agent”，ContextPilot 为技术标识。**真实阅读 AI 尚未开放；固定任务只能演示工程交互，不能作为模型回答、防剧透或真实试用证据。**

## 准备

按[本地开发](local-development.md)启动基础阅读；首次演示用自创 [reading-library-demo.txt](../../evals/corpora/reading/reading-library-demo.txt)。不要用最终 TEST 调整 Prompt。用于真实评测的固定资产和报告命令见[阅读工具](../../evals/tools/reading-README.md)。

如果要展示 AI 笔记的工程流程，使用现有隔离测试夹具：

```powershell
cd backend
$env:JAVA_HOME='F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9'
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=ReadingNoteProposalPersistenceTests#offlineBrowserFixture' '-Dreading.note.ui=true' test
```

它启动 Testcontainers 隔离数据库与 localhost 随机端口，元数据在 `backend/target/reading-note-ui.json`。另一个终端启动前端：

```powershell
cd frontend
$fixture = Get-Content ../backend/target/reading-note-ui.json | ConvertFrom-Json
$env:VITE_API_PROXY_TARGET='http://127.0.0.1:'+$fixture.port
node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5212 --strictPort
```

结束时关闭测试标签和 Vite，将 `backend/target/reading-note-ui.done` 写为 `done`，夹具会退出并回收容器。不要将随机测试端口或固定任务暴露给公网，不往日常数据库灌演示完成态。

## 演示顺序

| 场景 | 操作 | 可核对的结果 |
| --- | --- | --- |
| 导入 | 选自创 TXT→核对分章和首尾→明确确认 | 书架显示书籍，正文版本独立；导入没有模型费用 |
| 中断再回来 | 阅读并等到位置“已保存”→关闭页面→重新打开书架→一次点击继续阅读 | 恢复最后一次服务端确认位置；页面跳转本身不增加已读边界 |
| 显式边界 | 选择已完整读完的段落，点击已读到这里并确认 | 展示“从开头至第 B 段”；不能把跳到后章解释成已经全读 |
| 回顾与查证 | 正常环境展示尚未授权提示；隔离夹具选择已存在的固定完成任务 | 明确标注“固定离线结果”；不说这是刚刚由模型生成 |
| 查原文 | 点证据→看章/段和原文→关闭预览 | 一次关闭返回原位，不修改续读位置；只有“从这里继续读”才写位置 |
| 保存笔记 | 编辑→生成最终预览→停下来核对→独立确认 | 预览时笔记数不增加；确认后只一份；再次打开仍有出处及编辑标记 |
| 回退 | 重设较早的已读边界→再查看较后任务与笔记 | 标题、正文和引用被隐藏；仍能正常阅读 |
| 窄屏 | 重复续读、引用与保存；使用抽屉后返回正文 | 控件可见可点；无横向溢出或丢失已确认的保存 |

录制时开头和 AI 演示旁都标明证据种类，末尾展示当前限制。当前没有新录屏文件；不能把脚本完成写成录屏完成。

## 真实试用记录表

为每位真实试用者建立匿名编号，勿提交姓名、联系方式和私人小说。邀请试用、模型运行与发布仍按用户安排，工具不能虚构反馈。

| 字段 | 记录方式 |
| --- | --- |
| participantId / 日期 / 设备 / 视口 | 匿名编号，实际填写 |
| 文本/版本/边界 | 记录测试资产标识；私人文本不入库 |
| 模式 | 基础阅读 / 固定离线演示 / 已授权真实 AI，分别分析 |
| 三条核心流程 | 是否独立完成、操作数、卡点、误操作、是否丢保存或错位 |
| 回顾是否帮助接读 | 是/否/无法判断，保留用户原意与理由 |
| 对原文的信任、剧透或遗漏 | 具体事实和出处，由独立复核者判定 |

只有真实 AI 回顾和真实读者意见才能支持路线中的回顾帮助率；固定结果页面走通不计作真实读者样本。当前真实样本数为零。

## 求职说明可用措辞

> 实现单用户小说阅读闭环：版本化导入、独立续读位置与段落级已读范围、受范围约束的任务和引用、持久笔记提案与原子幂等确认。使用 PostgreSQL 集成测试验证事务、隔离和范围回退，建立冻结语料及人工评分工具。真实阅读模型适配与语义验收尚未完成。

容量实测可附上机器、冷热状态和服务层口径，引用[本轮验证](../evaluation/reading-experience-verification.md)。不使用“绝对不剧透”“语义准确率已达标”“真实读者通过”或“产品已上线”等尚无证据的描述。
