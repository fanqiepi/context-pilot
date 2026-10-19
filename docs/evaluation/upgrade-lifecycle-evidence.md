# 升级研究生命周期证据映射

核对日期：2026-10-19。合同来自 `evals/configs/v6-upgrade-research-v1.json` 的 22 项生命周期案例；不修改冻结输入、预算或阈值。22 项已有下表所列工程证据，各行保留具体实验边界，不以方法数量替代独立实验数量或语义质量成绩。

测试位置：`backend/src/test/java/io/github/fanqiepi/contextpilot/upgraderesearch/`。下表简称 P 为 `UpgradeRunPersistenceTests`，C 为 `UpgradeResearchControllerTests`，M 为 `UpgradeModelGatewayTests`，R 为 `UpgradeReadToolsTests`。P 使用独立 Testcontainers PostgreSQL/pgvector，其他类使用受控模型或本机 HTTP 服务，不产生供应商调用。

| 合同 | 测试方法 | 覆盖与剩余边界 |
| --- | --- | --- |
| LIFE-001 并发同键启动 | P.concurrentRequestReplayCreatesExactlyOneRun；P.simultaneousStartRequestsDispatchOnlyOnce | 真实数据库四路同时启动，返回同一运行且只派发一次 |
| LIFE-002 同键异参 | P.rejectsKeyReuseAndDeletedReplayWithoutRevivingRun；C.requestAndProposalConflictsExpose409AndExactCodes | 数据库拒绝冲突，接口断言 HTTP 409 与 UPGRADE_REQUEST_ID_CONFLICT |
| LIFE-003 队列容量 | P.queueAdmissionRejectsFourthNewRunButAllowsExistingReplay | 第四项拒绝、无数据库残留，既有请求可重放 |
| LIFE-004 提交后派发失败 | P.committedRunFailsWhenDispatchIsRejected | 关闭执行器注入拒绝，已提交运行 FAILED/DISPATCH_FAILED，重放不执行 |
| LIFE-005 取消与调用登记 | P.cancellationPreventsNewCallRegistrationAndLateResultPersistence；P.cancellationRacesWithRegistrationWithoutLeavingActiveCalls | 两线程屏障竞争，取消先提交则登记失败，登记先提交则记录转为 CANCELLED；取消后禁止再次登记 |
| LIFE-006 取消与完成竞争 | P.concurrentCompletionAndCancellationHaveOneIrreversibleTerminal | 两线程屏障竞争，一个成功转换；迟到失败与 claim 不复活 |
| LIFE-007 总超时与不可中断远端 | P.expiredRunRejectsLateUninterruptibleModelResult | 修改测试时钟对应的数据库截止时间，远端忽略中断；本地失败且迟到结果无写回，不宣称远端停止计费 |
| LIFE-008 重启收敛 | P.deadlineAndRestartConvergeWithoutReexecution | 调用实际恢复服务，遗留运行 PROCESS_RESTARTED；非真实杀进程实验 |
| LIFE-009 SSE 重连 | P.activeStreamCanDisconnectAndReconnectToSameRunWithoutRevisionRollback；C.terminalStreamReplaysSnapshotAndDoneWithoutCancelling | 真实 HTTP 读取 RUNNING 快照后关闭连接，运行未取消；同 runId 重连得到终态 snapshot/result/done，revision 不倒退 |
| LIFE-010 重复保存提案 | P.nativeToolLoopPersistsEvidenceAndAuditsEveryCall | 同键重放及新键同内容提案返回同动作 ID；直接断言聊天消息行数不增加 |
| LIFE-011 标题冲突 | P.rejectionExpiryTitleConflictAndSourceChangeNeverSaveReports；C.requestAndProposalConflictsExpose409AndExactCodes | 真实业务冲突错误码 REPORT_PROPOSAL_CONFLICT，接口返回 HTTP 409 |
| LIFE-012 未确认/拒绝/过期 | P.nativeToolLoopPersistsEvidenceAndAuditsEveryCall；P.rejectionExpiryTitleConflictAndSourceChangeNeverSaveReports | 未确认及拒绝无报告、过期后不保存 |
| LIFE-013 四路确认 | P.nativeToolLoopPersistsEvidenceAndAuditsEveryCall | 四路真实事务返回同一报告 ID |
| LIFE-014 插入后抛错 | P.reportAndExecutionClaimRollbackWhenSuccessMarkFails | 报告插入后在成功标记处抛错，报告与执行声明均回滚 |
| LIFE-015 成功标记失败 | P.reportAndExecutionClaimRollbackWhenSuccessMarkFails | 与 LIFE-014 共享同一故障注入，不计作两项独立实验 |
| LIFE-016 来源变化 | P.detectsIndexChangesAndFiltersOtherOwners；P.rejectionExpiryTitleConflictAndSourceChangeNeverSaveReports；P.deletingSourceDuringRunRejectsCompletionAndReportProposal | 重建后快照失效、确认不保存；运行中逻辑删除来源后完成被 SOURCE_CHANGED 拒绝，不能产生报告提案 |
| LIFE-017 删除报告后重放 | P.nativeToolLoopPersistsEvidenceAndAuditsEveryCall | 仍返回旧资源 ID，报告保持逻辑删除，新提案拒绝 |
| LIFE-018 删除运行后重放 | P.rejectsKeyReuseAndDeletedReplayWithoutRevivingRun | 精确 UPGRADE_RUN_DELETED，运行不复活 |
| LIFE-019 非法 HTTP 响应 | R.statusCodesAreDistinctAndNeverRetriedOrRedirected；R.rejectsOversizedMalformedEmptyDraftAndCompressedResponses | 重定向不跟随，超大/JSON/空正文等有界失败 |
| LIFE-020 HTTP 错误码 | R.statusCodesAreDistinctAndNeverRetriedOrRedirected | 401/403/404/429/500 分别记录，一次请求无重试 |
| LIFE-021 模型协议/超时 | P.duplicateCallIdAndFourthRoundToolsStopWithoutExtraReads；P.expiredRunRejectsLateUninterruptibleModelResult(false)；M.inputBudgetIsCheckedBeforeProviderAndNoRetryOccurs | 重复 ID、第四轮工具拒绝，无额外读取；不可中断模型实际等待单次 60 秒边界后 CALL_TIMEOUT，此时总 deadline 尚未到达，迟到结果无写回 |
| LIFE-022 输入/账本超限 | M.inputBudgetIsCheckedBeforeProviderAndNoRetryOccurs；P.evidenceCharacterLimitRejectsInsertionAtomically | 输入超限零调用、账本超限零插入；未进行自动摘要或扩大预算 |

