/**
 * 中文字典（默认 locale）。key → 文案。
 *
 * 这是"真源"：所有 key 都在这里定义中文原文（也是 t() 的兜底基准）。
 * en.ts 提供英文覆盖；缺失的 key 会回退到这里的中文。
 *
 * 覆盖范围：主界面 chrome（导航 / 标题 / 按钮 / 面板标题 / 设置分区 / 欢迎语等
 * 高频可见的框架文案）。深层内容（AI 输出、代码、错误详情、长说明）不在此范围。
 */
export const zh = {
  // ── 通用词 ──────────────────────────────────────────────
  "common.save": "保存",
  "common.cancel": "取消",
  "common.delete": "删除",
  "common.refresh": "刷新",
  "common.close": "关闭",
  "common.loading": "加载中…",
  "common.confirm": "确定",
  "common.ok": "确定",
  "common.retry": "重试",
  "common.open": "打开",
  "common.saving": "保存中…",

  // ── 标题栏 ──────────────────────────────────────────────
  "titlebar.noRepo": "未打开仓库",
  "titlebar.showEditor": "显示编辑器",
  "titlebar.hideEditor": "隐藏编辑器",
  "titlebar.showEditorTip": "显示中间编辑器区",
  "titlebar.hideEditorTip": "隐藏中间编辑器区（点文件或左侧视图会自动打开）",

  // ── 右侧引擎切换 Tab ────────────────────────────────────
  "engine.chat": "AI 会话",
  "engine.claude": "Claude Code",

  // ── ActivityBar 图标 tooltip ────────────────────────────
  "activity.explorer": "资源管理器",
  "activity.search": "搜索",
  "activity.terminal": "终端",
  "activity.showEditor": "显示编辑器",
  "activity.hideEditor": "隐藏编辑器",
  "activity.chat": "AI 会话（右侧面板）",
  "activity.settings": "设置",
  "activity.reorderHint": "（按住可上下拖动排序）",

  // ── ActivityBar 视图工具标题 ────────────────────────────
  "tool.graph": "数据流转",
  "tool.requirements": "需求流",
  "tool.agentruns": "Agent 执行",
  "tool.debt": "理解债务",
  "tool.provenance": "AI 贡献溯源",
  "tool.traceability": "Spec↔实现追溯",
  "tool.adr": "自动 ADR",
  "tool.sensitive": "敏感文件",
  "tool.mission": "Mission Control",
  "tool.timeline": "时间轴",
  "tool.drift": "架构漂移",
  "tool.branch": "方案分支",

  // ── 侧边栏 ──────────────────────────────────────────────
  "sidebar.explorer": "资源管理器",
  "sidebar.search": "搜索",

  // ── 仓库选择器（RepoPicker） ────────────────────────────
  "repo.label": "仓库",
  "repo.select": "选择仓库",
  "repo.openFolder": "打开文件夹…",
  "repo.gitUrl": "Git URL",
  "repo.deleteCurrent": "删除当前仓库",
  "repo.importFolderTip": "导入本地文件夹",
  "repo.importGitTip": "从 Git URL 导入",
  "repo.folderAbsPath": "文件夹绝对路径",
  "repo.gitAddress": "Git 仓库地址",
  "repo.branch": "分支",
  "repo.name": "仓库名称",
  "repo.openExisting": "打开已有",
  "repo.reindex": "重新索引",
  "repo.startImport": "开始导入",
  "repo.importing": "导入中…",

  // ── 状态栏 ──────────────────────────────────────────────
  "status.noRepo": "未打开仓库",
  "status.reindexing": "重建索引中…",
  "status.staleIndex": "索引已过期 · 点击重建",

  // ── AI 会话面板（Chat） ─────────────────────────────────
  "chat.title": "AI 会话",
  "chat.newChat": "新对话",
  "chat.history": "历史会话",
  "chat.memory": "长期记忆",
  "chat.localBadge": "🔒 本地",
  "chat.localBadgeTip": "所有推理走本地/内网，代码不出网",
  "chat.multiSolution": "多方案",
  "chat.realtime": "实时",
  "chat.send": "发送",
  "chat.thinking": "思考中…",
  "chat.noRepoTitle": "未打开仓库",
  "chat.noRepoSub": "导入一个仓库后即可开始 AI 会话",
  "chat.welcomeTitle": "✳ RepoLens Agent",
  "chat.welcomeSub": "自研 AI 内核 · 直接在你打开的文件夹里改 · git 可回溯",
  "chat.placeholderNoRepo": "请先导入并选择一个仓库",
  "chat.emptyHistory": "还没有历史会话——开始一段对话吧",
  "chat.untitledSession": "未命名会话",

  // ── Claude Code 面板 ────────────────────────────────────
  "claude.view": "视图",
  "claude.single": "单窗格",
  "claude.split": "双窗格",
  "claude.pickProject": "← 从左侧选择一个本地项目启动 Claude Code",

  // ── 设置页 ──────────────────────────────────────────────
  "settings.title": "设置",
  "settings.sectionLlm": "语言模型 (LLM)",
  "settings.sectionEmbedding": "向量 (Embedding)",
  "settings.sectionRules": "项目规则 (AGENTS.md)",
  "settings.sectionPrivacy": "隐私与出网",
  "settings.sectionVerify": "链路校验 & 零出网证明",
  "settings.sectionAppearance": "外观与语言",
  "settings.sectionAccount": "账号管理",
  "settings.testConnection": "测试连接",
  "settings.testing": "测试中…",
  "settings.save": "保存",
  "settings.savePrivacy": "保存模式",
  "settings.refreshRecords": "刷新记录",
  "settings.uiLanguage": "界面语言",
  "settings.uiLanguageDesc": "选择应用界面语言。",
  "settings.langZh": "简体中文",
  "settings.langEn": "English",
  "settings.changePassword": "修改密码",
  "settings.logout": "退出登录",
} as const;

export type MessageKey = keyof typeof zh;
