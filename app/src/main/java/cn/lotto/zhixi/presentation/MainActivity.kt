package cn.lotto.zhixi.presentation

import cn.lotto.zhixi.BuildConfig
import cn.lotto.zhixi.R
import cn.lotto.zhixi.data.local.LotteryDatabase
import cn.lotto.zhixi.data.remote.DoubaoKeyVault
import cn.lotto.zhixi.data.remote.DoubaoLotteryClient
import cn.lotto.zhixi.data.remote.OfficialDrawSync
import cn.lotto.zhixi.data.remote.scheduleDrawSync
import cn.lotto.zhixi.domain.engine.LotteryEngine
import cn.lotto.zhixi.domain.model.*

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val Ink = Color(0xFF19332D)
internal val Muted = Color(0xFF788A82)
internal var Green by mutableStateOf(Color(0xFFD94372))
internal val CanvasBg = Color(0xFFF3F7F4)
internal val Red = Color(0xFFE65B62)
internal val Blue = Color(0xFF4B83D7)
internal val Amber = Color(0xFFE3B45E)
internal val ModelOptions = listOf("AI集成", "综合模型", "近期趋势", "热号优先", "冷热搭配", "遗漏反转", "均衡型", "分区均衡")
internal val ThemePalette = listOf(
    "玫红" to Color(0xFFE5235E), "蜜桃" to Color(0xFFF2A99E), "金黄" to Color(0xFFE6B52F),
    "薄荷" to Color(0xFF91C8B0), "浅蓝" to Color(0xFF91B6E3), "薰衣草" to Color(0xFFB4A0D5)
)
internal data class TicketFilters(val sum: IntRange, val odd: IntRange, val big: IntRange, val maxRuns: Int)
internal fun currentFilters(sumMin: String, sumMax: String, minPossible: Int, maxPossible: Int, oddChoice: String, bigChoice: String, maxRunsChoice: String): TicketFilters {
    val min = sumMin.toIntOrNull()?.coerceIn(minPossible, maxPossible) ?: minPossible
    val max = (sumMax.toIntOrNull() ?: maxPossible).coerceIn(min, maxPossible)
    fun range(choice: String) = when (choice) { "1–5" -> 1..5; "2–4" -> 2..4; "3个" -> 3..3; else -> 0..6 }
    val maxRuns = when (maxRunsChoice) { "最多1组" -> 1; "最多2组" -> 2; else -> 5 }
    return TicketFilters(min..max, range(oddChoice), range(bigChoice), maxRuns)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = LotteryDatabase(applicationContext)
        db.importAssetIfEmpty(applicationContext)
        scheduleDrawSync(applicationContext)
        setContent { App(db) }
    }
}

