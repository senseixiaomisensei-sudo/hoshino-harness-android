package app.hoshino.harness.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 纯本地模拟的内核：不联网、不调用任何模型。
 * 发送消息后按脚本产生全部消息组件（思考、工具、终端、diff、审批、提问、子代理、Todo、Goal、定时任务、Markdown、用量），
 * 用于开发 UI 和演示。接入 dsh 后整体替换为 DshHarnessClient（见 README）。
 */
class FakeHarnessClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : HarnessClient {

    private val ids = AtomicInteger(0)
    private fun nid(prefix: String) = "$prefix-${ids.incrementAndGet()}"
    private fun now() = System.currentTimeMillis()

    private val _events = MutableSharedFlow<HarnessEvent>(extraBufferCapacity = 512)
    override val events: SharedFlow<HarnessEvent> = _events.asSharedFlow()
    private suspend fun emit(e: HarnessEvent) = _events.emit(e)

    private val _connection = MutableStateFlow(ConnectionState.Connecting)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _workspaces = MutableStateFlow(
        listOf(
            Workspace("ws-default", "默认工作区", "/data/data/app.hoshino.harness/files/workspace"),
            Workspace("ws-notes", "notes", "/storage/emulated/0/Documents/notes"),
        ),
    )
    override val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    override val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    private val runs = ConcurrentHashMap<String, Job>()
    private val turnIds = ConcurrentHashMap<String, String>()
    private val turnCount = ConcurrentHashMap<String, Int>()
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<ApprovalDecision>>()
    private val sessionAllowAll = ConcurrentHashMap<String, Boolean>()
    private val questions = ConcurrentHashMap<String, CompletableDeferred<String>>()

    init {
        scope.launch {
            delay(600)
            _connection.value = ConnectionState.Connected
        }
    }

    // ---------------- 会话 ----------------

    override suspend fun addWorkspace(name: String, path: String): Workspace {
        val ws = Workspace(nid("ws"), name, path)
        _workspaces.update { it + ws }
        return ws
    }

    override suspend fun createSession(workspaceId: String, presetId: String, tier: PermissionTier): SessionInfo {
        val s = SessionInfo(nid("s"), "新会话", workspaceId, presetId, tier, now(), now())
        _sessions.update { listOf(s) + it }
        return s
    }

    private fun updateSession(id: String, block: (SessionInfo) -> SessionInfo) =
        _sessions.update { list -> list.map { if (it.id == id) block(it) else it } }

    override suspend fun renameSession(sessionId: String, title: String) = updateSession(sessionId) { it.copy(title = title, updatedAt = now()) }

    override suspend fun forkSession(sessionId: String): SessionInfo {
        val src = _sessions.value.first { it.id == sessionId }
        val s = src.copy(id = nid("s"), title = "${src.title}（分叉）", createdAt = now(), updatedAt = now(), running = false, parentId = src.id)
        _sessions.update { listOf(s) + it }
        return s
    }

    override suspend fun exportSession(sessionId: String): String {
        delay(400)
        return "/storage/emulated/0/Download/Hoshino/session-$sessionId.zip"
    }

    override suspend fun setArchived(sessionId: String, archived: Boolean) = updateSession(sessionId) { it.copy(archived = archived) }

    override suspend fun deleteSession(sessionId: String) {
        runs.remove(sessionId)?.cancel()
        _sessions.update { list -> list.filterNot { it.id == sessionId } }
    }

    override suspend fun setSessionTier(sessionId: String, tier: PermissionTier) = updateSession(sessionId) { it.copy(tier = tier) }

    override suspend fun setSessionPreset(sessionId: String, presetId: String) = updateSession(sessionId) { it.copy(presetId = presetId) }

    private val demoFiles = listOf(
        "README.md", "AGENTS.md", "package.json", "src/index.ts", "src/agent/loop.ts", "src/agent/tools.ts",
        "src/ui/App.tsx", "app/build.gradle.kts", "app/src/main/AndroidManifest.xml",
        "app/src/main/java/app/hoshino/harness/MainActivity.kt", "docs/architecture.md", "scripts/release.sh",
    )

    override suspend fun searchWorkspaceFiles(workspaceId: String, query: String): List<String> =
        demoFiles.filter { it.contains(query, ignoreCase = true) }.take(8)

    // ---------------- 运行 ----------------

    override suspend fun send(sessionId: String, text: String, attachments: List<Attachment>, options: TurnOptions) {
        if (runs[sessionId]?.isActive == true) return
        val turnId = nid("turn")
        turnIds[sessionId] = turnId
        val n = (turnCount[sessionId] ?: 0) + 1
        turnCount[sessionId] = n
        val session = _sessions.value.firstOrNull { it.id == sessionId }
        if (n == 1 && session != null && session.title == "新会话") {
            renameSession(sessionId, text.trim().lineSequence().first().take(20).ifBlank { "新会话" })
        }
        updateSession(sessionId) { it.copy(running = true, updatedAt = now()) }
        val job = scope.launch {
            val started = now()
            emit(HarnessEvent.TurnStarted(sessionId, turnId))
            if (n == 1) {
                emit(HarnessEvent.SystemPrompt(sessionId, nid("sys"), "preset=${options.presetId}\npermission=${options.tier.key}\n你是 Hoshino Harness 中的编码 Agent……"))
                emit(HarnessEvent.ContextInjected(sessionId, nid("ctx"), "AGENTS.md", "# 项目约定\n- 使用 Kotlin + Compose\n- 提交前运行 ./gradlew check"))
            }
            emit(HarnessEvent.UserMessage(sessionId, nid("u"), text, attachments))
            val cmd = text.trim()
            when {
                cmd.startsWith("/goal") -> goalTurn(sessionId, cmd.removePrefix("/goal").trim())
                cmd.startsWith("/plan") -> planTurn(sessionId, cmd.removePrefix("/plan").trim())
                cmd.startsWith("/compact") -> compactTurn(sessionId)
                cmd.startsWith("/schedule") -> scheduleTurn(sessionId, cmd.removePrefix("/schedule").trim())
                n == 1 -> fullDemoTurn(sessionId, text, options)
                else -> shortTurn(sessionId, text, options)
            }
            val dur = now() - started
            emit(HarnessEvent.UsageReported(sessionId, turnId, Usage(8_000 + n * 2_100, 900 + n * 120, 5_200 * n, 640, dur)))
            emit(
                HarnessEvent.ContextUsageChanged(
                    sessionId,
                    ContextUsage(
                        usedTokens = (14_000 + n * 9_500).coerceAtMost(128_000),
                        limitTokens = 128_000,
                        breakdown = listOf(
                            "系统提示" to 3_200, "工具定义" to 5_400,
                            "消息" to 4_000 + n * 7_000, "文件与结果" to 1_400 + n * 2_500,
                        ),
                    ),
                ),
            )
            emit(HarnessEvent.TurnFinished(sessionId, turnId, cancelled = false))
            updateSession(sessionId) { it.copy(running = false, updatedAt = now()) }
        }
        runs[sessionId] = job
    }

