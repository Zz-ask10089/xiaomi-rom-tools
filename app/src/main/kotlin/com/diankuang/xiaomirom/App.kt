package com.diankuang.xiaomirom

import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private enum class Page { INDEX, DETAIL, PART }

/** 一次“进入提取页”的导航。普通类按实例身份 remember，且自带独立取消标志。 */
class ExtractReq(val urls: List<String>, val fileName: String) {
    val cancel = AtomicBoolean(false)
}

/** 预计算的索引行：小写 haystack 一次算好，搜索时只做 contains，不再全站正则。 */
private class IdxRow(val dev: String, val devKey: String, val hayKey: String, val item: Parser.Item)

@Composable
fun App() {
    val context = LocalContext.current
    // 边到边 / 系统栏配色（跟随系统深浅色）
    val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
    val activity = context as? androidx.activity.ComponentActivity
    DisposableEffect(sysDark) {
        if (activity != null) {
            with(activity) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { sysDark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { sysDark },
                )
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    window.isNavigationBarContrastEnforced = false
                }
            }
        }
        onDispose { }
    }
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller) {
        MainScreen()
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    var page by remember { mutableStateOf(Page.INDEX) }
    var indexItems by remember { mutableStateOf<List<Parser.Item>?>(null) }
    var indexError by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    // 搜索防抖：输入停顿 200ms 才触发过滤，避免每敲一键全站重算
    var deferredQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(200)
        deferredQuery = query
    }
    // 预计算行（正则只在数据变化时跑一次，且在 IO 线程）
    var rows by remember { mutableStateOf<List<IdxRow>>(emptyList()) }
    LaunchedEffect(indexItems) {
        val items = indexItems ?: return@LaunchedEffect
        rows = withContext(Dispatchers.Default) {
            items.map {
                val dr = Parser.devAndRegion(it.title)
                IdxRow(
                    dev = dr[0],
                    devKey = dr[0].lowercase(Locale.ROOT),
                    hayKey = (dr[0] + " " + dr[1]).lowercase(Locale.ROOT),
                    item = it,
                )
            }
        }
    }

    var devName by remember { mutableStateOf("") }
    var regionItems by remember { mutableStateOf<List<Parser.Item>>(emptyList()) }
    var showRegionPicker by remember { mutableStateOf(false) }

    var roms by remember { mutableStateOf<List<Parser.Rom>>(emptyList()) }
    var romTab by remember { mutableStateOf(0) }
    var detailTitle by remember { mutableStateOf("") }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf("") }

    var linkRom by remember { mutableStateOf<Parser.Rom?>(null) }
    var linkLoading by remember { mutableStateOf(false) }

    var extractReq by remember { mutableStateOf<ExtractReq?>(null) }
    var showAbout by remember { mutableStateOf(false) }

    // ---------------- 云更新（GitHub Releases：标题/标签即版本号） ----------------
    val curVerName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "0"
    }
    var upInfo by remember { mutableStateOf<Updater.Info?>(null) }
    var upBusy by remember { mutableStateOf(false) }      // 手动检查中
    var upResult by remember { mutableStateOf("") }       // 手动检查结果提示
    var upAuto by remember { mutableStateOf(true) }       // 自动检查不重复打扰

    fun runUpdateCheck(auto: Boolean) {
        if (upBusy) return
        upBusy = true
        upResult = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Updater.check(curVerName) }
            }
            upBusy = false
            r.onSuccess { info ->
                if (info != null) {
                    if (!(auto && Updater.seenVer(context) == info.version)) {
                        upInfo = info
                        upAuto = auto
                        Updater.markSeenVer(context, info.version)
                    }
                } else if (!auto) {
                    upResult = "已是最新版本 v$curVerName"
                }
            }.onFailure {
                if (!auto) upResult = "检查失败：请检查网络或稍后再试"
            }
        }
    }

    // 启动后静默检查一次（失败不打扰）
    LaunchedEffect(Unit) { runUpdateCheck(auto = true) }

    // 系统返回键：DETAIL→首页、提取页→DETAIL（并请求停止提取），不再直接退出 App
    BackHandler(enabled = page != Page.INDEX) {
        when (page) {
            Page.DETAIL -> page = Page.INDEX
            Page.PART -> {
                extractReq?.cancel?.set(true)
                page = Page.DETAIL
            }
            else -> {}
        }
    }

    // 机型分组（跟随搜索词）：基于预计算行，只做 lowercase contains
    val groups = remember(rows, deferredQuery) {
        val toks = deferredQuery.trim().lowercase(Locale.ROOT)
            .split(" ").filter { it.isNotEmpty() }
        val map = LinkedHashMap<String, MutableList<Parser.Item>>()
        for (r in rows) {
            val hit = toks.isEmpty() || toks.all { t -> r.hayKey.contains(t) } ||
                    toks.all { t -> r.devKey.contains(t) }
            if (hit) map.getOrPut(r.dev) { mutableListOf() }.add(r.item)
        }
        map.map { it.key to it.value }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val html = Http.fetch(Http.BASE + "/series/")
                Parser.parseIndex(html) to null
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            }
        }.let { (items, err) ->
            if (items != null) indexItems = items else indexError = err ?: "未知错误"
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            if (page == Page.INDEX) {
                TopAppBar(
                    title = "小米ROM直链",
                    actions = {
                        IconButton(onClick = { showAbout = true }) {
                            Icon(imageVector = MiuixIcons.Info, contentDescription = "关于")
                        }
                    },
                )
            } else if (page == Page.PART) {
                TopAppBar(
                    title = "在线提取分区",
                    navigationIcon = {
                        IconButton(onClick = {
                            extractReq?.cancel?.set(true)
                            page = Page.DETAIL
                        }) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = detailTitle,
                    navigationIcon = {
                        IconButton(onClick = { page = Page.INDEX }) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (roms.isNotEmpty()) {
                            IconButton(onClick = {
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        try {
                                            Exporter.export(context, roms) to null
                                        } catch (e: Exception) {
                                            null to (e.message ?: e.javaClass.simpleName)
                                        }
                                    }
                                    val f = res.first
                                    if (f != null) {
                                        try {
                                            Exporter.share(context, f, "text/csv")
                                            snack.showSnackbar("已导出 ${f.name}（${f.length()} 字节）")
                                        } catch (e: Exception) {
                                            snack.showSnackbar("文件在 ${f.absolutePath}")
                                        }
                                    } else {
                                        snack.showSnackbar("导出失败：${res.second}")
                                    }
                                }
                            }) {
                                Icon(imageVector = MiuixIcons.Share, contentDescription = "导出CSV")
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        // 注意：所有 OverlayDialog 必须在 Scaffold 内容里，
        // 否则注册进无人渲染的弹窗队列 → 点击机型没反应（v2.0 的 bug）。
        Box(Modifier.fillMaxSize().padding(padding)) {
            // 页面切换动画：进入下一层从右侧滑入，返回向左滑回
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(tween(260)) { w -> if (forward) w else -w } +
                            fadeIn(tween(260))) togetherWith
                    (slideOutHorizontally(tween(220)) { w -> if (forward) -w else w } +
                            fadeOut(tween(220)))
                },
                label = "page",
            ) { p ->
                when (p) {
                Page.INDEX -> Column(Modifier.fillMaxSize()) {
                    InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { },
                        expanded = false,
                        onExpandedChange = { },
                        label = "搜索机型，如 turbo / 14 pro / pad",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    val n = indexItems?.size
                    SmallTitle(
                        text = when {
                            indexError.isNotEmpty() -> "目录加载失败：$indexError（返回重进）"
                            n == null -> "正在加载全站机型目录 ..."
                            else -> "匹配 ${groups.size} 个机型 ｜ 全站 $n 个地区页"
                        },
                    )
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(groups, key = { it.first }) { (dev, its) ->
                            Card(Modifier.padding(horizontal = 16.dp)) {
                                BasicComponent(
                                    title = dev,
                                    summary = "${its.size} 个地区",
                                    onClick = {
                                        devName = dev
                                        regionItems = its
                                        showRegionPicker = true
                                    },
                                )
                            }
                        }
                    }
                }

                Page.DETAIL -> Box(Modifier.fillMaxSize()) {
                    if (detailLoading) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else if (roms.isEmpty()) {
                        Text(
                            text = detailError.ifEmpty { "没有解析到版本记录" },
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    } else {
                        // TabRow 与列表必须纵向排（Box 会叠压挡住标签）
                        Column(Modifier.fillMaxSize()) {
                        // 顶部按 卡刷/线刷 分组；某类为空则只显示存在的类
                        val rec = remember(roms) { roms.filter { it.type == "Recovery 卡刷包" } }
                        val fst = remember(roms) { roms.filter { it.type == "Fastboot 线刷包" } }
                        val tabRoms = remember(roms) { roms.filter { it.type.isEmpty() } }
                        val tabList = buildList {
                            if (rec.isNotEmpty()) add("卡刷 ${rec.size}")
                            if (fst.isNotEmpty() || tabRoms.isNotEmpty()) add("线刷 ${fst.size + tabRoms.size}")
                            if (isEmpty()) add("全部 ${roms.size}")
                        }
                        val showRec = romTab == 0 || tabList.size == 1
                        val shown = when {
                            tabList.size == 1 -> roms
                            showRec -> rec
                            else -> fst + tabRoms
                        }
                        if (tabList.size > 1) {
                            TabRow(
                                tabs = tabList,
                                selectedTabIndex = romTab,
                                onTabSelected = { romTab = it },
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(shown) { r ->
                                Card(Modifier.padding(horizontal = 16.dp)) {
                                    BasicComponent(
                                        title = r.version,
                                        summary = listOf(r.type, r.size, r.date,
                                                if (r.android.isNotEmpty()) "Android " + r.android else "")
                                            .filter { it.isNotEmpty() }.joinToString("  ·  "),
                                        onClick = {
                                        linkRom = r
                                        if (r.cdnUrls.isEmpty() && !r.downloadPage.isNullOrEmpty()) {
                                            linkLoading = true
                                            scope.launch {
                                                val err = withContext(Dispatchers.IO) {
                                                    try {
                                                        r.cdnUrls.addAll(
                                                            Parser.parseDownloadPage(
                                                                Http.fetch(r.downloadPage),
                                                                r.fragment, r.version))
                                                        null
                                                    } catch (e: Exception) {
                                                        e.message ?: e.javaClass.simpleName
                                                    }
                                                }
                                                linkLoading = false
                                                if (err != null) snack.showSnackbar("解析失败：$err")
                                                else if (r.cdnUrls.isEmpty()) snack.showSnackbar("没有解析到直链")
                                            }
                                        }
                                        },
                                    )
                                }
                            }
                            }
                        }
                    }
                }

                Page.PART -> extractReq?.let { req ->
                    ExtractPage(req = req, snack = snack)
                }
                }
            }

            // ---------------- 地区选择弹窗 ----------------
            if (showRegionPicker) {
                OverlayDialog(
                    show = true,
                    title = devName,
                    summary = "${regionItems.size} 个地区页",
                    onDismissRequest = { showRegionPicker = false },
                ) {
                    Column(
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    ) {
                        regionItems.forEachIndexed { idx, it2 ->
                            if (idx > 0) HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                            BasicComponent(
                                title = Parser.devAndRegion(it2.title)[1],
                                onClick = {
                                    showRegionPicker = false
                                    detailTitle = Parser.devAndRegion(it2.title)[1]
                                    page = Page.DETAIL
                                    romTab = 0
                                    detailLoading = true
                                    roms = emptyList()
                                    detailError = ""
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            try {
                                                Parser.parseDetail(Http.fetch(it2.url), it2.url) to null
                                            } catch (e: Exception) {
                                                null to (e.message ?: e.javaClass.simpleName)
                                            }
                                        }
                                        detailLoading = false
                                        res.let { (list, err) ->
                                            if (list != null) roms = list else detailError = "加载失败：$err"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // ---------------- 关于弹窗 ----------------
            if (showAbout) {
                OverlayDialog(
                    show = true,
                    title = "小米ROM直链 v2.8",
                    summary = "小米官方 ROM 直链浏览 / CSV 导出 / payload.bin 在线提取",
                    onDismissRequest = { showAbout = false },
                ) {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            text = "检查更新",
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                        Card(Modifier.fillMaxWidth()) {
                            BasicComponent(
                                title = if (upBusy) "正在检查…" else "检查新版本",
                                summary = when {
                                    upBusy -> "v$curVerName · 正在连接 GitHub Releases"
                                    upResult.isNotBlank() -> upResult
                                    else -> "v$curVerName · 点击检查 GitHub Releases 最新版"
                                },
                                onClick = { runUpdateCheck(auto = false) },
                            )
                        }
                        Text(
                            text = "数据来源",
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                        Card(Modifier.fillMaxWidth()) {
                            BasicComponent(
                                title = "xiaomirom.com",
                                summary = "本站数据来自小米官方固件的第三方镜像索引",
                                onClick = { Utils.open(context, Http.BASE) },
                            )
                        }
                        Text(
                            text = "开源组件",
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                        )
                        val libs = remember {
                            listOf(
                                Triple("Miuix UI 0.9.4", "MIUI/HyperOS 风格 Compose 界面组件",
                                    "https://github.com/compose-miuix-ui/miuix"),
                                Triple("Jsoup 1.17.2", "HTML 解析",
                                    "https://jsoup.org"),
                                Triple("XZ for Java 1.10", "LZMA2 解压（payload 数据块）",
                                    "https://tukaani.org/xz/java.html"),
                                Triple("Apache Commons Compress 1.27.1", "bzip2 解压（payload 数据块）",
                                    "https://commons.apache.org/compress"),
                                Triple("Apache Commons IO 2.16.1", "Commons Compress 运行时依赖",
                                    "https://commons.apache.org/io"),
                                Triple("Jetpack Compose / Kotlin", "Android 官方 UI 框架与语言",
                                    "https://developer.android.com/jetpack"),
                            )
                        }
                        libs.forEach { (name, desc, url) ->
                            Card(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                            ) {
                                BasicComponent(
                                    title = name,
                                    summary = desc,
                                    onClick = { Utils.open(context, url) },
                                )
                            }
                        }
                        Text(
                            text = "参考与致谢",
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                        )
                        val refs = remember {
                            listOf(
                                Triple("Payload Dumper Compose",
                                    "在线提取 payload.bin 分区的设计参考（本应用为独立实现）",
                                    "https://github.com/rcmiku/Payload-Dumper-Compose"),
                                Triple("payload-dumper-go",
                                    "payload.bin 提取格式的先行开源实现",
                                    "https://github.com/ssut/payload-dumper-go"),
                            )
                        }
                        refs.forEach { (name, desc, url) ->
                            Card(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                            ) {
                                BasicComponent(
                                    title = name,
                                    summary = desc,
                                    onClick = { Utils.open(context, url) },
                                )
                            }
                        }
                        Text(
                            text = "以上组件库均为 Apache-2.0 / MIT 开源许可，参考项目版权归原作者；本项目开源免费，禁止售卖。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
            }

            // ---------------- 发现新版本弹窗 ----------------
            upInfo?.let { info ->
                OverlayDialog(
                    show = true,
                    title = "发现新版本 ${info.version}",
                    summary = "当前 v$curVerName → 最新 ${info.version}",
                    onDismissRequest = { upInfo = null },
                ) {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        if (info.notes.isNotBlank()) {
                            Text(
                                text = info.notes,
                                style = MiuixTheme.textStyles.body2,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            if (info.apk.isNotBlank()) {
                                BasicComponent(
                                    title = "下载 APK",
                                    summary = "Release 附件，直接下载安装",
                                    onClick = { Utils.open(context, info.apk) },
                                )
                                HorizontalDivider()
                            }
                            BasicComponent(
                                title = "查看 Release 页面",
                                summary = info.page,
                                onClick = { Utils.open(context, info.page) },
                            )
                        }
                    }
                }
            }

            // ---------------- 直链弹窗 ----------------
            linkRom?.let { rom ->
                OverlayDialog(
                    show = true,
                    title = rom.version,
                    summary = rom.type,
                    onDismissRequest = { linkRom = null },
                ) {
                    Column(
                        Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (linkLoading && rom.cdnUrls.isEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        rom.cdnUrls.forEachIndexed { i, url ->
                            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                val host = url.substringAfter("://").substringBefore("/")
                                val fn = Parser.fileNameOf(url)
                                Text(
                                    text = if (fn.length > 42) fn.take(22) + "…" + fn.takeLast(18) else fn,
                                    style = MiuixTheme.textStyles.body1,
                                )
                                Text(
                                    text = "源${i + 1} · $host",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        text = "复制",
                                        onClick = { Utils.copy(context, url); },
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        text = "打开",
                                        onClick = { Utils.open(context, url) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.textButtonColorsPrimary(),
                                    )
                                    var sizeText by remember(url) { mutableStateOf("测大小") }
                                    TextButton(
                                        text = sizeText,
                                        onClick = {
                                            scope.launch {
                                                sizeText = "..."
                                                val r = withContext(Dispatchers.IO) {
                                                    try { Http.probeSize(url) } catch (e: Exception) { -1L }
                                                }
                                                sizeText = if (r > 0) Utils.fmtSize(r) else "失败"
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                // 只有 Recovery 卡刷包内含 payload.bin；提取入口独立成区，
                                // 不再和 复制/打开/测大小 混在按钮行里
                            }
                        }
                        if (rom.cdnUrls.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(Modifier.padding(horizontal = 4.dp))
                            Spacer(Modifier.height(10.dp))
                            // 只有 Recovery 卡刷包内含 payload.bin（与 Python 版一致）
                            if (rom.type == "Recovery 卡刷包") {
                                TextButton(
                                    text = "在线提取分区（payload.bin）",
                                    onClick = {
                                        val us = rom.cdnUrls.toList()
                                        linkRom = null
                                        extractReq = ExtractReq(us, Parser.fileNameOf(us[0]))
                                        page = Page.PART
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                )
                            } else if (rom.type == "Fastboot 线刷包") {
                                Text(
                                    text = "线刷包不含 payload.bin，无法在线提取分区",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                text = "复制全部直链",
                                onClick = { Utils.copy(context, rom.cdnUrls.joinToString("\n")) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================ 在线提取分区页
@Composable
private fun ExtractPage(
    req: ExtractReq,
    snack: SnackbarHostState,
) {
    val cancelRef = req.cancel
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var meta by remember(req) { mutableStateOf<Payload.Meta?>(null) }
    var loadErr by remember(req) { mutableStateOf("") }
    val checked = remember(req) { mutableStateMapOf<String, Boolean>() }
    var running by remember(req) { mutableStateOf(false) }
    var curName by remember(req) { mutableStateOf("") }
    var progDone by remember(req) { mutableStateOf(0L) }
    var progTotal by remember(req) { mutableStateOf(0L) }
    var progRate by remember(req) { mutableStateOf(0L) }
    var results by remember(req) {
        mutableStateOf<List<Pair<String, String>>>(emptyList())  // 分区名 → OK:路径 / ERR:信息 / RETRY:信息
    }

    // 多源：curUrl 供 UI；srcRef 供后台提取线程读；失败自动换源
    var curUrl by remember(req) { mutableStateOf(req.urls[0]) }
    var showSrcPicker by remember(req) { mutableStateOf(false) }
    val failUrls = remember(req) { mutableStateOf<List<String>>(emptyList()) }
    val srcRef = remember(req) { java.util.concurrent.atomic.AtomicReference(req.urls[0]) }
    val curIdx = req.urls.indexOf(curUrl).coerceAtLeast(0)
    var probe by remember(req) { mutableStateOf(0) }   // “重新尝试”计数

    LaunchedEffect(req, probe) {
        if (running) return@LaunchedEffect
        cancelRef.set(false)
        meta = null
        loadErr = ""
        var u = curUrl
        while (true) {
            val r = withContext(Dispatchers.IO) {
                try {
                    Payload.prepare(u) to null
                } catch (e: Exception) {
                    null to (e.message ?: e.javaClass.simpleName)
                }
            }
            if (r.first != null) {
                meta = r.first
                curUrl = u; srcRef.set(u)
                return@LaunchedEffect
            }
            if (!failUrls.value.contains(u)) failUrls.value = failUrls.value + u
            val next = req.urls.firstOrNull { !failUrls.value.contains(it) }
            if (next == null) {
                loadErr = r.second ?: "未知错误"
                return@LaunchedEffect
            }
            snack.showSnackbar("源 ${req.urls.indexOf(u) + 1} 不可用，自动切换到源 ${req.urls.indexOf(next) + 1}")
            u = next
        }
    }
    // 手动换源 / 重新尝试都用 probe++ 触发上面的探测循环

    // 提取指定分区：网络失败自动换下一个源；全源失败标 RETRY 可稍后重试
    val runExtract: (List<String>) -> Unit = run@{ names0 ->
        val mm = meta ?: return@run
        if (running) return@run
        val names = names0.toSet()
        val targets = mm.parts().filter { it.name in names }
        if (targets.isEmpty()) return@run
        running = true
        results = results.filterNot { it.first in names }
        cancelRef.set(false)
        progDone = 0
        progTotal = targets.sumOf { it.download }
        progRate = 0
        scope.launch {
            val base = context.getExternalFilesDir(
                android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val dir = File(base, "payload_extracted")
            val out = withContext(Dispatchers.IO) {
                val acc = ArrayList<Pair<String, String>>()
                val localBad = ArrayList<String>()
                var u = srcRef.get()
                var done0 = 0L
                for (pt in targets) {
                    if (cancelRef.get()) break
                    curName = pt.name
                    var settled = false
                    while (!settled && !cancelRef.get()) {
                        try {
                            val path = Payload.extractOne(u, mm, pt.name, dir) { d, _, r ->
                                progDone = done0 + d
                                progRate = r
                                !cancelRef.get()
                            }
                            acc.add(pt.name to "OK:$path")
                            settled = true
                        } catch (e: Exception) {
                            if (cancelRef.get()) break
                            if (!localBad.contains(u)) localBad.add(u)
                            val next = req.urls.firstOrNull { !localBad.contains(it) }
                            if (next == null) {
                                acc.add(pt.name to "RETRY:${e.message ?: "网络失败"}")
                                settled = true
                            } else {
                                u = next
                                srcRef.set(u)
                                curUrl = u
                                val n = req.urls.indexOf(u) + 1
                                scope.launch { snack.showSnackbar("此源连接失败，自动切换到源 $n") }
                            }
                        }
                    }
                    // 当前源仍不可用（已标 RETRY）：跳过该分区继续下一个
                    if (cancelRef.get()) break
                    done0 += pt.download
                }
                acc
            }
            progRate = 0
            running = false
            results = results + out
            val ok = out.count { it.second.startsWith("OK:") }
            val retry = out.count { it.second.startsWith("RETRY:") }
            snack.showSnackbar(when {
                cancelRef.get() -> "已停止，本轮完成 $ok 个分区"
                retry > 0 && ok == 0 -> "所有源都失败：可手动换源后点“重试”"
                retry > 0 -> "完成 $ok 个，$retry 个未完成：可换源后点重试"
                else -> "完成 $ok / ${out.size} 个分区，保存在 payload_extracted"
            })
        }
    }

    val m = meta
    Column(Modifier.fillMaxSize()) {
        SmallTitle(
            text = when {
                loadErr.isNotEmpty() -> "失败：$loadErr"
                m == null -> "正在读取 payload.bin 目录（只下几 MB）..."
                else -> "整包 ${Utils.fmtSize(m.pkgSize())} · ${m.parts().size} 个分区 · 只下载所选分区用到的字节"
            },
        )
        // 下载源条：多源时可点选切换
        if (req.urls.size > 1) {
            Card(Modifier.padding(horizontal = 12.dp)) {
                BasicComponent(
                    title = "当前下载源 ${curIdx + 1} / ${req.urls.size} · " +
                            curUrl.substringAfter("://").substringBefore("/"),
                    summary = if (running) "正在提取；此源断了会自动换下一个"
                        else "共 ${req.urls.size} 个源；坏源自动跳过，点击可手动更换",
                    onClick = { if (!running) showSrcPicker = true },
                )
            }
        }
        if (loadErr.isEmpty() && m == null) {
            CircularProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
        } else if (m != null) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(m.parts(), key = { it.name }) { pt ->
                    Card(Modifier.padding(horizontal = 12.dp)) {
                        CheckboxPreference(
                            title = pt.name,
                            checked = checked[pt.name] == true,
                            onCheckedChange = { if (!running) checked[pt.name] = it },
                            summary = "镜像 ${Utils.fmtSize(pt.size)} · 需下载 ${Utils.fmtSize(pt.download)}",
                        )
                    }
                }
                if (results.isNotEmpty()) {
                    item { SmallTitle(text = if (running) "已完成" else "提取结果") }
                    items(results, key = { "r_" + it.first }) { (name, res) ->
                        Card(Modifier.padding(horizontal = 12.dp)) {
                            BasicComponent(
                                title = name,
                                summary = when {
                                    res.startsWith("OK:") -> {
                                        val f = File(res.substring(3))
                                        "已保存 ${f.name}（${Utils.fmtSize(f.length())}）"
                                    }
                                    res.startsWith("RETRY:") -> "未完成：" + res.removePrefix("RETRY:")
                                    else -> res.removePrefix("ERR:")
                                },
                                endActions = if (!running && res.startsWith("OK:")) {
                                    {
                                        TextButton(
                                            text = "分享",
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        Exporter.share(context, File(res.substring(3)),
                                                            "application/octet-stream")
                                                    } catch (e: Exception) {
                                                        snack.showSnackbar("没有可接收分享的应用")
                                                    }
                                                }
                                            },
                                        )
                                    }
                                } else if (!running && res.startsWith("RETRY:")) {
                                    {
                                        TextButton(
                                            text = "重试",
                                            onClick = { runExtract(listOf(name)) },
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }
            if (running) {
                LinearProgressIndicator(
                    progress = if (progTotal > 0)
                        (progDone.toFloat() / progTotal).coerceIn(0f, 1f) else 0f,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Text(
                    text = "$curName  ${Utils.fmtSize(progDone)} / ${Utils.fmtSize(progTotal)}" +
                            if (progRate > 0) "  ·  ${Utils.fmtSize(progRate)}/s" else "",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "全选",
                    enabled = !running,
                    onClick = { m.parts().forEach { checked[it.name] = true } },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "清空",
                    enabled = !running,
                    onClick = { checked.clear() },
                    modifier = Modifier.weight(1f),
                )
                if (running) {
                    TextButton(
                        text = "停止",
                        onClick = { cancelRef.set(true) },
                        modifier = Modifier.weight(1.4f),
                    )
                } else {
                    val sel = m.parts().filter { checked[it.name] == true }
                    TextButton(
                        text = if (sel.isEmpty()) "开始提取" else "提取 ${sel.size} 个分区",
                        enabled = sel.isNotEmpty(),
                        onClick = { runExtract(sel.map { it.name }) },
                        modifier = Modifier.weight(1.6f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
            if (!running && results.any { it.second.startsWith("RETRY:") }) {
                TextButton(
                    text = "重试未完成",
                    onClick = {
                        runExtract(results.filter { it.second.startsWith("RETRY:") }
                                   .map { it.first })
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (req.urls.size > 1)
                            "${req.urls.size} 个下载源都连不上或不支持按需提取：\n$loadErr"
                        else "这个包不支持在线提取：\n$loadErr",
                    )
                    Spacer(Modifier.height(12.dp))
                    if (req.urls.size > 1) {
                        TextButton(
                            text = "重新尝试",
                            onClick = {
                                failUrls.value = emptyList()
                                curUrl = req.urls[0]
                                srcRef.set(req.urls[0])
                                probe++
                            },
                        )
                    }
                }
            }
        }

        // ---------------- 源选择弹窗（与主弹窗同一宿主） ----------------
        if (showSrcPicker) {
            OverlayDialog(
                show = true,
                title = "选择下载源",
                summary = "同一文件的不同镜像，可任选；失败会自动跳过",
                onDismissRequest = { showSrcPicker = false },
            ) {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                ) {
                    req.urls.forEachIndexed { i, u ->
                        val host = u.substringAfter("://").substringBefore("/")
                        RadioButtonPreference(
                            title = "源 ${i + 1} · $host",
                            summary = when {
                                failUrls.value.contains(u) -> "此源曾连接失败"
                                i == curIdx -> "正在使用"
                                else -> null
                            },
                            selected = i == curIdx,
                            onClick = {
                                showSrcPicker = false
                                if (u != curUrl) {
                                    curUrl = u; srcRef.set(u)
                                    if (!running) { loadErr = ""; probe++ }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
