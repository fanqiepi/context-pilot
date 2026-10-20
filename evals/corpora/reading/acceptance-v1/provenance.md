# 阅读验收语料来源

2026-10-20。这里是评估输入，不是应用指令，不得把参考答案、未读事实和版本对照送进模型。

- `harbor-v1.txt`《雾港值班册》：本次为项目创作的十二章合成短篇，三段一章。不是已出版小说，不代表真实长篇的语义复杂度。
- `harbor-v2.txt`：同一故事的明确改写版，阿砾本名、值班册及结尾改变；两版属于同一 TEST 来源族，不能跨 DEBUG/TEST 分配。
- `island-debug-v1.txt`《小岛观测站》：独立创作的调试故事，人物、事件、来源族与 TEST 分离。通用回顾指令相同不算内容污染；不能据此宣称问题分布完全独立。
- `alice-opening-v1.txt`：Lewis Carroll（1832–1898）英文原作 *Alice's Adventures in Wonderland* 的开篇两段，无现代译文。来源为 [Project Gutenberg #11](https://www.gutenberg.org/ebooks/11)，原文件 [pg11.txt](https://www.gutenberg.org/cache/epub/11/pg11.txt)，电子版注明 2025-06-26 更新；2026-10-20 核对目录的 “Public domain in the USA”。原文属十九世纪已公开英文作品。这里只合并段内排版换行并增加数字章标题，没有添加后文。

Project Gutenberg 原电子版署名 Arthur DiBianca and David Widger。其使用说明要求遵守 [Project Gutenberg License](https://www.gutenberg.org/policy/license.html)，美国以外另按使用地规则核对；本来源记录不是对任意译本或电子版的许可。此处不使用 Project Gutenberg 商标推广产品，不提供商业书城。

数据集中每本书有文件 SHA-256，每段有正文 SHA-256 与版本限定的逻辑段号。逻辑 ID 不是数据库 UUID；执行时须从真实导入结果取得 bookId/versionId/paragraphId，并按章序、段序、hash 核对映射。不能把 v1 的数据库 ID 复用于 v2。

后文所有事实列为当前边界不可引用的候选，人工仍需识别隐含剧透、因果编造和版本混淆，不能仅用关键词匹配评分。已知作品测试明确标注外部先验诱因，不把参考禁用事实当作可用证据。