    override suspend fun steer(sessionId: String, text: String) {
        emit(HarnessEvent.UserMessage(sessionId, nid("u"), text, emptyList(), steer = true))
        emit(HarnessEvent.ContextInjected(sessionId, nid("ctx"), "steer", text))
    }

    override suspend fun stop(sessionId: String) {
        val job = runs.remove(sessionId) ?: return
        if (!job.isActive) return
        job.cancel()
        emit(HarnessEvent.TurnFinished(sessionId, turnIds[sessionId] ?: "turn", cancelled = true))
        updateSession(sessionId) { it.copy(running = false) }
    }

    override suspend fun respondApproval(sessionId: String, approvalId: String, decision: ApprovalDecision) {
        if (decision == ApprovalDecision.AllowSession) sessionAllowAll[sessionId] = true
        emit(HarnessEvent.ApprovalResolved(sessionId, approvalId, decision))
        approvals.remove(approvalId)?.complete(decision)
    }

    override suspend fun answerQuestion(sessionId: String, questionId: String, answer: String) {
        emit(HarnessEvent.QuestionAnswered(sessionId, questionId, answer))
        questions.remove(questionId)?.complete(answer)
    }

    override suspend fun resolveDiff(sessionId: String, diffId: String, accept: Boolean) {
        emit(HarnessEvent.DiffResolved(sessionId, diffId, if (accept) DiffState.Accepted else DiffState.Reverted))
    }

    override suspend fun compact(sessionId: String) {
        emit(HarnessEvent.Notice(sessionId, nid("n"), "上下文已压缩：保留摘要与最近 6 轮"))
        emit(
            HarnessEvent.ContextUsageChanged(
                sessionId,
                ContextUsage(11_800, 128_000, listOf("系统提示" to 3_200, "工具定义" to 5_400, "摘要" to 2_100, "消息" to 1_100)),
            ),
        )
    }

    override val slashCommands: StateFlow<List<SlashCommand>> = MutableStateFlow(
        listOf(
            SlashCommand("/goal", "设定长期目标，Agent 会跨会话持续推进"),
            SlashCommand("/plan", "进入规划协作：先出计划，确认后再执行"),
            SlashCommand("/compact", "压缩上下文，保留摘要"),
            SlashCommand("/permission", "切换本会话权限档"),
            SlashCommand("/export", "导出当前会话为 ZIP"),
            SlashCommand("/feedback", "反馈问题或建议"),
            SlashCommand("/skills", "管理 Skills", "skills 插件"),
            SlashCommand("/mcp", "管理 MCP 服务器", "mcp 插件"),
            SlashCommand("/schedule", "创建定时任务", "scheduler 插件"),
        ),
    ).asStateFlow()

    // ---------------- 演示脚本 ----------------

    private suspend fun stream(sessionId: String, id: String, text: String, thinking: Boolean, chunk: Int = 4, pause: Long = 22) {
        var i = 0
        while (i < text.length) {
            val end = (i + chunk).coerceAtMost(text.length)
            val delta = text.substring(i, end)
            emit(if (thinking) HarnessEvent.ThinkingDelta(sessionId, id, delta) else HarnessEvent.AssistantDelta(sessionId, id, delta))
            i = end
            delay(pause)
        }
        if (!thinking) emit(HarnessEvent.AssistantDone(sessionId, id))
    }

    private suspend fun think(sessionId: String, text: String) {
        val id = nid("think")
        val t0 = now()
        stream(sessionId, id, text, thinking = true, chunk = 3, pause = 45)
        emit(HarnessEvent.ThinkingDone(sessionId, id, now() - t0))
    }

    private suspend fun tool(sessionId: String, name: String, summary: String, input: String, output: String, status: ToolStatus = ToolStatus.Success) {
        val id = nid("tool")
        emit(HarnessEvent.ToolCallStarted(sessionId, id, name, summary, input, ToolKind.Generic))
        delay(700)
        if (output.isNotEmpty()) emit(HarnessEvent.ToolCallOutput(sessionId, id, output))
        emit(HarnessEvent.ToolCallFinished(sessionId, id, status))
    }

    private suspend fun bash(sessionId: String, command: String, outputLines: List<String>, auto: Boolean, exit: Int = 0) {
        val id = nid("bash")
        emit(HarnessEvent.ToolCallStarted(sessionId, id, "bash", command, "{\"command\": \"$command\"}", ToolKind.Bash, autoApproved = auto))
        for (l in outputLines) {
            delay(140)
            emit(HarnessEvent.ToolCallOutput(sessionId, id, l + "\n"))
        }
        emit(HarnessEvent.ToolCallFinished(sessionId, id, if (exit == 0) ToolStatus.Success else ToolStatus.Failed, exit))
    }

