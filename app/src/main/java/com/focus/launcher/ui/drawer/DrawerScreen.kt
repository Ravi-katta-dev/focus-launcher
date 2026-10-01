package com.focus.launcher.ui.drawer

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.item
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.Graph
import com.focus.launcher.data.AppEntry
import com.focus.launcher.data.DayUsage
import com.focus.launcher.data.DrawerSort
import com.focus.launcher.data.Settings
import com.focus.launcher.ui.components.ChoiceDialog
import com.focus.launcher.ui.components.Label
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.components.TabChip
import com.focus.launcher.ui.components.VSpace
import com.focus.launcher.ui.components.focusTextStyle
import com.focus.launcher.ui.components.hasColourGlyphs
import com.focus.launcher.ui.components.monochrome
import com.focus.launcher.ui.components.press
import com.focus.launcher.ui.theme.LocalFocusColors
import com.focus.launcher.util.formatDuration
import com.focus.launcher.util.formatMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

private const val DAY_MS = 86_400_000L

/**
 * Page two of the launcher: a search bar and every app in a compact grid.
 * With a work profile the list splits into Personal / Work tabs; "Sort" orders it A–Z, by most
 * used or by last used. Search always covers both profiles.
 * Long-press a tile for its menu.
 */
@Composable
fun DrawerScreen(
    settings: Settings,
    apps: List<AppEntry>,
    loaded: Boolean,
    today: DayUsage?,
    query: String,
    onQueryChange: (String) -> Unit,
    isActive: Boolean,
    wantsSearchFocus: Boolean,
    onSearchFocusHandled: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onAppMenu: (AppEntry) -> Unit,
    /** A tip that is about this page, shown under the search bar until it has been done. */
    hint: String? = null,
) {
    val c = LocalFocusColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    val visible = remember(apps, settings.hidden) { apps.filter { it.key !in settings.hidden } }
    // Stripping accents is the expensive part of search; do it once per list, not per keystroke.
    val searchNames = remember(visible) { visible.associate { it.key to normalize(it.label) } }
    val searching = query.isNotBlank()

    val hasWork = remember(visible) { visible.any { it.isWorkProfile } }
    var showWork by remember { mutableStateOf(false) }
    // The tab choice only counts while there is a work profile to show.
    val workTab = hasWork && showWork
    val inProfile = remember(visible, hasWork, workTab) {
        if (hasWork) visible.filter { it.isWorkProfile == workTab } else visible
    }

    val sort = settings.drawerSort
    var sortDialog by remember { mutableStateOf(false) }
    // package -> (foreground ms over the last week, last time used). Only read when a sort needs it.
    var stats by remember { mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap()) }
    // Scrolling through the list is browsing, not typing: give the keyboard's half of the screen back.
    val gridDragged by gridState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(gridDragged) {
        if (gridDragged) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    LaunchedEffect(sort, isActive) {
        if (isActive && sort != DrawerSort.ALPHA) stats = withContext(Dispatchers.IO) { Graph.usage.sortStats() }
    }
    // Usage inside a work profile is invisible to us, so those apps count as 0 and stay
    // alphabetical. sortedByDescending is stable: ties keep their A–Z order.
    val sorted = remember(inProfile, sort, stats) {
        when (sort) {
            DrawerSort.ALPHA -> inProfile
            DrawerSort.MOST_USED -> inProfile.sortedByDescending { if (it.isWorkProfile) 0L else stats[it.packageName]?.first ?: 0L }
            DrawerSort.RECENT -> inProfile.sortedByDescending { if (it.isWorkProfile) 0L else stats[it.packageName]?.second ?: 0L }
        }
    }
    // Search ignores tab and sort: it looks through both profiles and ranks by match.
    val results = remember(visible, sorted, query, searchNames) {
        if (query.isBlank()) sorted else searchApps(visible, query, searchNames)
    }
    val recent = remember(inProfile, settings.showRecentInstalls) {
        if (!settings.showRecentInstalls) emptyList()
        else {
            val cutoff = System.currentTimeMillis() - DAY_MS
            inProfile.filter { it.firstInstallTime >= cutoff && it.packageName != Graph.app.packageName }
                .sortedByDescending { it.firstInstallTime }
        }
    }
    val rest = remember(results, recent, searching) {
        if (searching || recent.isEmpty()) results else results.filterNot { app -> recent.any { it.key == app.key } }
    }

    // The keyboard only ever opens on purpose: by the "open right away" setting, by swiping up on
    // the home screen, or by tapping the search bar. In every other case make sure it is closed.
    LaunchedEffect(isActive) {
        if (isActive && (settings.autoKeyboard || wantsSearchFocus)) {
            searchFocus.requestFocus()
            keyboard?.show()
            onSearchFocusHandled()
        } else {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    // stats is a key too: when it arrives the grid reorders, and a keyed list would follow the old top row.
    LaunchedEffect(query, workTab, sort, stats) { gridState.scrollToItem(0) }
    // Optional: open the app as soon as the search narrows down to exactly one.
    LaunchedEffect(results, query) {
        if (settings.autoLaunch && isActive && query.trim().length >= 2 && results.size == 1) onLaunch(results[0])
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        // Search bar
        Row(
            Modifier
                .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 10.dp)
                .fillMaxWidth()
                .border(1.dp, c.faint)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocus)
                    // This page is composed even while the home page is showing. Without this the
                    // off-screen field takes the window's initial focus and pops the keyboard.
                    .focusProperties { canFocus = isActive }
                    .padding(vertical = 14.dp),
                singleLine = true,
                textStyle = focusTextStyle(size = 18.sp),
                cursorBrush = SolidColor(c.fg),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onAny = { results.firstOrNull()?.takeIf { searching }?.let(onLaunch) }),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) T("Search", size = 18.sp, color = c.faint, maxLines = 1)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                T("Clear", Modifier.clickable { onQueryChange("") }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), size = 14.sp, color = c.dim)
            }
        }

        // Tabs and sort. Chips and the sort text carry their own padding, so both edges land on 30dp.
        if (!searching) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hasWork) {
                    TabChip("Personal", !workTab) { showWork = false }
                    TabChip("Work", workTab) { showWork = true }
                }
                Spacer(Modifier.weight(1f))
                T(
                    "Sort: ${sort.label}",
                    Modifier.press { sortDialog = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                    size = 13.sp, color = c.dim, maxLines = 1,
                )
            }
        }

        // Framed like the tips on the home page, so it reads as a tip and not as part of the list.
        if (hint != null && !searching) {
            T(hint, Modifier.padding(horizontal = 24.dp, vertical = 6.dp).border(1.dp, c.fg, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp), size = 15.sp, lineHeight = 21.sp)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 106.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!searching && recent.isNotEmpty()) {
                    item(key = "label:recent", span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Installed in the last 24 hours") }
                    items(recent, key = { "recent:" + it.key }) { app ->
                        AppTile(app, settings, today, onLaunch, onAppMenu)
                    }
                    item(key = "label:all", span = { GridItemSpan(maxLineSpan) }) { SectionLabel("All apps", top = 16) }
                    items(rest, key = { "all:" + it.key }) { app ->
                        AppTile(app, settings, today, onLaunch, onAppMenu)
                    }
                } else {
                    items(results, key = { it.key }) { app ->
                        AppTile(app, settings, today, onLaunch, onAppMenu)
                    }
                }
                if (results.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        T(
                            if (!loaded) "Loading…" else if (searching) "No app matches “${query.trim()}”" else "No apps",
                            Modifier.padding(horizontal = 12.dp, vertical = 18.dp), size = 16.sp, color = c.dim,
                        )
                    }
                }
                item(key = "inset", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        VSpace(24.dp)
                        Box(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        }
    }

    if (sortDialog) {
        ChoiceDialog("Sort apps", DrawerSort.entries.map { it to it.label }, sort, { sortDialog = false }) { v ->
            Graph.settings.update { it.copy(drawerSort = v) }
        }
    }
}

