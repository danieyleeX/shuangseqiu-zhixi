package cn.lotto.zhixi

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val Ink = Color(0xFF19332D)
private val Muted = Color(0xFF788A82)
private val Green = Color(0xFF147D68)
private val DeepGreen = Color(0xFF0D5B4D)
private val CanvasBg = Color(0xFFF3F7F4)
private val Red = Color(0xFFE65B62)
private val Blue = Color(0xFF4B83D7)
private val Amber = Color(0xFFE3B45E)
private val ModelOptions = listOf("AI集成", "综合模型", "近期趋势", "热号优先", "冷热搭配", "遗漏反转", "均衡型", "分区均衡")
private data class TicketFilters(val sum: IntRange, val odd: IntRange, val big: IntRange, val maxRuns: Int)

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
    var years by remember { mutableIntStateOf(1) }
    var amount by remember { mutableIntStateOf(3) }
    var mode by remember { mutableStateOf("综合模型") }
    var homeQuick by remember { mutableStateOf<List<Ticket>>(emptyList()) }
    var smartTickets by remember { mutableStateOf<List<Ticket>>(emptyList()) }
    var draws by remember { mutableStateOf(db.readAll()) }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf("") }
    var syncTime by remember { mutableStateOf(context.getSharedPreferences("sync", 0).getString("last_ok", null).orEmpty()) }
    val historyScroll = rememberScrollState()
    val pinnedTicket = remember(draws) {
        if (draws.isEmpty()) null else {
            val pinnedKey = "pinned_ai_candidate_${draws.first().issue}"
            val saved = prefs.getString(pinnedKey, null)
            val restored = saved?.let(::parseTicket)
            restored ?: LotteryEngine.generate(draws, 1, 1, "AI集成").first().also { ticket ->
                prefs.edit().putString(pinnedKey, serializeTicket(ticket)).apply()
            }
        }
    }

    fun sync() {
        if (syncing) return
        syncing = true
        syncMessage = "正在连接中国福彩网…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { OfficialDrawSync(db).syncLatest() } }
            result.onSuccess { updated ->
                draws = db.readAll()
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

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = CanvasBg, surface = Color.White)) {
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
                                Box(Modifier.size(44.dp).background(Color(0xFFE0F2E9), CircleShape), contentAlignment = Alignment.Center) {
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
                    0 -> Home(draws, pinnedTicket, homeQuick, years, amount, mode,
                        { years = it }, { amount = it }, { mode = it },
                        { homeQuick = LotteryEngine.generate(draws, amount, years, mode) }, syncTime)
                    1 -> Analysis(draws, years, { years = it })
                    2 -> History(draws, syncing, syncMessage, syncTime, ::sync, historyScroll)
                    else -> Smart(draws, years, amount, mode, { years = it }, { amount = it }, { mode = it },
                        { smartTickets = LotteryEngine.generate(draws, amount, years, mode) },
                        { filters -> smartTickets = LotteryEngine.generate(draws, amount, years, mode, sumRange = filters.sum, oddRange = filters.odd, bigRange = filters.big, maxConsecutivePairs = filters.maxRuns) },
                        smartTickets)
                }
            }
        }
    }
}

@Composable
private fun PageScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), content = content)
}

@Composable
private fun Header(title: String, subtitle: String? = null, timestamp: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        if (subtitle != null && timestamp != null) {
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(subtitle, fontSize = 12.sp, color = Muted)
                Text("数据更新 · ${timestamp.ifBlank { "待同步" }}", fontSize = 11.sp, color = Green)
            }
        } else if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)
        .background(Color.White, RoundedCornerShape(22.dp)).padding(17.dp), content = content)
}

@Composable
private fun Label(text: String) {
    Text(text, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun Numbers(reds: List<Int>, blue: Int, small: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (small) 5.dp else 7.dp)) {
        reds.forEach { Ball(it, Red, small) }
        Text("+", color = Muted, fontSize = 14.sp)
        Ball(blue, Blue, small)
    }
}