    private suspend fun requestApproval(sessionId: String, title: String, detail: String, command: String): ApprovalDecision {
        if (sessionAllowAll[sessionId] == true) return ApprovalDecision.AllowSession
        val id = nid("appr")
        val d = CompletableDeferred<ApprovalDecision>()
        approvals[id] = d
        emit(HarnessEvent.ApprovalRequested(sessionId, id, title, detail, command))
        return d.await()
    }

    private val planItems = listOf("阅读项目结构", "定位相关代码", "修改实现", "运行检查", "总结与交付")

    private suspend fun todos(sessionId: String, id: String, done: Int) {
        emit(
            HarnessEvent.TodosUpdated(
                sessionId, id, "执行计划",
                planItems.mapIndexed { i, t -> TodoItem("td$i", t, done = i < done, active = i == done) },
            ),
        )
    }

    private suspend fun fullDemoTurn(sessionId: String, text: String, o: TurnOptions) {
        think(sessionId, "用户的需求是「${text.take(24)}」。先读一下项目结构和约定文件，确认构建方式，然后列一个可执行的计划，改动前先征求确认。")
        val todoId = nid("todo")
        todos(sessionId, todoId, 0)
        delay(300)
        tool(
            sessionId, "read_file", "AGENTS.md",
            "{\n  \"path\": \"AGENTS.md\",\n  \"offset\": 0,\n  \"limit\": 200\n}",
            "# 项目约定\n- Kotlin 2.x + Jetpack Compose\n- 提交前运行 ./gradlew check\n- UI 文案使用简体中文",
        )
        todos(sessionId, todoId, 1)
        tool(
            sessionId, "grep", "\"HarnessClient\" in app/src",
            "{\n  \"pattern\": \"HarnessClient\",\n  \"path\": \"app/src\",\n  \"glob\": \"*.kt\"\n}",
            "app/src/main/java/app/hoshino/harness/core/HarnessClient.kt:16\napp/src/main/java/app/hoshino/harness/core/FakeHarnessClient.kt:23",
        )
        val full = o.tier == PermissionTier.FullAccess
        bash(
            sessionId, "ls -la app/src/main",
            listOf(
                "total 24", "drwxr-xr-x  5 u0_a321 u0_a321 4096 10-23 21:40 .",
                "-rw-r--r--  1 u0_a321 u0_a321 1874 10-23 21:40 AndroidManifest.xml",
                "drwxr-xr-x  3 u0_a321 u0_a321 4096 10-23 21:40 java", "drwxr-xr-x 14 u0_a321 u0_a321 4096 10-23 21:40 res",
            ),
            auto = full,
        )
        todos(sessionId, todoId, 2)

        // 写操作：按权限档走不同分支
        val riskyCmd = "./gradlew clean :app:assembleDebug"
        when (o.tier) {
            PermissionTier.ReadOnly -> {
                val id = nid("tool")
                emit(HarnessEvent.ToolCallStarted(sessionId, id, "str_replace_editor", "app/src/main/res/values/strings.xml", "{\n  \"command\": \"str_replace\",\n  \"path\": \"app/src/main/res/values/strings.xml\"\n}", ToolKind.Edit))
                delay(300)
                emit(HarnessEvent.ToolCallOutput(sessionId, id, "只读模式：写操作已被拒绝"))
                emit(HarnessEvent.ToolCallFinished(sessionId, id, ToolStatus.Denied))
            }
            PermissionTier.WorkspaceWrite -> {
                val d = requestApproval(sessionId, "运行构建命令", "将清理并重新构建 Debug 包，会删除 build/ 目录。", riskyCmd)
                if (d == ApprovalDecision.Deny) {
                    val id = nid("bash")
                    emit(HarnessEvent.ToolCallStarted(sessionId, id, "bash", riskyCmd, "{\"command\": \"$riskyCmd\"}", ToolKind.Bash))
                    emit(HarnessEvent.ToolCallFinished(sessionId, id, ToolStatus.Denied))
                } else {
                    bash(sessionId, riskyCmd, listOf("> Task :app:clean", "> Task :app:compileDebugKotlin", "> Task :app:assembleDebug", "", "BUILD SUCCESSFUL in 41s"), auto = false)
                }
                diff(sessionId)
            }
            PermissionTier.FullAccess -> {
                bash(sessionId, riskyCmd, listOf("> Task :app:clean", "> Task :app:compileDebugKotlin", "> Task :app:assembleDebug", "", "BUILD SUCCESSFUL in 39s"), auto = true)
                diff(sessionId)
            }
        }

        // 子代理 / workflow
        val wfId = nid("wf")
        val names = listOf("explorer" to "梳理模块依赖", "reviewer" to "审查改动风险", "tester" to "生成测试用例")
        for (step in 0..4) {
            val tasks = names.mapIndexed { i, (agent, title) ->
                val p = ((step - i * 0.6f) / 3f).coerceIn(0f, 1f)
                val st = when {
                    p >= 1f -> TaskStatus.Done
                    p > 0f -> TaskStatus.Running
                    else -> TaskStatus.Pending
                }
                SubTask(
                    "sub$i", title, agent, st, p,
                    log = listOf("启动子代理 $agent", "读取 6 个文件", if (st == TaskStatus.Done) "完成：输出 3 条结论" else "进行中…"),
                )
            }
            emit(HarnessEvent.SubagentsUpdated(sessionId, wfId, "Workflow · 并行子任务", true, tasks))
            delay(650)
        }
        todos(sessionId, todoId, 3)

        // ask_user_question
        val qid = nid("q")
        val answer = CompletableDeferred<String>()
        questions[qid] = answer
        emit(
            HarnessEvent.QuestionAsked(
                sessionId, qid, "验证方式", "改动已完成，接下来如何验证？",
                listOf(
                    QuestionOption("a", "运行单元测试", "./gradlew testDebugUnitTest，约 1 分钟", recommended = true),
                    QuestionOption("b", "只做静态检查", "lint + detekt"),
                    QuestionOption("c", "跳过验证", "直接总结"),
                ),
                allowFreeText = true,
            ),
        )
        val picked = answer.await()
        think(sessionId, "用户选择：$picked。按此执行并收尾。")
        todos(sessionId, todoId, 5)

        emit(
            HarnessEvent.GoalUpdated(
                sessionId, nid("goal"),
                Goal(nid("g"), "完成 Hoshino Harness M0 联调", "UI 与 dsh bridge 打通，跑通一次完整会话", GoalStatus.Active, 0.35f, now()),
            ),
        )
        emit(
            HarnessEvent.ScheduleCreated(
                sessionId, nid("sch"),
                Schedule(nid("schd"), "每日构建巡检", "每天检查依赖更新并跑一次构建", Repeat.Daily, "每天 09:00", "Asia/Shanghai", now() + 36_000_000L, true),
            ),
        )
        val md = """
## 已完成

按计划处理了「${text.take(18)}」，要点如下：

1. **结构确认**：读取 `AGENTS.md`，构建命令为 `./gradlew check`
2. **实现修改**：更新了 `strings.xml` 中的文案，并补充深色主题
3. **验证**：$picked

```kotlin
val client: HarnessClient = FakeHarnessClient()
client.send(sessionId, "你好", emptyList(), options)
```

| 项目 | 状态 |
|---|---|
| 构建 | 通过 |
| 子代理 | 3/3 完成 |
| 定时巡检 | 已创建 |

如需继续，可以直接说下一步，或用 `/goal` 设定长期目标。
""".trimIndent()
        stream(sessionId, nid("a"), md, thinking = false)
    }