@Composable
private fun SectionLabel(text: String, top: Int = 10) {
    Label(text, Modifier.padding(start = 30.dp, end = 30.dp, top = top.dp, bottom = 6.dp))
}

@Composable
private fun AppTile(
    app: AppEntry,
    settings: Settings,
    today: DayUsage?,
    onLaunch: (AppEntry) -> Unit,
    onAppMenu: (AppEntry) -> Unit,
) {
    val c = LocalFocusColors.current
    val limit = remember(app.packageName, settings.appLimits, settings.timersEnabled, settings.socialDefaultMin, settings.gameDefaultMin) {
        Graph.limits.limitFor(app.packageName, settings)
    }
    val used = today?.perApp?.get(app.packageName) ?: 0L
    val spent = limit != null && used >= limit.millis && !Graph.limits.hasFreePass(app.packageName)

    Column(
        Modifier
            .press(onLongClick = { onAppMenu(app) }) { onLaunch(app) }
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        MinimalIcon(label = app.label, dimmed = spent)
        val name = if (hasColourGlyphs(app.label)) Modifier.fillMaxWidth().monochrome() else Modifier.fillMaxWidth()
        T(app.label, name, size = 14.sp, color = if (spent) c.faint else c.fg, maxLines = 1, align = androidx.compose.ui.text.style.TextAlign.Center)
        if (app.isWorkProfile) T("Work", size = 11.sp, color = c.dim, maxLines = 1)
        if (limit != null && settings.showUsageInDrawer) {
            T("${formatDuration(used)} / ${formatMinutes(limit.minutes)}", size = 11.sp, color = if (spent) c.faint else c.dim, maxLines = 1, align = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun MinimalIcon(label: String, dimmed: Boolean) {
    val c = LocalFocusColors.current
    val text = normalize(label).firstOrNull()?.uppercaseChar()?.toString() ?: "·"
    Box(
        Modifier
            .size(46.dp)
            .border(1.dp, if (dimmed) c.line else c.dim, RoundedCornerShape(12.dp))
            .background(c.bg, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        T(text, size = 20.sp, color = if (dimmed) c.faint else c.fg, weight = FontWeight.Medium, maxLines = 1)
        Canvas(Modifier.fillMaxSize()) {
            drawLine(
                color = if (dimmed) c.line else c.faint,
                start = Offset(size.width * 0.22f, size.height * 0.78f),
                end = Offset(size.width * 0.78f, size.height * 0.78f),
                strokeWidth = 1f,
            )
        }
    }
}

// ---- search ----------------------------------------------------------------------------------

private val MARKS = Regex("\\p{Mn}+")

private fun normalize(text: String): String =
    MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase().trim()

/**
 * Filters [apps] by [query]. Best matches first: name starts with the query, then a word in the
 * name starts with it, then it appears anywhere, then initials ("gm" finds Google Maps), and
 * finally a loose in-order match for typos of three letters or more.
 */
fun searchApps(apps: List<AppEntry>, query: String, names: Map<String, String> = emptyMap()): List<AppEntry> {
    val q = normalize(query)
    if (q.isEmpty()) return apps
    val ranked = ArrayList<Pair<Int, AppEntry>>()
    for (app in apps) {
        val name = names[app.key] ?: normalize(app.label)
        val words = name.split(' ', '-', '_', '.', ':').filter { it.isNotEmpty() }
        val rank = when {
            name.startsWith(q) -> 0
            words.any { it.startsWith(q) } -> 1
            name.contains(q) -> 2
            words.size > 1 && words.map { it[0] }.joinToString("").startsWith(q) -> 3
            q.length >= 3 && isSubsequence(q, name) -> 4
            else -> continue
        }
        ranked += rank to app
    }
    return ranked.sortedBy { it.first }.map { it.second } // stable: alphabetical within a rank
}

private fun isSubsequence(needle: String, haystack: String): Boolean {
    var i = 0
    for (ch in haystack) if (i < needle.length && ch == needle[i]) i++
    return i == needle.length
}
