# 截图与视觉合同索引

## 公开资产

以下 9 张 PNG 是项目唯一视觉合同，均位于 `design/ai-prototypes/`。它们是设计资产，不包含运行账号、Cookie、真实学生数据、日志或本机路径。

| 文件 | 尺寸 | SHA-256 |
| --- | --- | --- |
| [AI Dashboard 桌面](../../design/ai-prototypes/ai-dashboard-desktop.png) | 1586x992 | `3A56B7F2C60EAC90C1286EB6B1794241760ED2366E9528F3B33C5C4BF66DA3D6` |
| [AI Assistant 桌面](../../design/ai-prototypes/ai-assistant-desktop.png) | 1586x992 | `38511AB2058E2E51FC76E4F37BD39D1E097E7C62DD874A6DFCC1C4188CAE1083` |
| [维修分诊桌面](../../design/ai-prototypes/ai-repair-triage-desktop.png) | 1586x992 | `C12FCC1D8DC74E6D27632DAD8B548D8CA4ABEFE93CCD3BDB5DEA327A500CA583` |
| [公告起草桌面](../../design/ai-prototypes/ai-notice-drafting-desktop.png) | 1536x1024 | `1EC33DF713BB7A5B2961A4F9F1CC7955E26490DC309FB76714FA921A35919778` |
| [风险中心桌面](../../design/ai-prototypes/ai-risk-center-desktop.png) | 1536x1024 | `678DCAC7299FF47CB370C80C124033594E5FB2164CD0BDD666E52B8C3AFFAEBB` |
| [审批与审计桌面](../../design/ai-prototypes/ai-approval-audit-desktop.png) | 1536x1024 | `BD0F36E37D76F09ADA109AC4D9E063F7C13E38FA9B3D34401CBCCDE9C81020C9` |
| [AI Dashboard 移动](../../design/ai-prototypes/ai-dashboard-mobile.png) | 852x1846 | `934FCF6B42466BA38050C0F78EA6161155EBD59DC88657D72CC009226CBAF0AF` |
| [AI Assistant 移动](../../design/ai-prototypes/ai-assistant-mobile.png) | 853x1844 | `35306E9A0F2661C93976EF98A5F6FEA1CB2B9DA52512040EAEAE366842BC6CCE` |
| [设计系统](../../design/ai-prototypes/ai-design-system.png) | 1505x1045 | `95C5D374FC9B6457E8BE2FF2959465831B16675F49FD21B6408AAA84D0FAB4B2` |

原型解释、共享壳层冲突收敛和页面合同见 [原型索引](../../design/ai-prototypes/README.md)。原型 PNG 不编辑、不重生成、不替换。

## 当前运行证据摘要

2026-08-11 历史 RC2 候选完成正式六视口视觉门：22 个受保护路由、132 路由截图、19 Assistant 状态、9 prototype capture、1 画廊、共 159 PNG；源码/原型稳定性违规、API/request/console/page/runtime errors 和业务写均为 0。视觉 manifest SHA-256 为 `9E31D3FD60F27A774D0BB7F7AF12F9954A1FC9DB5A749D21ABB663CAFD35DFEE`（原 E84… 是中间候选，不能作为 RC2 或本轮新验证）。

运行截图、trace、测试报告和原始 comparison 是本地验收材料，可能包含本机端口、合成会话标识或调试元数据，因此默认不进入公开仓库。公开结论只维护在 [AI 验证与验收](../../plan/ai-verification.md) 和 RC 发布说明中。

Stage 5 另在全新 clone 中人工复核 `1366x768` Dashboard、`390x844` Dashboard、`390x844` Assistant 和后端不可用登录态；这些截图仅进入忽略的本地证据目录，不进入公开仓库。

## 人工验收边界

自动 E2E、结构相似度、无溢出或视觉 manifest PASS 不等于质感验收。当前 UI 的人工结论来自相同视口下对 prototype/current/side-by-side/diff 的逐张复核与用户确认；后续任何页面、断点、原型或关键交互变化都必须重新生成当前证据。