    private suspend fun diff(sessionId: String) {
        val tid = nid("tool")
        emit(HarnessEvent.ToolCallStarted(sessionId, tid, "str_replace_editor", "app/src/main/res/values/strings.xml", "{\n  \"command\": \"str_replace\",\n  \"path\": \"app/src/main/res/values/strings.xml\",\n  \"old_str\": \"新对话\",\n  \"new_str\": \"新会话\"\n}", ToolKind.Edit))
        delay(400)
        emit(HarnessEvent.ToolCallOutput(sessionId, tid, "已修改 1 处"))
        emit(HarnessEvent.ToolCallFinished(sessionId, tid, ToolStatus.Success))
        emit(
            HarnessEvent.FileDiff(
                sessionId, nid("diff"), "app/src/main/res/values/strings.xml",
                listOf(
                    DiffLine(DiffLineType.Hunk, "@@ -12,6 +12,7 @@"),
                    DiffLine(DiffLineType.Context, "    <string name=\"app_name\">Hoshino Harness</string>", 12, 12),
                    DiffLine(DiffLineType.Remove, "    <string name=\"new_chat\">新对话</string>", 13, null),
                    DiffLine(DiffLineType.Add, "    <string name=\"new_chat\">新会话</string>", null, 13),
                    DiffLine(DiffLineType.Add, "    <string name=\"hero_title\">今天想完成什么？</string>", null, 14),
                    DiffLine(DiffLineType.Context, "    <string name=\"settings\">设置</string>", 14, 15),
                ),
            ),
        )
    }

    private suspend fun shortTurn(sessionId: String, text: String, o: TurnOptions) {
        think(sessionId, "这是后续追问，结合上文直接处理。")
        tool(sessionId, "file_search", "\"${text.take(12)}\"", "{\n  \"query\": \"${text.take(12)}\"\n}", "找到 2 个相关位置")
        if (o.planMode) todos(sessionId, nid("todo"), 1)
        stream(sessionId, nid("a"), "好的。我已根据「${text.take(20)}」更新了实现。\n\n- 改动集中在 `core/` 目录\n- 未引入新依赖\n\n需要我再跑一遍检查吗？", thinking = false)
    }

    private suspend fun goalTurn(sessionId: String, title: String) {
        think(sessionId, "设定一个长期目标，并拆成可跟踪的里程碑。")
        val g = Goal(nid("g"), title.ifBlank { "持续改进项目质量" }, "Agent 会在后续会话中持续推进并汇报进度", GoalStatus.Active, 0f, now())
        _goals.update { listOf(g) + it }
        emit(HarnessEvent.GoalUpdated(sessionId, nid("goal"), g))
        stream(sessionId, nid("a"), "已设定长期目标 **${g.title}**。可在侧栏「长期目标」中暂停或编辑。", thinking = false)
    }

    private suspend fun planTurn(sessionId: String, task: String) {
        think(sessionId, "规划协作模式：先给出计划，等待确认后再执行。")
        val id = nid("todo")
        emit(
            HarnessEvent.TodosUpdated(
                sessionId, id, "计划草案",
                listOf("明确目标与验收标准", "调研现有实现", "拆分任务并估时", "实施", "验证与回顾").mapIndexed { i, t -> TodoItem("p$i", t, false, i == 0) },
            ),
        )
        stream(sessionId, nid("a"), "以上是「${task.ifBlank { "当前任务" }}」的计划草案。确认后我再开始执行，也可以直接修改条目。", thinking = false)
    }

    private suspend fun compactTurn(sessionId: String) {
        compact(sessionId)
        stream(sessionId, nid("a"), "上下文已压缩，后续对话会基于摘要继续。", thinking = false)
    }

    private suspend fun scheduleTurn(sessionId: String, what: String) {
        val s = Schedule(nid("schd"), what.ifBlank { "每日摘要" }, what.ifBlank { "汇总昨天的会话与待办" }, Repeat.Daily, "每天 09:00", "Asia/Shanghai", now() + 3_600_000L, true)
        _schedules.update { listOf(s) + it }
        emit(HarnessEvent.ScheduleCreated(sessionId, nid("sch"), s))
        stream(sessionId, nid("a"), "已创建定时任务 **${s.title}**。", thinking = false)
    }

    // ---------------- 模型供应商 ----------------