## 本轮复现

使用 JDK 21，在 `backend/` 执行 `mvnw.cmd -q -Dtest=Upgrade*Tests,ResearchReport*Tests test`。第一批 35 项通过（P 25、C 3、M 2、R 5）；补齐直接证据后的最终复验为 **41 项全部通过、无跳过**（P 30、C 4、M 2、R 5）。P 套件约 73.68 秒，包含实际 60 秒模型调用超时实验。这里是生命周期工程门禁，不包含供应商调用、语义评分或最终启用决定。

脱敏[执行摘要](../../evals/reports/2026-10-19-upgrade-closeout-engineering.json)保留基础提交、未提交变更标记、测试源码哈希和逐套件结果；不含密钥、模型请求或用户资料。

最终直接证据见[41 项执行摘要](../../evals/reports/2026-10-19-upgrade-lifecycle-direct.json)。真实 SSE 测试读取活动快照后关闭客户端流，并在结束时关闭本测试上下文的轮询执行器，避免数据库容器清理后仍尝试轮询；最终运行未再出现容器关闭引起的数据库连接异常。

新增超时夹具首次把创建时间与截止时间间隔设为 199 秒，被数据库 180 秒预算约束拒绝；改成 180 秒后重跑。这个失败是测试夹具错误，不放宽数据库约束，也不归因为生产超时缺陷。
