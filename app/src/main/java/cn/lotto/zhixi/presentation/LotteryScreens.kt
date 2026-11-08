package cn.lotto.zhixi.presentation

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
private fun Numbers(reds: List<Int>, blue: Int, small: Boolean = false, blueNumbers: List<Int> = listOf(blue)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (small) 5.dp else 7.dp)) {
        reds.forEach { Ball(it, Red, small) }
        if (blueNumbers.isNotEmpty()) {
            Text("+", color = Muted, fontSize = 14.sp)
            blueNumbers.forEach { Ball(it, Blue, small) }
        }
    }
}

@Composable
private fun TicketNumbers(ticket: Ticket, small: Boolean = false) {
    if (ticket.gameCode == "ssq" || ticket.gameCode == "dlt") {
        Numbers(ticket.reds, ticket.secondary.firstOrNull() ?: ticket.blue, small, ticket.secondary)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(if (small) 5.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ticket.reds.forEach { digit ->
                Box(Modifier.size(if (small) 30.dp else 38.dp).background(Color(0xFFFFE8EE), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Text(digit.toString(), color = Green, fontWeight = FontWeight.ExtraBold, fontSize = if (small) 15.sp else 19.sp)
                }
            }
        }
    }
}

@Composable
private fun Ball(n: Int, color: Color, small: Boolean) {
    Box(Modifier.size(if (small) 24.dp else 32.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text("%02d".format(n), color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (small) 10.sp else 13.sp)
    }
}

@Composable
private fun ChipRow(values: List<String>, selected: String, choose: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        values.forEach { value ->
            val active = value == selected
            Text(value, modifier = Modifier.border(1.dp, if (active) Green else Color(0xFFE4EBE6), RoundedCornerShape(50))
                .background(if (active) Green.copy(alpha = .20f) else Color.White, RoundedCornerShape(50))
                .clickable { choose(value) }.padding(horizontal = 14.dp, vertical = 9.dp),
                color = if (active) Green else Muted, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun GameDrawNumbers(draw: GameDraw, game: LotteryGame) {
    if (game.backCount > 0) Numbers(draw.front, draw.back.firstOrNull() ?: 0, true, draw.back)
    else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { draw.front.forEach { Ball(it, Red, true) } }
}

@Composable
fun GameAnalysis(game: LotteryGame, draws: List<GameDraw>, years: Int, setYears: (Int) -> Unit) {
    val latestYear = draws.firstOrNull()?.date?.take(4)?.toIntOrNull() ?: LocalDate.now().year
    val data = draws.filter { (it.date.take(4).toIntOrNull() ?: 0) >= latestYear - years + 1 }
    val firstNumber = if (game.digits) 0 else 1
    val freq = IntArray(game.frontMax + 1)
    data.forEach { d -> d.front.forEach { if (it in freq.indices) freq[it]++ } }
    val misses = IntArray(game.frontMax + 1) { n -> if (n < firstNumber) 0 else data.indexOfFirst { n in it.front }.let { if (it < 0) data.size else it } }
    val orderedFreq = (firstNumber..game.frontMax).map { freq[it] }.sorted()
    val low = orderedFreq.getOrNull((orderedFreq.size * .33).toInt()) ?: 0
    val high = orderedFreq.getOrNull((orderedFreq.size * .67).toInt()) ?: 0
    val hot = (firstNumber..game.frontMax).filter { freq[it] > high }
    val cold = (firstNumber..game.frontMax).filter { freq[it] < low }
    val warm = (firstNumber..game.frontMax).filter { freq[it] in low..high }
    val recent = data.take(40).reversed()
    var selected by remember(game.code) { mutableIntStateOf(firstNumber) }
    PageScroll {
        Header("数据分析", "${game.name} · 开奖分布与号码结构 · ${data.size} 期样本")
        CardBox { Label("分析区间"); ChipRow((1..10).map { "${it}年" }, "${years}年") { setYears(it.dropLast(1).toInt()) } }
        CardBox {
            Label(if (game.code == "dlt") "前区号码出现频率排行" else "号码出现频率排行榜")
            (firstNumber..game.frontMax).sortedByDescending { freq[it] }.take(minOf(12, game.frontMax + 1 - firstNumber)).forEachIndexed { index, n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (game.digits) "$n" else "%02d".format(n), color = Red, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
                    Box(Modifier.weight(1f).height(9.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF0F3F0))) {
                        Box(Modifier.fillMaxWidth(if (freq[n] == 0) 0f else freq[n].toFloat() / (freq.maxOrNull() ?: 1)).fillMaxHeight().background(if (index < 4) Red else Green, RoundedCornerShape(8.dp)))
                    }
                    Text(" ${freq[n]} 次", color = Muted, fontSize = 11.sp, modifier = Modifier.width(56.dp))
                }
            }
            if (game.backCount > 0) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color(0xFFEFF2EF))
                Label("后区号码频率")
                val backFreq = IntArray(game.backMax + 1)
                data.forEach { d -> d.back.forEach { if (it in backFreq.indices) backFreq[it]++ } }
                Text((1..game.backMax).sortedByDescending { backFreq[it] }.take(8).joinToString("    ") { "%02d · %d次".format(it, backFreq[it]) }, color = Blue, fontSize = 12.sp)
            }
        }
        CardBox {
            Label("热号、温号、冷号分布")
            fun format(list: List<Int>) = list.sorted().joinToString("、") { if (game.digits) "$it" else "%02d".format(it) }
            Text("热号  ${hot.size} 个：${format(hot)}", color = Red, fontSize = 12.sp)
            Text("温号  ${warm.size} 个：${format(warm)}", color = Green, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Text("冷号  ${cold.size} 个：${format(cold)}", color = Blue, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        CardBox {
            Label("当前遗漏值排行")
            Text((firstNumber..game.frontMax).sortedByDescending { misses[it] }.take(10).joinToString("    ") { (if (game.digits) "$it" else "%02d".format(it)) + " · ${misses[it]}期" }, color = Muted, fontSize = 12.sp)
            Text("遗漏按当前分析窗口内的开奖期数计算", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
        }
        CardBox {
            Label("号码结构特征")
            data.firstOrNull()?.let { d ->
                val sorted = d.front.sorted(); val odd = d.front.count { it % 2 == 1 }; val bigLimit = if (game.digits) 5 else (game.frontMax + 1) / 2
                val big = d.front.count { it >= bigLimit }; val runs = sorted.distinct().zipWithNext().count { (a,b) -> b == a + 1 }
                Text("最新一期 ${d.issue}：和值 ${d.front.sum()} · 跨度 ${(sorted.lastOrNull() ?: 0) - (sorted.firstOrNull() ?: 0)} · 奇偶 $odd:${d.front.size-odd} · 大小 $big:${d.front.size-big} · 连号 $runs 组", color = Ink, fontSize = 13.sp)
            }
            Text("近 40 期和值走势", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)); MiniChart(recent.map { it.front.sum().toFloat() }, Red)
            Text("近 40 期跨度走势", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp)); MiniChart(recent.map { ((it.front.maxOrNull() ?: 0) - (it.front.minOrNull() ?: 0)).toFloat() }, Blue)
        }
        CardBox {
            Label("号码遗漏走势")
            ChipRow((firstNumber..game.frontMax).map { if (game.digits) "$it" else "%02d".format(it) }, if (game.digits) "$selected" else "%02d".format(selected)) { selected = it.toInt() }
            val trend = recent.mapIndexed { idx, _ -> recent.drop(idx).indexOfFirst { selected in it.front }.let { if (it < 0) (recent.size - idx).toFloat() else it.toFloat() } }
            MiniChart(trend, Green)
        }
        Text("历史频率、遗漏和回测只描述过往样本，不能预测随机开奖结果。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
fun GameHistory(game: LotteryGame, draws: List<GameDraw>, syncing: Boolean, syncMessage: String, syncTime: String, sync: () -> Unit, scroll: androidx.compose.foundation.ScrollState) {
    var filter by remember(game.code) { mutableStateOf("最近30期") }
    var query by remember(game.code) { mutableStateOf("") }
    var year by remember(game.code) { mutableStateOf("全部年份") }
    val yearOptions = remember(draws) { listOf("全部年份") + draws.map { it.date.take(4) }.distinct().sortedDescending() }
    val years = when(filter) { "近3年" -> 3; "近5年" -> 5; "近10年" -> 10; else -> 1 }
    val count = when(filter) { "最近50期" -> 50; "最近100期" -> 100; else -> 30 }
    val filtered = draws.filter { (year == "全部年份" || it.date.startsWith(year)) && (query.isBlank() || it.issue.contains(query) || it.date.contains(query)) }
        .let { if (filter.startsWith("近")) { val cutoff = (draws.firstOrNull()?.date?.take(4)?.toIntOrNull() ?: LocalDate.now().year) - years + 1; it.filter { d -> (d.date.take(4).toIntOrNull() ?: 0) >= cutoff } } else it.take(count) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp)) {
        Header("历史数据", "${game.name} · ${draws.size} 期 · 官方来源数据")
        CardBox {
            SyncLine(syncing, syncMessage, syncTime, sync); Spacer(Modifier.height(8.dp))
            ChipRow(listOf("最近30期", "最近50期", "最近100期", "近1年", "近3年", "近5年", "近10年"), filter) { filter = it }
            Spacer(Modifier.height(10.dp)); ChipRow(yearOptions, year) { year = it }
            Spacer(Modifier.height(10.dp)); OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("查询期号或日期") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            Text("找到 ${filtered.size} 期", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        filtered.forEach { draw ->
            CardBox {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${draw.issue}期", color = Ink, fontWeight = FontWeight.Bold)
                    Text(draw.date, color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp)); GameDrawNumbers(draw, game)
                Text("和值 ${draw.front.sum()} · 跨度 ${(draw.front.maxOrNull() ?: 0) - (draw.front.minOrNull() ?: 0)}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun GameDataNotice(game: LotteryGame) {
    PageScroll {
        Header("${game.name}数据", "开奖历史暂未接入")
        CardBox {
            Label("当前支持情况")
            Text("${game.name} 的号码规则和智能选号已可使用。官方历史开奖数据目前只接入双色球，因此本页不会展示双色球数据作为${game.name}的历史统计。", color = Ink, fontSize = 14.sp)
            Text("切换回双色球即可查看官方开奖历史、频率分析和回测数据。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
fun Home(draws: List<Draw>, gameDraws: List<GameDraw>, pinned: Ticket?, quick: List<Ticket>, years: Int, amount: Int, mode: String, game: LotteryGame, themeIndex: Int,
                 setYears: (Int) -> Unit, setAmount: (Int) -> Unit, setMode: (String) -> Unit,
                 generate: () -> Unit, syncTime: String, setGame: (String) -> Unit, setTheme: (Int) -> Unit) {
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(game.code) { while (true) { clockNow = System.currentTimeMillis(); delay(1000) } }
    val countdown = remember(game.code, clockNow) { lotteryCountdown(game, clockNow) }
    PageScroll {
        Header("彩票智析", "统计模型参考 · ${game.name}", syncTime)
        CardBox {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("本期推荐号码", color = Muted, fontSize = 13.sp); Text("AI 集成 · 候选池择优", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Text("${if (game.code == "ssq") draws.size else gameDraws.size}期数据", color = Green, fontSize = 11.sp)
            }
            Spacer(Modifier.height(16.dp))
            if (pinned != null) {
                TicketNumbers(pinned)
                Text("结构评分 ${pinned.score} / 100 · 从 250 组候选中择优 · 不代表中奖概率", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            } else Text("历史数据准备中", color = Muted)
        }
        CardBox {
            Label("${game.name} · 开奖信息")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("下一期开奖", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(countdown.drawAt.format(DateTimeFormatter.ofPattern("M月d日 E HH:mm:ss", java.util.Locale.CHINA)), color = Green, fontSize = 12.sp)
            }
            Text("距离开奖  ${formatCountdown(countdown.secondsToDraw)}", color = Green, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("销售截止 ${countdown.saleCutoff.format(DateTimeFormatter.ofPattern("M月d日 HH:mm:ss", java.util.Locale.CHINA))}", color = Muted, fontSize = 11.sp)
                Text(if (countdown.secondsToSale == 0L) "本期销售已截止" else "剩余 ${formatCountdown(countdown.secondsToSale)}", color = if (countdown.secondsToSale == 0L) Red else Amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("按常规开奖日历计算，休市及特别安排以官方公告为准。", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            if ((game.code == "ssq" && draws.isNotEmpty()) || (game.code != "ssq" && gameDraws.isNotEmpty())) {
                val drawDate = if (game.code == "ssq") draws.first().date else gameDraws.first().date
                val drawIssue = if (game.code == "ssq") draws.first().issue else gameDraws.first().issue
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFFEDF1EE))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("最近一期官方开奖", color = Ink, fontWeight = FontWeight.Bold); Text("$drawDate · ${drawIssue}期", color = Muted, fontSize = 12.sp) }
                Spacer(Modifier.height(12.dp)); if (game.code == "ssq") Numbers(draws.first().reds, draws.first().blue, true) else GameDrawNumbers(gameDraws.first(), game)
            } else {
                Text("等待首次同步官方开奖数据。", color = Muted, fontSize = 12.sp)
            }
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
                        TicketNumbers(ticket, true)
                    }
                    Text("结构评分 ${ticket.score}", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
                }
            }
        }
        CardBox {
            Label("切换彩种")
            ChipRow(LotteryGames.all.map { it.name }, game.name) { name -> setGame(LotteryGames.all.first { it.name == name }.code) }
        }
        CardBox {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("主题颜色", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(ThemePalette[themeIndex].first, color = Green, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ThemePalette.forEachIndexed { index, (name, color) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { setTheme(index) }) {
                        Box(Modifier.size(if (themeIndex == index) 29.dp else 25.dp).background(color, CircleShape)
                            .then(if (themeIndex == index) Modifier.border(2.dp, color.copy(alpha = .42f), CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                            if (themeIndex == index) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(name, color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        Text("彩票开奖结果具有随机性，统计与历史回测不代表未来中奖概率。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

@Composable
fun Analysis(draws: List<Draw>, years: Int, setYears: (Int) -> Unit) {
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
fun History(draws: List<Draw>, syncing: Boolean, message: String, time: String, onSync: () -> Unit, scroll: androidx.compose.foundation.ScrollState) {
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
internal fun Smart(draws: List<Draw>, gameDraws: List<GameDraw>, years: Int, amount: Int, mode: String, game: LotteryGame, setYears: (Int) -> Unit, setAmount: (Int) -> Unit,
                  setMode: (String) -> Unit, generate: () -> Unit, generateFiltered: (TicketFilters) -> Unit, tickets: List<Ticket>,
                  apiKey: String, setApiKey: (String) -> Unit, modelId: String, setModelId: (String) -> Unit,
                  saveAiConfig: (String, String) -> Unit, aiBusy: Boolean, aiMessage: String, aiReason: String,
                  generateDoubao: (TicketFilters?) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var generatePressed by remember { mutableStateOf(false) }
    val minPossibleSum = if (game.digits) 0 else game.frontCount * (game.frontCount + 1) / 2
    val maxPossibleSum = if (game.digits) game.frontCount * 9 else game.frontCount * (2 * game.frontMax - game.frontCount + 1) / 2
    var sumMin by remember(game.code) { mutableStateOf(if (game.digits) "0" else "60") }
    var sumMax by remember(game.code) { mutableStateOf(if (game.digits) maxPossibleSum.toString() else "150") }
    var oddChoice by remember { mutableStateOf("1–5") }
    var bigChoice by remember { mutableStateOf("1–5") }
    var maxRunsChoice by remember { mutableStateOf("最多2组") }
    val ssqResult = remember(draws, mode, years, game.code) { if (game.code == "ssq") LotteryEngine.backtest(draws, mode, years) else null }
    val otherResult = remember(gameDraws, mode, years, game.code) { if (game.code != "ssq") LotteryEngine.backtestGame(gameDraws, game, mode, years) else null }
    PageScroll {
        Header("智能选号", "${game.name} · 多模型评分 · 自定义筛选")
        CardBox {
            Label("豆包大模型选号")
            Text("豆包会根据本地历史频率、近期走势和候选号码池进行筛选。请填写已在火山方舟账号开通的模型 ID，或已创建的推理接入点 ID（Endpoint ID）。首次调用可能产生方舟费用。", color = Muted, fontSize = 12.sp)
            OutlinedTextField(value = modelId, onValueChange = setModelId, label = { Text("模型 ID / Endpoint ID") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(value = apiKey, onValueChange = setApiKey, label = { Text("火山方舟 API Key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { saveAiConfig(apiKey.trim(), modelId.trim()) }, enabled = !aiBusy) { Text("加密保存配置") }
            }
            Button(onClick = { generateDoubao(if (custom) currentFilters(sumMin, sumMax, minPossibleSum, maxPossibleSum, oddChoice, bigChoice, maxRunsChoice) else null) },
                enabled = !aiBusy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(15.dp)) {
                Text(if (aiBusy) "豆包正在分析…" else "✦  豆包 AI 分析并生成 $amount 组")
            }
            if (aiMessage.isNotBlank()) Text(aiMessage, color = if (aiMessage.startsWith("豆包选号失败")) Red else Green, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            if (aiReason.isNotBlank()) Text("模型分析：$aiReason", color = Ink, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            Text("密钥使用 Android Keystore 加密保存在本机，不写入项目源码。", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (tickets.isNotEmpty()) CardBox {
            Label("本次候选 · ${tickets.size} 组")
            tickets.forEachIndexed { index, ticket ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("第 ${index + 1} 组", color = Ink, fontWeight = FontWeight.Bold); Text("结构评分 ${ticket.score}", color = Green, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp)); TicketNumbers(ticket, true)
                if (index < tickets.lastIndex) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFFEFF2EF))
            }
        }
        CardBox {
            Label("选号设置与自定义筛选")
            Text("策略", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(7.dp)); ChipRow(ModelOptions, mode, setMode)
            Spacer(Modifier.height(13.dp)); Text("数据窗口", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(7.dp)); ChipRow((1..10).map { "${it}年" }, "${years}年") { setYears(it.dropLast(1).toInt()) }
            Spacer(Modifier.height(13.dp)); Text("候选组数", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(7.dp)); ChipRow((1..10).map { "${it}组" }, "${amount}组") { setAmount(it.dropLast(1).toInt()) }
            Text("可按和值、奇偶、大号比例与连号数量筛选", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(custom, { custom = it }); Text("启用自定义筛选", color = Ink, fontSize = 13.sp) }
            if (custom) {
                Text("号码和值（$minPossibleSum–$maxPossibleSum）", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedTextField(sumMin, { sumMin = it }, label = { Text("最小值") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(sumMax, { sumMax = it }, label = { Text("最大值") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                }
                Text("奇数红球个数", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                ChipRow(listOf("不限", "1–5", "2–4", "3个"), oddChoice) { oddChoice = it }
                val bigThreshold = if (game.digits) 5 else if (game.code == "dlt") 18 else 17
                Text("大号（$bigThreshold–${if (game.digits) 9 else game.frontMax}）个数", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                ChipRow(listOf("不限", "1–5", "2–4", "3个"), bigChoice) { bigChoice = it }
                Text("连续号上限", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                ChipRow(listOf("不限", "最多1组", "最多2组"), maxRunsChoice) { maxRunsChoice = it }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                generatePressed = true
                if (custom) {
                    val min = sumMin.toIntOrNull()?.coerceIn(minPossibleSum, maxPossibleSum) ?: minPossibleSum
                    val max = (sumMax.toIntOrNull() ?: maxPossibleSum).coerceIn(min, maxPossibleSum)
                    fun range(choice: String) = when (choice) { "1–5" -> 1..5; "2–4" -> 2..4; "3个" -> 3..3; else -> 0..6 }
                    val maxRuns = when (maxRunsChoice) { "最多1组" -> 1; "最多2组" -> 2; else -> 5 }
                    generateFiltered(TicketFilters(min..max, range(oddChoice), range(bigChoice), maxRuns))
                } else generate()
            },
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) { Text("生成 $amount 组候选", fontWeight = FontWeight.Bold) }
            if (generatePressed && tickets.isEmpty()) Text("当前条件没有找到组合，请放宽和值、比例或连号限制。", color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            if (tickets.isNotEmpty() && tickets.size < amount) Text("符合条件的组合只有 ${tickets.size} 组，可放宽筛选条件。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
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
        if (ssqResult != null) {
            CardBox {
                Label("${game.name}历史回测 · 最近 ${ssqResult.draws} 期")
                Text("红球命中 ${ssqResult.redHits} 次 · 蓝球命中 ${ssqResult.blueHits} 次", color = Ink, fontSize = 14.sp)
                Text("每期平均命中 ${"%.2f".format(ssqResult.meanHits)} 个号码", color = Green, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text("命中分布：0 个 ${ssqResult.hit0}期 · 1 个 ${ssqResult.hit1}期 · 2 个 ${ssqResult.hit2}期 · 3 个及以上 ${ssqResult.hit3Plus}期", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Text("逐期向前滚动验证，仅使用当期之前的历史数据。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        } else if (otherResult != null && otherResult.draws > 0) {
            CardBox {
                Label("${game.name}历史回测 · 最近 ${otherResult.draws} 期")
                Text(if (game.digits) "位置号码累计命中 ${otherResult.frontHits} 位" else "前区累计命中 ${otherResult.frontHits} 个 · 后区累计命中 ${otherResult.backHits} 个", color = Ink, fontSize = 14.sp)
                Text("每期开奖平均命中 ${"%.2f".format(otherResult.meanHits)} 个", color = Green, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text("完全命中 ${otherResult.exactHits} 期", color = Ink, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Text("命中分布：${otherResult.hitDistribution.toSortedMap().entries.joinToString(" · ") { "${it.key}个 ${it.value}期" }}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Text("按期滚动验证，只用目标期之前的数据；数字型彩票按位置命中统计。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            CardBox { Label("${game.name}历史回测"); Text("本地历史开奖数据不足，请先在历史数据页同步。", color = Muted, fontSize = 12.sp) }
        }
    }
}

private data class DrawCountdown(val drawAt: ZonedDateTime, val saleCutoff: ZonedDateTime, val secondsToDraw: Long, val secondsToSale: Long)

private fun lotteryCountdown(game: LotteryGame, nowMillis: Long): DrawCountdown {
    val zone = ZoneId.of("Asia/Shanghai")
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val (days, cutoffTime, drawTime) = when (game.code) {
        "ssq" -> Triple(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY), LocalTime.of(20, 0), LocalTime.of(21, 15))
        "dlt" -> Triple(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY), LocalTime.of(21, 0), LocalTime.of(21, 25))
        else -> Triple(DayOfWeek.values().toSet(), LocalTime.of(if (game.code == "fc3d") 20 else 21, 0), LocalTime.of(21, if (game.code == "fc3d") 15 else 25))
    }
    for (offset in 0L..8L) {
        val date = now.toLocalDate().plusDays(offset)
        if (date.dayOfWeek !in days) continue
        val drawAt = ZonedDateTime.of(date, drawTime, zone)
        if (!drawAt.isAfter(now)) continue
        val saleCutoff = ZonedDateTime.of(date, cutoffTime, zone)
        val toDraw = Duration.between(now, drawAt).seconds.coerceAtLeast(0)
        val toSale = if (now.isBefore(saleCutoff)) Duration.between(now, saleCutoff).seconds.coerceAtLeast(0) else 0L
        return DrawCountdown(drawAt, saleCutoff, toDraw, toSale)
    }
    val fallback = ZonedDateTime.of(now.toLocalDate().plusDays(1), drawTime, zone)
    val cutoff = ZonedDateTime.of(fallback.toLocalDate(), cutoffTime, zone)
    return DrawCountdown(fallback, cutoff, Duration.between(now, fallback).seconds.coerceAtLeast(0), Duration.between(now, cutoff).seconds.coerceAtLeast(0))
}

private fun formatCountdown(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val days = safe / 86_400
    val hours = safe % 86_400 / 3_600
    val minutes = safe % 3_600 / 60
    val secs = safe % 60
    return "%02d天 %02d:%02d:%02d".format(days, hours, minutes, secs)
}

private fun drawCalendar(latestDate: String): Pair<String, String> {
    if (latestDate.isBlank()) return "" to "待同步"
    return runCatching {
        val date = LocalDate.parse(latestDate, DateTimeFormatter.ISO_LOCAL_DATE)
        val next = (1..7).map { date.plusDays(it.toLong()) }.first { it.dayOfWeek.value in listOf(2, 4, 7) }
        date.toString() to next.format(DateTimeFormatter.ofPattern("M月d日"))
    }.getOrDefault(latestDate to "以官网公告为准")
}