    override val catalog: List<CatalogProvider> = listOf(
        CatalogProvider("anthropic", "Anthropic Claude", ProviderProtocol.Anthropic, "https://api.anthropic.com"),
        CatalogProvider("openai", "OpenAI", ProviderProtocol.OpenAIResponses, "https://api.openai.com/v1"),
        CatalogProvider("deepseek", "DeepSeek", ProviderProtocol.OpenAICompatible, "https://api.deepseek.com"),
        CatalogProvider("openrouter", "OpenRouter", ProviderProtocol.OpenAICompatible, "https://openrouter.ai/api/v1"),
        CatalogProvider("gemini", "Google Gemini", ProviderProtocol.OpenAICompatible, "https://generativelanguage.googleapis.com/v1beta/openai"),
        CatalogProvider("moonshot", "Moonshot", ProviderProtocol.OpenAICompatible, "https://api.moonshot.cn/v1"),
        CatalogProvider("zhipu", "智谱", ProviderProtocol.OpenAICompatible, "https://open.bigmodel.cn/api/paas/v4"),
        CatalogProvider("siliconflow", "硅基流动", ProviderProtocol.OpenAICompatible, "https://api.siliconflow.cn/v1"),
        CatalogProvider("ollama", "Ollama 本地", ProviderProtocol.OpenAICompatible, "http://127.0.0.1:11434/v1"),
        CatalogProvider("custom", "自定义", ProviderProtocol.OpenAICompatible, ""),
    )

    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    override val providers: StateFlow<List<Provider>> = _providers.asStateFlow()
    private val _routing = MutableStateFlow(ModelRouting())
    override val routing: StateFlow<ModelRouting> = _routing.asStateFlow()

    override suspend fun upsertProvider(provider: Provider) {
        _providers.update { list ->
            if (list.any { it.id == provider.id }) list.map { if (it.id == provider.id) provider else it } else list + provider
        }
        // 首个模型自动成为主对话模型
        val r = _routing.value
        val first = provider.models.firstOrNull()
        if (r.main == null && first != null) {
            val ref = ModelRef(provider.id, first.id)
            _routing.value = ModelRouting(ref, ref, ref, provider.models.firstOrNull { it.vision }?.let { ModelRef(provider.id, it.id) })
        }
    }

    override suspend fun deleteProvider(providerId: String) {
        _providers.update { list -> list.filterNot { it.id == providerId } }
        _routing.update { r ->
            fun ModelRef?.keep() = if (this?.providerId == providerId) null else this
            ModelRouting(r.main.keep(), r.subagent.keep(), r.compact.keep(), r.vision.keep())
        }
    }

    override suspend fun setProviderEnabled(providerId: String, enabled: Boolean) =
        _providers.update { list -> list.map { if (it.id == providerId) it.copy(enabled = enabled) else it } }

    override suspend fun testProvider(provider: Provider): Result<String> {
        delay(900)
        return if (provider.baseUrl.isBlank()) Result.failure<String>(IllegalArgumentException("Base URL 不能为空"))
        else if (provider.apiKey.isBlank() && provider.catalogId != "ollama") Result.failure<String>(IllegalArgumentException("缺少 API Key"))
        else Result.success("连接成功 · 延迟 ${180 + provider.name.length * 7} ms（模拟）")
    }

    override suspend fun fetchModels(provider: Provider): List<ModelInfo> {
        delay(800)
        fun m(id: String, name: String, ctx: Int, out: Int, vision: Boolean, reasoning: Boolean, e: ReasoningEffort) =
            ModelInfo(id, name, ctx, out, vision, reasoning, e)
        return when (provider.catalogId) {
            "anthropic" -> listOf(
                m("claude-sonnet-4-5", "Claude Sonnet 4.5", 200_000, 64_000, true, true, ReasoningEffort.Medium),
                m("claude-opus-4-1", "Claude Opus 4.1", 200_000, 32_000, true, true, ReasoningEffort.High),
                m("claude-haiku-4-5", "Claude Haiku 4.5", 200_000, 64_000, true, true, ReasoningEffort.Off),
            )
            "openai" -> listOf(
                m("gpt-5", "GPT-5", 400_000, 128_000, true, true, ReasoningEffort.Medium),
                m("gpt-5-mini", "GPT-5 mini", 400_000, 128_000, true, true, ReasoningEffort.Low),
            )
            "deepseek" -> listOf(
                m("deepseek-chat", "deepseek-chat", 128_000, 8_000, false, false, ReasoningEffort.Off),
                m("deepseek-reasoner", "deepseek-reasoner", 128_000, 64_000, false, true, ReasoningEffort.High),
            )
            "gemini" -> listOf(m("gemini-2.5-pro", "Gemini 2.5 Pro", 1_000_000, 64_000, true, true, ReasoningEffort.Medium))
            "moonshot" -> listOf(m("kimi-k2", "Kimi K2", 128_000, 16_000, false, false, ReasoningEffort.Off))
            "zhipu" -> listOf(m("glm-4.6", "GLM-4.6", 200_000, 128_000, false, true, ReasoningEffort.Medium))
            "ollama" -> listOf(m("qwen3:8b", "Qwen3 8B（本地）", 32_000, 8_000, false, true, ReasoningEffort.Low))
            else -> listOf(m("model-a", "Model A", 128_000, 8_000, false, false, ReasoningEffort.Off))
        }
    }

    override suspend fun setRouting(routing: ModelRouting) {
        _routing.value = routing
    }

    // ---------------- 预设 ----------------

    private val standardTools = listOf("文件编辑", "shell", "文件搜索", "网页搜索", "skills", "规划", "goals", "子代理", "workflow")

    private val _presets = MutableStateFlow(
        listOf(
            AgentPreset("standard", "Standard", "完整编码 Agent：文件编辑、shell、文件/网页搜索、skills、规划、goals、子代理、workflow", true, "", standardTools, null, PermissionTier.WorkspaceWrite),
            AgentPreset("ptc", "PTC", "模型写一段 TypeScript 程序编排多步工具调用", true, "", listOf("ts_program", "文件编辑", "shell"), null, PermissionTier.WorkspaceWrite),
            AgentPreset("minimal", "Minimal", "只有两个工具：持久 bash + str_replace_editor", true, "", listOf("bash", "str_replace_editor"), null, PermissionTier.WorkspaceWrite),
            AgentPreset("creator", "Creator", "Standard + 运行时检查、内存插件实验、预设编写", true, "", standardTools + listOf("runtime_inspect", "memory_lab", "preset_author"), null, PermissionTier.WorkspaceWrite),
        ),
    )
    override val presets: StateFlow<List<AgentPreset>> = _presets.asStateFlow()

