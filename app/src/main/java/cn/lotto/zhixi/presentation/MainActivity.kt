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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal val Ink = Color(0xFF19332D)
internal val Muted = Color(0xFF788A82)
internal var Green by mutableStateOf(Color(0xFFD94372))
internal val DeepGreen = Color(0xFF0D5B4D)
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
    var homeQuick by remember { mutableStateOf<List<Ticket>>(emptyList()) }
    var smartTickets by remember { mutableStateOf<List<Ticket>>(emptyList()) }
    var draws by remember { mutableStateOf(db.readAll()) }
    var gameDraws by remember(selectedGameCode) { mutableStateOf(db.readGame(selectedGameCode)) }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf("") }
    var syncTime by remember { mutableStateOf(context.getSharedPreferences("sync", 0).getString("last_ok", null).orEmpty()) }
    var doubaoApiKey by remember { mutableStateOf(DoubaoKeyVault.loadOrInitialize(context, BuildConfig.DEFAULT_DOUBAO_API_KEY)) }
    var doubaoModelId by remember { mutableStateOf(prefs.getString("doubao_model_id", "doubao-seed-2-1-pro-260915") ?: "doubao-seed-2-1-pro-260915") }
    var doubaoBusy by remember { mutableStateOf(false) }
    var doubaoMessage by remember { mutableStateOf("") }
    var doubaoReason by remember { mutableStateOf("") }
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

    fun sync() {
        if (syncing) return
        syncing = true
        syncMessage = "正在同步各彩种官方开奖数据…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { OfficialDrawSync(db).syncLatest() } }
            result.onSuccess { updated ->
                draws = db.readAll()
                gameDraws = db.readGame(selectedGameCode)
                syncTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())
                context.getSharedPreferences("sync", 0).edit().putString("last_ok", syncTime).apply()
                val success = "同步成功 · 更新 $updated 期"
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
                Box(Modifier.fillMaxWidth().background(Color.Transparent).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.fillMaxWidth().height(54.dp).shadow(12.dp, RoundedCornerShape(30.dp))
                            .clip(RoundedCornerShape(30.dp)).background(Color.White.copy(alpha = .84f))
                            .border(1.dp, Color.White.copy(alpha = .95f), RoundedCornerShape(30.dp)).padding(horizontal = 5.dp, vertical = 4.dp),
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
                                Box(Modifier.size(44.dp).background(Green.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(if (historyReturn) R.drawable.ic_nav_top else icons[index]), contentDescription = if (historyReturn) "回到顶部" else label, tint = Green, modifier = Modifier.size(27.dp))
                                }
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
                    0 -> Home(draws, gameDraws, pinnedTicket, homeQuick, years, amount, mode, selectedGame, themeIndex,
                        { years = it }, { amount = it }, { mode = it },
                        { homeQuick = LotteryEngine.generateForGame(draws, amount, years, mode, selectedGame) }, syncTime,
                        { code -> selectedGameCode = code; gameDraws = db.readGame(code); prefs.edit().putString("selected_game", code).apply(); homeQuick = emptyList(); smartTickets = emptyList() },
                        { index -> themeIndex = index; prefs.edit().putInt("theme_index", index).apply() })
                    1 -> if (selectedGame.code == "ssq") Analysis(draws, years, { years = it }) else GameAnalysis(selectedGame, gameDraws, years, { years = it })
                    2 -> if (selectedGame.code == "ssq") History(draws, syncing, syncMessage, syncTime, ::sync, historyScroll) else GameHistory(selectedGame, gameDraws, syncing, syncMessage, syncTime, ::sync, historyScroll)
                    else -> Smart(draws, gameDraws, years, amount, mode, selectedGame, { years = it }, { amount = it }, { mode = it },
                        { smartTickets = LotteryEngine.generateForGame(draws, amount, years, mode, selectedGame) },
                        { filters -> smartTickets = LotteryEngine.generateForGame(draws, amount, years, mode, selectedGame, sumRange = filters.sum, oddRange = filters.odd, bigRange = filters.big, maxConsecutivePairs = filters.maxRuns) },
                        smartTickets, doubaoApiKey, { doubaoApiKey = it }, doubaoModelId, { doubaoModelId = it },
                        { key, model -> doubaoApiKey = key; doubaoModelId = model; DoubaoKeyVault.save(context, key); prefs.edit().putString("doubao_model_id", model).apply(); doubaoMessage = "豆包配置已安全保存" },
                        doubaoBusy, doubaoMessage, doubaoReason,
                        { filters ->
                            if (doubaoBusy) return@Smart
                            if (doubaoApiKey.isBlank()) { doubaoMessage = "请先填写豆包 API Key 并保存"; return@Smart }
                            doubaoBusy = true; doubaoMessage = "正在根据历史数据请求豆包分析…"; doubaoReason = ""
                            scope.launch {
                                val history = if (selectedGame.code == "ssq") draws.map { GameDraw("ssq", it.issue, it.date, it.reds, listOf(it.blue)) } else gameDraws
                                val result = withContext(Dispatchers.IO) { runCatching {
                                    val frontMin = if (filters?.sum != null) filters.sum.first else Int.MIN_VALUE
                                    val frontMax = if (filters?.sum != null) filters.sum.last else Int.MAX_VALUE
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
                                result.onSuccess { picked -> smartTickets = picked.tickets; doubaoReason = picked.reasoning; doubaoMessage = "豆包选号完成 · ${picked.tickets.size} 组" }
                                    .onFailure { doubaoMessage = "豆包选号失败：${it.message?.take(100) ?: "网络或配置错误"}" }
                                doubaoBusy = false
                            }
                        })
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