@Composable
fun App(db: LotteryDatabase) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("lottery_ui", 0) }
    var page by remember { mutableIntStateOf(0) }
    var selectedGameCode by remember { mutableStateOf(prefs.getString("selected_game", "ssq") ?: "ssq") }
    val selectedGame = remember(selectedGameCode) { LotteryGames.byCode(selectedGameCode) }
    var themeIndex by remember { mutableIntStateOf(prefs.getInt("theme_index", 0).coerceIn(0, ThemePalette.lastIndex)) }
    val selectedTheme = ThemePalette[themeIndex]
    SideEffect { Green = selectedTheme.second }

    var years by remember { mutableIntStateOf(1) }
    var amount by remember { mutableIntStateOf(3) }
    var mode by remember { mutableStateOf("综合模型") }
    var smartTickets by remember { mutableStateOf<List<Ticket>>(emptyList()) }
    var draws by remember { mutableStateOf(db.readAll()) }
    var gameDraws by remember(selectedGameCode) { mutableStateOf(db.readGame(selectedGameCode)) }
    var generatedHistory by remember(selectedGameCode) { mutableStateOf(db.readGeneratedRecords(selectedGameCode)) }

    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf("") }
    var syncTime by remember { mutableStateOf(context.getSharedPreferences("sync", 0).getString("last_ok", null).orEmpty()) }

    var doubaoApiKey by remember { mutableStateOf(DoubaoKeyVault.loadOrInitialize(context, BuildConfig.DEFAULT_DOUBAO_API_KEY)) }
    var doubaoModelId by remember { mutableStateOf(prefs.getString("doubao_model_id", "doubao-seed-2-1-pro-260915") ?: "doubao-seed-2-1-pro-260915") }
    var doubaoBusy by remember { mutableStateOf(false) }
    var doubaoMessage by remember { mutableStateOf("") }
    var doubaoReason by remember { mutableStateOf("") }

    var showSettingsDialog by remember { mutableStateOf(false) }

    val historyScroll = rememberScrollState()
    val pinnedTicket = remember(draws, gameDraws, selectedGameCode) {
        val latestIssue = if (selectedGame.code == "ssq") draws.firstOrNull()?.issue else gameDraws.firstOrNull()?.issue
        if (latestIssue == null) null else {
            val pinnedKey = "pinned_ai_candidate_${selectedGame.code}_$latestIssue"
            val saved = prefs.getString(pinnedKey, null)
            val restored = saved?.let(::parseTicket)
            restored ?: LotteryEngine.generateForGame(draws, 1, 1, "AI集成", selectedGame, candidatePoolMultiplier = 250).first().also { ticket ->
                prefs.edit().putString(pinnedKey, serializeTicket(ticket)).apply()
            }
        }
    }

    fun recordTickets(modeName: String, summary: String, tickets: List<Ticket>) {
        if (tickets.isEmpty()) return
        val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())
        val latestIssue = if (selectedGame.code == "ssq") draws.firstOrNull()?.issue.orEmpty() else gameDraws.firstOrNull()?.issue.orEmpty()
        val record = GeneratedRecord(
            gameCode = selectedGameCode,
            issue = latestIssue,
            timestamp = timeStr,
            mode = modeName,
            filterSummary = summary,
            tickets = tickets
        )
        scope.launch(Dispatchers.IO) {
            db.insertGeneratedRecord(record)
            val updated = db.readGeneratedRecords(selectedGameCode)
            withContext(Dispatchers.Main) { generatedHistory = updated }
        }
    }

    fun clearHistory() {
        scope.launch(Dispatchers.IO) {
            db.clearGeneratedRecords(selectedGameCode)
            val updated = db.readGeneratedRecords(selectedGameCode)
            withContext(Dispatchers.Main) { generatedHistory = updated }
        }
    }

    fun syncCurrentGame() {
        if (syncing) return
        syncing = true
        syncMessage = "正在更新【${selectedGame.name}】开奖数据…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { OfficialDrawSync(db).syncGame(selectedGameCode) } }
            result.onSuccess { updated ->
                draws = db.readAll()
                gameDraws = db.readGame(selectedGameCode)
                syncTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())
                context.getSharedPreferences("sync", 0).edit().putString("last_ok", syncTime).apply()
                val success = "同步成功 · 更新【${selectedGame.name}】$updated 期"
                syncMessage = success
                syncing = false
                delay(4000)
                if (syncMessage == success) syncMessage = ""
            }.onFailure { syncMessage = "同步失败：${it.message?.take(70) ?: "网络连接错误"}" }
            syncing = false
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = selectedTheme.second, secondary = selectedTheme.second, background = CanvasBg, surface = Color.White)) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                Box(Modifier.fillMaxWidth().background(Color.Transparent).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.fillMaxWidth().height(46.dp).shadow(10.dp, RoundedCornerShape(24.dp))
                            .clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = .84f))
                            .border(1.dp, Color.White.copy(alpha = .95f), RoundedCornerShape(24.dp)).padding(horizontal = 5.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
                    ) {
                        val labels = listOf("首页", "数据分析", "历史数据", "智能选号")
                        val icons = listOf(R.drawable.ic_nav_home, R.drawable.ic_nav_analysis, R.drawable.ic_nav_history, R.drawable.ic_nav_smart)
                        labels.forEachIndexed { index, label ->
                            val selected = page == index
                            val historyReturn = index == 2 && page == 2 && historyScroll.value > 80
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(26.dp)).clickable {
                                    when {
                                        historyReturn -> scope.launch { historyScroll.animateScrollTo(0) }
                                        page == 2 && index != 2 -> scope.launch { historyScroll.scrollTo(0); page = index }
                                        else -> page = index
                                    }
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Icon(painterResource(if (historyReturn) R.drawable.ic_nav_top else icons[index]), contentDescription = if (historyReturn) "回到顶部" else label, tint = Green, modifier = Modifier.size(23.dp))
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Icon(painterResource(icons[index]), contentDescription = label, tint = Muted, modifier = Modifier.size(18.dp))
                                        Text(label, color = Muted, fontSize = 9.sp, lineHeight = 11.sp, modifier = Modifier.padding(top = 1.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { inset ->
            Box(Modifier.fillMaxSize().background(CanvasBg).padding(inset)) {
                when (page) {
                    0 -> Home(
                        draws = draws,
                        gameDraws = gameDraws,
                        pinned = pinnedTicket,
                        game = selectedGame,
                        syncTime = syncTime,
                        generatedHistory = generatedHistory,
                        onClearHistory = ::clearHistory,
                        onOpenSettings = { showSettingsDialog = true }
                    )
                    1 -> if (selectedGame.code == "ssq") Analysis(draws, years, { years = it }) else GameAnalysis(selectedGame, gameDraws, years, { years = it })
                    2 -> if (selectedGame.code == "ssq") History(draws, historyScroll) else GameHistory(selectedGame, gameDraws, historyScroll)
                    else -> Smart(
                        draws = draws,
                        gameDraws = gameDraws,
                        years = years,
                        amount = amount,
                        mode = mode,
                        game = selectedGame,
                        setYears = { years = it },
                        setAmount = { amount = it },
                        setMode = { mode = it },
                        generate = {
                            val res = LotteryEngine.generateForGame(draws, amount, years, mode, selectedGame)
                            smartTickets = res
                            recordTickets(mode, "常规组数 $amount 组", res)
                        },
                        generateFiltered = { filters ->
                            val res = LotteryEngine.generateForGame(draws, amount, years, mode, selectedGame, sumRange = filters.sum, oddRange = filters.odd, bigRange = filters.big, maxConsecutivePairs = filters.maxRuns)
                            smartTickets = res
                            val summary = "和值 ${filters.sum.first}–${filters.sum.last} · 奇偶 ${filters.odd.first}–${filters.odd.last}"
                            recordTickets(mode, summary, res)
                        },
                        tickets = smartTickets,
                        aiBusy = doubaoBusy,
                        aiMessage = doubaoMessage,
                        aiReason = doubaoReason,
                        generateDoubao = { filters ->
                            if (doubaoBusy) return@Smart
                            if (doubaoApiKey.isBlank()) { doubaoMessage = "请先在右上角【设置】中配置豆包 API Key"; return@Smart }
                            doubaoBusy = true; doubaoMessage = "正在请求豆包 AI 分析…"; doubaoReason = ""
                            scope.launch {
                                val history = if (selectedGame.code == "ssq") draws.map { GameDraw("ssq", it.issue, it.date, it.reds, listOf(it.blue)) } else gameDraws
                                val result = withContext(Dispatchers.IO) { runCatching {
                                    val frontMin = filters?.sum?.first ?: Int.MIN_VALUE
                                    val frontMax = filters?.sum?.last ?: Int.MAX_VALUE
                                    val candidates = buildList {
                                        repeat(4) { batch ->
                                            addAll(LotteryEngine.generateForGame(draws, 10, years, "AI集成", selectedGame,
                                                sumRange = frontMin..frontMax,
                                                oddRange = filters?.odd ?: 0..6,
                                                bigRange = filters?.big ?: 0..6,
                                                maxConsecutivePairs = filters?.maxRuns ?: 5,
                                                candidatePoolMultiplier = 4,
                                                seed = System.nanoTime() + batch * 104_729L))
                                        }
                                    }.distinctBy { it.reds.joinToString(",") + "+" + it.secondary.joinToString(",") }
                                    DoubaoLotteryClient.generate(selectedGame, history, candidates, amount, years, mode, doubaoModelId, doubaoApiKey)
                                } }
                                result.onSuccess { picked ->
                                    smartTickets = picked.tickets
                                    doubaoReason = picked.reasoning
                                    doubaoMessage = "豆包 AI 选号完成 · ${picked.tickets.size} 组"
                                    val summary = if (filters != null) "和值 ${filters.sum.first}–${filters.sum.last} · 豆包 AI 精选" else "豆包 AI 智能精选"
                                    recordTickets("豆包 AI 分析", summary, picked.tickets)
                                }.onFailure {
                                    doubaoMessage = "豆包选号失败：${it.message?.take(100) ?: "网络或配置错误"}"
                                }
                                doubaoBusy = false
                            }
                        },
                        onOpenSettings = { showSettingsDialog = true }
                    )
                }

                if (showSettingsDialog) {
                    SystemSettingsDialog(
                        game = selectedGame,
                        setGame = { code ->
                            selectedGameCode = code
                            gameDraws = db.readGame(code)
                            prefs.edit().putString("selected_game", code).apply()
                            smartTickets = emptyList()
                            generatedHistory = db.readGeneratedRecords(code)
                        },
                        themeIndex = themeIndex,
                        setTheme = { index ->
                            themeIndex = index
                            prefs.edit().putInt("theme_index", index).apply()
                        },
                        syncing = syncing,
                        syncMessage = syncMessage,
                        syncTime = syncTime,
                        onSyncCurrentGame = ::syncCurrentGame,
                        apiKey = doubaoApiKey,
                        setApiKey = { doubaoApiKey = it },
                        modelId = doubaoModelId,
                        setModelId = { doubaoModelId = it },
                        saveAiConfig = { key, model ->
                            doubaoApiKey = key
                            doubaoModelId = model
                            DoubaoKeyVault.save(context, key)
                            prefs.edit().putString("doubao_model_id", model).apply()
                            doubaoMessage = "模型配置保存成功"
                        },
                        onDismiss = { showSettingsDialog = false }
                    )
                }
            }
        }
    }
}

private fun serializeTicket(ticket: Ticket) = ticket.reds.joinToString(",") + "+" + ticket.blue + "+" + ticket.score + "+" + ticket.gameCode + "+" + ticket.secondary.joinToString(",")
private fun parseTicket(value: String): Ticket? = runCatching {
    val parts = value.split('+')
    val blue = parts[1].toInt()
    val gameCode = parts.getOrNull(3) ?: "ssq"
    val secondary = parts.getOrNull(4)?.takeIf { it.isNotBlank() }?.split(',')?.map(String::toInt) ?: listOf(blue)
    Ticket(parts[0].split(',').map(String::toInt), blue, parts[2].toInt(), gameCode, secondary)
}.getOrNull()