    override suspend fun upsertPreset(preset: AgentPreset) = _presets.update { list ->
        if (list.any { it.id == preset.id }) list.map { if (it.id == preset.id) preset else it } else list + preset
    }

    override suspend fun deletePreset(presetId: String) = _presets.update { list -> list.filterNot { it.id == presetId && !it.builtIn } }

    // ---------------- 插件 / Skills / MCP / Hooks ----------------

    private val _plugins = MutableStateFlow(
        listOf(
            Plugin("p1", "Anthropic 协议", "@dsh/provider-anthropic", "0.9.4", true, PluginSource.BuiltIn, PluginCategory.Model, "Anthropic Messages API 适配"),
            Plugin("p2", "OpenAI 协议", "@dsh/provider-openai", "0.9.4", true, PluginSource.BuiltIn, PluginCategory.Model, "OpenAI Chat / Responses 适配"),
            Plugin("p3", "Shell 工具", "@dsh/tool-shell", "0.9.4", true, PluginSource.BuiltIn, PluginCategory.Tool, "持久 bash 会话"),
            Plugin("p4", "网页搜索", "@dsh/tool-websearch", "0.9.2", true, PluginSource.BuiltIn, PluginCategory.Tool, "多引擎网页搜索"),
            Plugin("p5", "会话分叉", "@dsh/session-fork", "0.9.0", true, PluginSource.BuiltIn, PluginCategory.Session, "分叉与回放"),
            Plugin("p6", "proot 沙箱", "@hoshino/sandbox-proot", "0.3.1", true, PluginSource.BuiltIn, PluginCategory.Sandbox, "Alpine rootfs + proot"),
            Plugin("p7", "SQLite 存储", "@dsh/storage-sqlite", "0.9.4", true, PluginSource.BuiltIn, PluginCategory.Storage, "会话与轨迹持久化"),
            Plugin("p8", "调度器", "@dsh/scheduler", "0.8.7", true, PluginSource.BuiltIn, PluginCategory.Schedule, "定时任务与 Goals"),
            Plugin("p9", "Skills", "@dsh/skills", "0.9.1", true, PluginSource.BuiltIn, PluginCategory.Tool, "SKILL.md 技能加载"),
            Plugin("p10", "MCP 客户端", "@dsh/mcp", "0.9.3", true, PluginSource.BuiltIn, PluginCategory.Tool, "stdio / HTTP MCP"),
            Plugin("p11", "Trajectory 视图", "@dsh/ui-trajectory", "0.4.0", false, PluginSource.BuiltIn, PluginCategory.UI, "事件流可视化"),
        ),
    )
    override val plugins: StateFlow<List<Plugin>> = _plugins.asStateFlow()
    private val _restart = MutableStateFlow(false)
    override val restartRequired: StateFlow<Boolean> = _restart.asStateFlow()
    private val pluginConfigs = ConcurrentHashMap<String, String>()