@Composable
private fun Ball(n: Int, color: Color, small: Boolean) {
    Box(Modifier.size(if (small) 28.dp else 36.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text("%02d".format(n), color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (small) 10.sp else 13.sp)
    }
}

@Composable
private fun ChipRow(values: List<String>, selected: String, choose: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        values.forEach { value ->
            val active = value == selected
            Text(value, modifier = Modifier.border(1.dp, if (active) Green else Color(0xFFE4EBE6), RoundedCornerShape(50))
                .background(if (active) Color(0xFFE5F3ED) else Color.White, RoundedCornerShape(50))
                .clickable { choose(value) }.padding(horizontal = 14.dp, vertical = 9.dp),
                color = if (active) Green else Muted, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun Home(draws: List<Draw>, pinned: Ticket?, quick: List<Ticket>, years: Int, amount: Int, mode: String,
                 setYears: (Int) -> Unit, setAmount: (Int) -> Unit, setMode: (String) -> Unit,
                 generate: () -> Unit, syncTime: String) {
    val (_, nextDate) = drawCalendar(draws.firstOrNull()?.date.orEmpty())
    PageScroll {
        Header("彩票智析", "统计模型参考 · 双色球", syncTime)
        CardBox {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("本期推荐号码", color = Muted, fontSize = 13.sp); Text("综合模型", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Text("${draws.size} 期", color = Green, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            if (pinned != null) {
                Numbers(pinned.reds, pinned.blue)
                Text("结构评分 ${pinned.score} / 100 · 仅供统计参考", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            } else Text("历史数据准备中", color = Muted)
        }
        CardBox {
            Label("开奖信息")
            Text("双色球 · 通常每周二、四、日开奖", color = Ink, fontSize = 13.sp)
            Text("下期开奖日：$nextDate", color = Green, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp))
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Color(0xFFEDF1EE))
            if (draws.isNotEmpty()) {
                val draw = draws.first()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("最近一期官方开奖", color = Ink, fontWeight = FontWeight.Bold); Text("${draw.date} · ${draw.issue}期", color = Muted, fontSize = 12.sp) }
                Spacer(Modifier.height(12.dp)); Numbers(draw.reds, draw.blue, true)
            } else Text("官方历史数据待同步", color = Muted)
        }
        CardBox {
            Label("快速生成")
            Row(verticalAlignment = Alignment.CenterVertically) { Text("数据窗口", color = Muted, fontSize = 13.sp); Spacer(Modifier.width(10.dp)); ChipRow((1..10).map { "${it}年" }, "${years}年") { setYears(it.dropLast(1).toInt()) } }
            Spacer(Modifier.height(11.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Text("生成组数", color = Muted, fontSize = 13.sp); Spacer(Modifier.width(10.dp)); ChipRow((1..10).map { "${it}组" }, "${amount}组") { setAmount(it.dropLast(1).toInt()) } }
            Spacer(Modifier.height(11.dp))
            ChipRow(ModelOptions, mode, setMode)
            Spacer(Modifier.height(14.dp))
            Button(onClick = generate, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) { Text("✦  生成 $amount 组候选号码", fontWeight = FontWeight.Bold) }
            if (quick.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("本次生成结果", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                quick.forEachIndexed { index, ticket ->
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("%02d".format(index + 1), color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Numbers(ticket.reds, ticket.blue, true)
                    }
                    Text("结构评分 ${ticket.score}", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
                }
            }
        }
        Text("彩票开奖结果具有随机性，统计与历史回测不代表未来中奖概率。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

@Composable
private fun Analysis(draws: List<Draw>, years: Int, setYears: (Int) -> Unit) {
    val data = LotteryEngine.window(draws, years)
    val freq = LotteryEngine.frequency(data)
    val miss = LotteryEngine.misses(data)
    val sorted = (1..33).map { freq[it] }.sorted()
    val low = sorted[10]; val high = sorted[22]
    val hot = (1..33).filter { freq[it] > high }
    val cold = (1..33).filter { freq[it] < low }
    val warm = (1..33).filter { freq[it] in low..high }
    PageScroll {
        Header("数据分析", "开奖分布与号码结构 · ${data.size} 期样本")
        CardBox { Label("分析区间"); ChipRow((1..10).map { "${it}年" }, "${years}年") { setYears(it.dropLast(1).toInt()) } }
        CardBox {
            Label("红球出现频率排行")
            (1..33).sortedByDescending { freq[it] }.take(12).forEachIndexed { index, n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("%02d".format(n), color = Red, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
                    Box(Modifier.weight(1f).height(9.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF0F3F0))) {
                        Box(Modifier.fillMaxWidth(if (freq[n] == 0) 0f else freq[n].toFloat() / (freq.maxOrNull() ?: 1)).fillMaxHeight().background(if (index < 4) Red else Green, RoundedCornerShape(8.dp)))
                    }
                    Text(" ${freq[n]} 次", color = Muted, fontSize = 11.sp, modifier = Modifier.width(56.dp))
                }
            }
        }
        CardBox {
            Label("冷热号分布")
            Text("热号  ${hot.size} 个：${hot.sorted().joinToString("、") { "%02d".format(it) }}", color = Red, fontSize = 12.sp)
            Text("温号  ${warm.size} 个：${warm.sorted().joinToString("、") { "%02d".format(it) }}", color = Green, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Text("冷号  ${cold.size} 个：${cold.sorted().joinToString("、") { "%02d".format(it) }}", color = Blue, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        CardBox {
            Label("遗漏值排行")
            Text((1..33).sortedByDescending { miss[it] }.take(8).joinToString("    ") { "%02d · %d期".format(it, miss[it]) }, color = Muted, fontSize = 12.sp)
            Text("当前区间内未出现的期数", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
        }
        val latest = data.firstOrNull()
        CardBox {
            Label("号码结构特征")
            if (latest != null) {
                val odd = latest.reds.count { it % 2 == 1 }; val big = latest.reds.count { it >= 17 }
                Text("最新一期 ${latest.issue}：和值 ${latest.reds.sum()} · 跨度 ${latest.reds.last() - latest.reds.first()} · 奇偶 $odd:${6 - odd} · 大小 $big:${6 - big} · 连号 ${LotteryEngine.consecutivePairs(latest)} 组", color = Ink, fontSize = 13.sp)
            }
            Text("近 40 期和值走势", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)); MiniChart(data.take(40).reversed().map { it.reds.sum().toFloat() }, Red)
            Text("近 40 期跨度走势", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp)); MiniChart(data.take(40).reversed().map { (it.reds.last() - it.reds.first()).toFloat() }, Blue)
        }
        CardBox {
            var selected by remember { mutableIntStateOf(1) }
            Label("红球遗漏走势"); ChipRow((1..33).map { "%02d".format(it) }, "%02d".format(selected)) { selected = it.toInt() }
            val trend = data.take(40).reversed().mapIndexed { index, _ -> data.take(40).reversed().take(index + 1).reversed().indexOfFirst { selected in it.reds }.let { if (it < 0) 40f else it.toFloat() } }
            MiniChart(trend, Green)
        }
    }
}

@Composable
private fun MiniChart(values: List<Float>, color: Color) {
    if (values.size < 2) { Text("数据不足", color = Muted); return }
    val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f); val min = values.minOrNull() ?: 0f
    Canvas(Modifier.fillMaxWidth().height(130.dp).padding(top = 8.dp)) {
        val points = values.mapIndexed { index, value -> Offset(index.toFloat() / (values.size - 1) * size.width, size.height - (value - min + 1) / (max - min + 1) * size.height) }
        for (index in 0..3) { val y = index * size.height / 3; drawLine(Color(0xFFE8ECE8), Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
        val path = Path().apply { moveTo(points.first().x, points.first().y); points.drop(1).forEach { lineTo(it.x, it.y) } }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx())); points.forEach { drawCircle(color, 3.dp.toPx(), it) }
    }
}

@Composable
private fun SyncLine(syncing: Boolean, message: String, time: String, onSync: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(if (syncing) "同步中" else if (message.isNotBlank()) message else "官方数据 · ${if (time.isBlank()) "等待同步" else "更新于 $time"}", fontSize = 12.sp, color = if (message.startsWith("同步失败")) Red else Muted)
            Text("来源：中国福利彩票发行管理中心", fontSize = 10.sp, color = Muted)
        }
        TextButton(onClick = onSync, enabled = !syncing) { Text(if (syncing) "…" else "立即更新") }
    }
}

@Composable
private fun History(draws: List<Draw>, syncing: Boolean, message: String, time: String, onSync: () -> Unit, scroll: androidx.compose.foundation.ScrollState) {
    var filter by remember { mutableStateOf("最近30期") }
    var query by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("全部年份") }
    val years = remember(draws) { listOf("全部年份") + draws.map { it.date.take(4) }.distinct().sortedDescending() }
    val count = when (filter) { "最近50期" -> 50; "最近100期" -> 100; else -> 30 }
    val filtered = draws.filter { (year == "全部年份" || it.date.startsWith(year)) && (query.isBlank() || it.issue.contains(query) || it.date.contains(query)) }
        .let { if (filter.startsWith("近")) { val n = filter.drop(1).dropLast(1).toIntOrNull() ?: 1; LotteryEngine.window(it, n) } else it.take(count) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 28.dp)) {
        Header("历史数据", "${draws.size} 期 · 官方来源数据")
        CardBox {
            SyncLine(syncing, message, time, onSync); Spacer(Modifier.height(8.dp))
            ChipRow(listOf("最近30期", "最近50期", "最近100期", "近1年", "近3年", "近5年"), filter) { filter = it }
            Spacer(Modifier.height(10.dp)); ChipRow(years, year) { year = it }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("查询期号或日期") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            Text("找到 ${filtered.size} 期", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        filtered.forEach { draw ->
            CardBox {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${draw.issue} 期", fontWeight = FontWeight.SemiBold, color = Ink); Text(draw.date, color = Muted, fontSize = 12.sp) }
                Spacer(Modifier.height(12.dp)); Numbers(draw.reds, draw.blue, true)
            }
        }
    }
}

@Composable
private fun Smart(draws: List<Draw>, years: Int, amount: Int, mode: String, setYears: (Int) -> Unit, setAmount: (Int) -> Unit,
                  setMode: (String) -> Unit, generate: () -> Unit, generateFiltered: (TicketFilters) -> Unit, tickets: List<Ticket>) {
    var custom by remember { mutableStateOf(false) }
    var sumMin by remember { mutableStateOf("60") }
    var sumMax by remember { mutableStateOf("150") }
    var oddChoice by remember { mutableStateOf("1–5") }
    var bigChoice by remember { mutableStateOf("1–5") }
    var maxRunsChoice by remember { mutableStateOf("最多2组") }
    val result = remember(draws, mode, years) { LotteryEngine.backtest(draws, mode, years) }
    PageScroll {
        Header("智能选号", "多模型评分 · 历史回测 · 自定义筛选")
        if (tickets.isNotEmpty()) CardBox {
            Label("本次候选 · ${tickets.size} 组")
            tickets.forEachIndexed { index, ticket ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("第 ${index + 1} 组", color = Ink, fontWeight = FontWeight.Bold); Text("结构评分 ${ticket.score}", color = Green, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp)); Numbers(ticket.reds, ticket.blue, true)
                if (index < tickets.lastIndex) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFFEFF2EF))
            }
        }
        CardBox {
            Label("选号设置")
            Text("策略", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(7.dp)); ChipRow(ModelOptions, mode, setMode)
            Spacer(Modifier.height(13.dp)); Text("数据窗口", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(7.dp)); ChipRow((1..10).map { "${it}年" }, "${years}年") { setYears(it.dropLast(1).toInt()) }
            Spacer(Modifier.height(13.dp)); Text("候选组数", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(7.dp)); ChipRow((1..10).map { "${it}组" }, "${amount}组") { setAmount(it.dropLast(1).toInt()) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(custom, { custom = it }); Text("启用自定义筛选", color = Ink, fontSize = 13.sp) }
            if (custom) {
                Text("红球和值（21–183）", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedTextField(sumMin, { sumMin = it }, label = { Text("最小值") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(sumMax, { sumMax = it }, label = { Text("最大值") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                }
                Text("奇数红球个数", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                ChipRow(listOf("不限", "1–5", "2–4", "3个"), oddChoice) { oddChoice = it }
                Text("大号（17–33）个数", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                ChipRow(listOf("不限", "1–5", "2–4", "3个"), bigChoice) { bigChoice = it }
                Text("连续号上限", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                ChipRow(listOf("不限", "最多1组", "最多2组"), maxRunsChoice) { maxRunsChoice = it }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                if (custom) {
                    val min = sumMin.toIntOrNull()?.coerceIn(21, 183) ?: 60
                    val max = (sumMax.toIntOrNull() ?: 150).coerceIn(min, 183)
                    fun range(choice: String) = when (choice) { "1–5" -> 1..5; "2–4" -> 2..4; "3个" -> 3..3; else -> 0..6 }
                    val maxRuns = when (maxRunsChoice) { "最多1组" -> 1; "最多2组" -> 2; else -> 5 }
                    generateFiltered(TicketFilters(min..max, range(oddChoice), range(bigChoice), maxRuns))
                } else generate()
            },
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) { Text("生成 $amount 组候选", fontWeight = FontWeight.Bold) }
        }
        CardBox {
            Label("综合评分权重")
            listOf("历史频率" to 26, "遗漏平衡" to 16, "和值结构" to 19, "奇偶与跨度" to 17, "蓝球频率" to 12, "连号结构" to 10).forEach { (name, weight) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = Ink, fontSize = 12.sp, modifier = Modifier.width(92.dp))
                    LinearProgressIndicator(progress = { weight / 30f }, modifier = Modifier.weight(1f).height(6.dp), color = Green, trackColor = Color(0xFFEFF2EF))
                    Text(" $weight%", color = Muted, fontSize = 11.sp, modifier = Modifier.width(43.dp))
                }
            }
            Text("AI 集成采用本地多因子集成评分，不依赖网络大模型或已训练模型。所有策略仅总结历史特征，不代表中奖概率。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
        CardBox {
            Label("历史回测 · 最近 ${result.draws} 期")
            Text("红球命中 ${result.redHits} 次 · 蓝球命中 ${result.blueHits} 次", color = Ink, fontSize = 14.sp)
            Text("每期平均命中 ${"%.2f".format(result.meanHits)} 个号码", color = Green, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text("命中分布：0 个 ${result.hit0}期 · 1 个 ${result.hit1}期 · 2 个 ${result.hit2}期 · 3 个及以上 ${result.hit3Plus}期", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Text("逐期向前滚动验证，仅使用当期之前的历史数据。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun drawCalendar(latestDate: String): Pair<String, String> {
    if (latestDate.isBlank()) return "" to "待同步"
    return runCatching {
        val date = LocalDate.parse(latestDate, DateTimeFormatter.ISO_LOCAL_DATE)
        val next = (1..7).map { date.plusDays(it.toLong()) }.first { it.dayOfWeek.value in listOf(2, 4, 7) }
        date.toString() to next.format(DateTimeFormatter.ofPattern("M月d日"))
    }.getOrDefault(latestDate to "以官网公告为准")
}

private fun serializeTicket(ticket: Ticket) = ticket.reds.joinToString(",") + "+" + ticket.blue + "+" + ticket.score
private fun parseTicket(value: String): Ticket? = runCatching {
    val parts = value.split('+'); Ticket(parts[0].split(',').map(String::toInt), parts[1].toInt(), parts[2].toInt())
}.getOrNull()

