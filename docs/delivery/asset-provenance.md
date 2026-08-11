# 公开资产与数据来源说明

本说明只覆盖首次公开仓库中的静态资产和小型评测数据，不替代根目录 `LICENSE` 或第三方依赖各自的许可证。

## 视觉资产

| 范围 | 数量 | 公开结论 |
| --- | ---: | --- |
| `design/ai-prototypes/*.png` | 9 | 项目最终视觉合同；用户已授权本次公开发布。文件可读、尺寸与索引一致，未发现文本、XMP、EXIF、作者、软件或本机路径元数据。历史 `candidates/` 不公开。 |
| `frontend/src/assets/visual-evidence/*.png` | 2 | 合成维修状态示意图，不含照片、真实身份、地点或第三方可识别素材；未发现文本或 EXIF 元数据。 |
| `frontend/public/favicon.svg` | 1 | 本项目为首次公开发布新绘制的宿舍楼图标，只使用基础几何图形和项目色。 |

脚手架残留的 Vite favicon 已替换；未使用且包含第三方品牌标志的 `frontend/public/icons.svg` 已删除。公开仓库不包含下载字体、图库、照片、视频或音频。

## 字体与图标

页面只声明 `Inter`、`PingFang SC`、`Microsoft YaHei`、`system-ui` 和通用等宽字体回退，不随仓库分发字体文件。运行时组件图标来自已声明的 npm 依赖，不把第三方 SVG sprite 复制为项目资产。

## 评测数据

`backend/src/main/resources/ai/eval/*.jsonl` 是项目编写的小型合成评测与安全红队样本：

- 不来自学生、学校、供应商或公开数据集；
- 不包含真实姓名、手机号、身份证、账号或业务记录；
- 安全敏感形态只用于验证拒绝、脱敏和越权阻断；
- 每行均为有效 JSON，manifest 记录版本、hash、分类和许可证标识。

这些合成评测数据随根目录 `LICENSE` 采用 `Apache-2.0`；manifest 中的许可证标识已同步为 `Apache-2.0`。

## 不公开的证据

`.planning/`、运行截图、Playwright trace/video/report、日志、数据库、构建目录、JAR、前端 dist 和本机路径证据不属于公开仓库或 GitHub Release 资产。公开材料只保留脱敏结论和 SHA-256。