    override suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        _plugins.update { l -> l.map { if (it.id == pluginId) it.copy(enabled = enabled) else it } }
        _restart.value = true
    }

    override suspend fun installPluginFromGit(spec: String): Result<Plugin> {
        delay(1200)
        val m = Regex("^github:([\\w.-]+)/([\\w.-]+)(#[\\w./-]+)?$").find(spec.trim())
            ?: return Result.failure<Plugin>(IllegalArgumentException("格式应为 github:owner/repo#ref"))
        val repo = m.groupValues[2]
        val ref = m.groupValues[3].removePrefix("#").ifBlank { "main" }
        val p = Plugin(nid("p"), repo, "${m.groupValues[1]}/$repo", ref, true, PluginSource.Git, PluginCategory.Tool, "从 $spec 安装")
        _plugins.update { it + p }
        _restart.value = true
        return Result.success(p)
    }

    override suspend fun linkLocalPlugin(path: String): Result<Plugin> {
        delay(400)
        if (path.isBlank()) return Result.failure<Plugin>(IllegalArgumentException("路径不能为空"))
        val name = path.trimEnd('/').substringAfterLast('/')
        val p = Plugin(nid("p"), name, "local:$name", "dev", true, PluginSource.Local, PluginCategory.Tool, path)
        _plugins.update { it + p }
        _restart.value = true
        return Result.success(p)
    }

    override suspend fun removePlugin(pluginId: String) {
        _plugins.update { l -> l.filterNot { it.id == pluginId } }
        _restart.value = true
    }

    override suspend fun readPluginConfig(pluginId: String): String =
        pluginConfigs[pluginId] ?: "{\n  \"enabled\": true,\n  \"options\": {}\n}"

    override suspend fun writePluginConfig(pluginId: String, content: String) {
        pluginConfigs[pluginId] = content
        _restart.value = true
    }

    private val _skills = MutableStateFlow(
        listOf(
            Skill("sk1", "android-build", "Gradle 构建、签名与常见错误排查", true, "内置", "---\nname: android-build\ndescription: Gradle 构建、签名与常见错误排查\n---\n\n# Android 构建\n\n1. 先运行 `./gradlew tasks` 确认任务\n2. 构建失败时读取最后 80 行日志"),
            Skill("sk2", "pdf", "读取、拆分、合并 PDF", true, "内置", "---\nname: pdf\n---\n\n# PDF 处理"),
            Skill("sk3", "web-research", "多来源网页调研并引用出处", true, "内置", "---\nname: web-research\n---\n\n# 网页调研"),
            Skill("sk4", "release-notes", "根据 git log 生成发布说明", false, "工作区", "---\nname: release-notes\n---\n\n# 发布说明"),
        ),
    )
    override val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    override suspend fun setSkillEnabled(skillId: String, enabled: Boolean) =
        _skills.update { l -> l.map { if (it.id == skillId) it.copy(enabled = enabled) else it } }

    override suspend fun createSkill(name: String, description: String, content: String) =
        _skills.update { it + Skill(nid("sk"), name, description, true, "工作区", content) }

    private val _mcp = MutableStateFlow(
        listOf(
            McpServer(
                "m1", "filesystem", McpTransport.Stdio, "npx -y @modelcontextprotocol/server-filesystem ~/workspace", "", false, McpStatus.Connected,
                listOf(McpTool("read_file", "读取文件", true), McpTool("write_file", "写入文件", true), McpTool("list_directory", "列目录", true)),
            ),
            McpServer(
                "m2", "github", McpTransport.Http, "https://api.githubcopilot.com/mcp/", "GITHUB_TOKEN=••••", true, McpStatus.Error,
                listOf(McpTool("search_issues", "搜索 issue", true), McpTool("create_pr", "创建 PR", false)),
            ),
        ),
    )
    override val mcpServers: StateFlow<List<McpServer>> = _mcp.asStateFlow()

    override suspend fun upsertMcpServer(server: McpServer) {
        val s = if (server.tools.isEmpty()) server.copy(status = McpStatus.Connecting) else server
        _mcp.update { l -> if (l.any { it.id == s.id }) l.map { if (it.id == s.id) s else it } else l + s }
        scope.launch {
            delay(1200)
            _mcp.update { l ->
                l.map {
                    if (it.id == s.id && it.status == McpStatus.Connecting) {
                        it.copy(status = McpStatus.Connected, tools = listOf(McpTool("ping", "连通性检查", true), McpTool("query", "查询", true)))
                    } else it
                }
            }
        }
    }

    override suspend fun removeMcpServer(serverId: String) = _mcp.update { l -> l.filterNot { it.id == serverId } }

    override suspend fun setMcpToolEnabled(serverId: String, toolName: String, enabled: Boolean) = _mcp.update { l ->
        l.map { s -> if (s.id != serverId) s else s.copy(tools = s.tools.map { if (it.name == toolName) it.copy(enabled = enabled) else it }) }
    }

    private val _hooks = MutableStateFlow(
        listOf(Hook("h1", "PreToolUse", "bash", "~/.hoshino/hooks/guard.sh", "Claude", true)),
    )
    override val hooks: StateFlow<List<Hook>> = _hooks.asStateFlow()
    private val _webhooks = MutableStateFlow<List<WebhookRule>>(emptyList())
    override val webhooks: StateFlow<List<WebhookRule>> = _webhooks.asStateFlow()

    override suspend fun upsertHook(hook: Hook) = _hooks.update { l ->
        if (l.any { it.id == hook.id }) l.map { if (it.id == hook.id) hook else it } else l + hook
    }
    override suspend fun removeHook(hookId: String) = _hooks.update { l -> l.filterNot { it.id == hookId } }
    override suspend fun upsertWebhook(rule: WebhookRule) = _webhooks.update { l ->
        if (l.any { it.id == rule.id }) l.map { if (it.id == rule.id) rule else it } else l + rule
    }
    override suspend fun removeWebhook(ruleId: String) = _webhooks.update { l -> l.filterNot { it.id == ruleId } }

    // ---------------- 自动化 ----------------

    private val _schedules = MutableStateFlow(
        listOf(
            Schedule(
                "sc1", "依赖安全巡检", "检查 package.json 与 Gradle 依赖的已知漏洞，输出摘要", Repeat.Weekly, "每周一 09:00", "Asia/Shanghai",
                now() + 3 * 86_400_000L, true,
                listOf(ScheduleRun(now() - 4 * 86_400_000L, true, "无高危漏洞"), ScheduleRun(now() - 11 * 86_400_000L, false, "网络超时")),
            ),
        ),
    )
    override val schedules: StateFlow<List<Schedule>> = _schedules.asStateFlow()

    override suspend fun upsertSchedule(schedule: Schedule) = _schedules.update { l ->
        if (l.any { it.id == schedule.id }) l.map { if (it.id == schedule.id) schedule else it } else listOf(schedule) + l
    }
    override suspend fun setScheduleEnabled(scheduleId: String, enabled: Boolean) =
        _schedules.update { l -> l.map { if (it.id == scheduleId) it.copy(enabled = enabled) else it } }
    override suspend fun runScheduleNow(scheduleId: String) {
        delay(800)
        _schedules.update { l -> l.map { if (it.id == scheduleId) it.copy(runs = listOf(ScheduleRun(now(), true, "手动运行完成（模拟）")) + it.runs) else it } }
    }
    override suspend fun deleteSchedule(scheduleId: String) = _schedules.update { l -> l.filterNot { it.id == scheduleId } }

    private val _goals = MutableStateFlow(
        listOf(Goal("g1", "把测试覆盖率提升到 80%", "每周推进一个模块，并在会话中汇报", GoalStatus.Paused, 0.52f, now() - 86_400_000L)),
    )
    override val goals: StateFlow<List<Goal>> = _goals.asStateFlow()
    override suspend fun upsertGoal(goal: Goal) = _goals.update { l ->
        if (l.any { it.id == goal.id }) l.map { if (it.id == goal.id) goal else it } else listOf(goal) + l
    }
    override suspend fun setGoalStatus(goalId: String, status: GoalStatus) =
        _goals.update { l -> l.map { if (it.id == goalId) it.copy(status = status, updatedAt = now()) else it } }

    // ---------------- 系统 ----------------

    private val _shizuku = MutableStateFlow(ShizukuStatus.NotRunning)
    override val shizuku: StateFlow<ShizukuStatus> = _shizuku.asStateFlow()

    override suspend fun refreshShizuku() {
        delay(500)
        // 模拟：用户按引导启动后再次检查
        if (_shizuku.value == ShizukuStatus.NotInstalled) _shizuku.value = ShizukuStatus.NotRunning
        else if (_shizuku.value == ShizukuStatus.NotRunning) _shizuku.value = ShizukuStatus.NotAuthorized
    }

    override suspend fun requestShizukuPermission() {
        delay(700)
        if (_shizuku.value == ShizukuStatus.NotAuthorized) _shizuku.value = ShizukuStatus.Ready
    }

    private val _kernel = MutableStateFlow(KernelInfo("dsh 0.9.4", "Node.js v22.11.0 (arm64)", true, "proot · Alpine 3.20", true))
    override val kernel: StateFlow<KernelInfo> = _kernel.asStateFlow()

    override suspend fun restartKernel() {
        _kernel.update { it.copy(running = false) }
        _connection.value = ConnectionState.Connecting
        delay(1500)
        _kernel.update { it.copy(running = true) }
        _connection.value = ConnectionState.Connected
        _restart.value = false
    }

    override suspend fun kernelLogs(): List<String> = listOf(
        "[21:40:01] dsh 0.9.4 starting (headless, stdio bridge)",
        "[21:40:01] node v22.11.0 arm64",
        "[21:40:02] plugins loaded: 10 enabled, 1 disabled",
        "[21:40:02] sandbox proot ready: alpine 3.20",
        "[21:40:02] mcp filesystem connected (3 tools)",
        "[21:40:03] mcp github error: 401 Unauthorized",
        "[21:40:03] bridge listening",
    )

    private val _caps = MutableStateFlow(
        listOf(
            PhoneCapability("files", "文件访问", "读写共享存储中的工作区与附件", true),
            PhoneCapability("notify", "通知", "运行完成、需要审批时提醒", false),
            PhoneCapability("a11y", "无障碍服务", "读取屏幕内容、模拟点击（手机操作 Agent）", false),
            PhoneCapability("capture", "截图 / 录屏", "让 Agent 看到当前屏幕", false),
            PhoneCapability("overlay", "悬浮窗", "在其他应用上显示运行状态", false),
        ),
    )
    override val phoneCapabilities: StateFlow<List<PhoneCapability>> = _caps.asStateFlow()
    override suspend fun requestCapability(capabilityId: String) {
        delay(300)
        _caps.update { l -> l.map { if (it.id == capabilityId) it.copy(granted = true) else it } }
    }

    override val lspServers: StateFlow<List<LspServer>> = MutableStateFlow(
        listOf(
            LspServer("kotlin-language-server", "Kotlin", "运行中", "1.3.13"),
            LspServer("typescript-language-server", "TypeScript", "运行中", "4.3.3"),
            LspServer("pyright", "Python", "未启动", "1.1.380"),
            LspServer("rust-analyzer", "Rust", "未安装", "—"),
        ),
    ).asStateFlow()

    override val inspector: StateFlow<List<InspectorNode>> = MutableStateFlow(
        listOf(
            InspectorNode(
                "kernel", "dsh", "运行中",
                listOf(
                    InspectorNode("session-service", "service", "运行中", listOf(InspectorNode("store: sqlite", "storage", "就绪"))),
                    InspectorNode("tool-runtime", "service", "运行中", listOf(InspectorNode("bash#1", "process", "空闲"), InspectorNode("str_replace_editor", "tool", "就绪"))),
                    InspectorNode("sandbox", "proot", "就绪", listOf(InspectorNode("alpine 3.20", "rootfs", "已挂载"))),
                    InspectorNode("scheduler", "service", "运行中"),
                ),
            ),
            InspectorNode(
                "mcp", "client", "部分可用",
                listOf(InspectorNode("filesystem", "stdio", "已连接"), InspectorNode("github", "http", "错误")),
            ),
            InspectorNode("bridge", "M0", "已连接", listOf(InspectorNode("transport", "stdio", "正常"))),
        ),
    ).asStateFlow()

    // ---------------- 终端 ----------------

    private val _terminals = MutableStateFlow<List<TerminalSession>>(emptyList())
    override val terminals: StateFlow<List<TerminalSession>> = _terminals.asStateFlow()

    override suspend fun openTerminal(): String {
        val id = nid("term")
        val banner = listOf(
            TerminalLine("Welcome to Alpine Linux 3.20 (proot)", TerminalStream.Output),
            TerminalLine("输入 help 查看可用命令（模拟终端）", TerminalStream.Output),
        )
        _terminals.update { it + TerminalSession(id, "shell ${it.size + 1}", banner) }
        return id
    }

    override suspend fun closeTerminal(terminalId: String) = _terminals.update { l -> l.filterNot { it.id == terminalId } }

    override suspend fun terminalInput(terminalId: String, input: String) {
        fun append(lines: List<TerminalLine>, clear: Boolean = false) = _terminals.update { l ->
            l.map { if (it.id == terminalId) it.copy(lines = if (clear) emptyList() else it.lines + lines) else it }
        }
        append(listOf(TerminalLine("~/workspace $ $input", TerminalStream.Input)))
        delay(120)
        val cmd = input.trim()
        val out = when {
            cmd.isEmpty() -> emptyList()
            cmd == "clear" -> { append(emptyList(), clear = true); return }
            cmd == "help" -> listOf("可用：ls pwd uname whoami date echo clear")
            cmd == "ls" -> listOf("AGENTS.md  README.md  app  package.json  src")
            cmd == "pwd" -> listOf("/root/workspace")
            cmd.startsWith("uname") -> listOf("Linux localhost 5.15.0-android aarch64 Linux")
            cmd == "whoami" -> listOf("root")
            cmd == "date" -> listOf(java.util.Date().toString())
            cmd.startsWith("echo ") -> listOf(cmd.removePrefix("echo "))
            else -> listOf("sh: ${cmd.substringBefore(' ')}: not found")
        }
        val stream = if (out.firstOrNull()?.startsWith("sh:") == true) TerminalStream.Error else TerminalStream.Output
        append(out.map { TerminalLine(it, stream) })
    }
}
