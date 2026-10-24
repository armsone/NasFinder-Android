package com.armsone.nasfinder.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private data class LibraryGridEntry(val key: String, val day: LocalDate?, val media: LocalMediaItem?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LocalPhotoLibraryGrid(
    media: List<LocalMediaItem>, query: LocalLibraryQuery, motionPhotoSupported: Boolean, onQueryChanged: (LocalLibraryQuery) -> Unit,
    loading: Boolean, selectedKeys: Set<String>, canonicalKey: (String) -> String,
    onSetSelection: (List<String>, Boolean) -> Unit, onToggle: (String) -> Unit,
    onClearDeviceSelection: () -> Unit,
    onPreview: (List<LocalMediaItem>, LocalMediaItem) -> Unit,
    onLaunchPicker: () -> Unit, onLoadMore: (() -> Unit)?, copying: Boolean,
    dateCounts: Map<LocalDate, Int>,
    onRequestDay: (LocalDate, (List<LocalMediaItem>) -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var columns by rememberSaveable { mutableStateOf(5) }
    var filterMenu by remember { mutableStateOf(false) }
    var durationEditor by remember { mutableStateOf(false) }
    var durationMinutes by remember { mutableStateOf("1") }
    var durationSeconds by remember { mutableStateOf("0") }
    var durationAtLeast by remember { mutableStateOf(true) }
    var filtersBeforeDuration by rememberSaveable { mutableStateOf(listOf(true, true, true)) }
    var todayArmed by rememberSaveable { mutableStateOf(false) }
    var navigationItems by remember { mutableStateOf<List<LocalMediaItem>>(emptyList()) }
    var pendingScrollDate by remember { mutableStateOf<LocalDate?>(null) }
    val grid = rememberLazyGridState()
    var initialScrollDone by rememberSaveable { mutableStateOf(false) }
    var previousQuery by remember { mutableStateOf(query) }
    var gridHeight by remember { mutableStateOf(0) }
    var dragPoint by remember { mutableStateOf<Offset?>(null) }
    var dragSelect by remember { mutableStateOf<Boolean?>(null) }
    var lastDragIndex by remember { mutableStateOf<Int?>(null) }
    var scrollButtonsVisible by remember { mutableStateOf(false) }

    fun matches(item: LocalMediaItem): Boolean {
        val kindMatches = when { item.isVideo -> query.videos; item.isMotionPhoto -> query.motionPhotos; else -> query.photos }
        return kindMatches && (!item.isVideo || query.durationMs == null || item.durationMs?.let { if (query.durationAtLeast) it >= query.durationMs else it <= query.durationMs } == true)
    }
    fun timestamp(item: LocalMediaItem): Long = if (query.captureSort) item.captureTimeMs ?: item.addedTimeMs ?: 0L else item.addedTimeMs ?: item.captureTimeMs ?: 0L
    fun day(item: LocalMediaItem): LocalDate? = timestamp(item).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
    val ordered = remember(media, navigationItems, query) {
        val sorted = (media + navigationItems).distinctBy { canonicalKey(it.uri.toString()) }.filter(::matches)
            .sortedWith(compareBy<LocalMediaItem> { timestamp(it) }.thenBy { it.uri.toString() })
        if (query.ascending) sorted else sorted.asReversed()
    }
    val entries = remember(ordered, query) {
        buildList {
            var previousDay: LocalDate? = null
            var first = true
            ordered.forEach { item ->
                val date = day(item)
                if (first || date != previousDay) add(LibraryGridEntry("date:${date ?: "unknown"}", date, null))
                add(LibraryGridEntry(item.uri.toString(), date, item))
                previousDay = date; first = false
            }
        }
    }
    val latestQuery by rememberUpdatedState(query)
    val latestOrdered by rememberUpdatedState(ordered)
    val latestEntries by rememberUpdatedState(entries)
    val latestSelected by rememberUpdatedState(selectedKeys)
    val latestSetSelection by rememberUpdatedState(onSetSelection)
    val latestCanonical by rememberUpdatedState(canonicalKey)
    val latestLoadMore by rememberUpdatedState(onLoadMore)

    fun itemAt(point: Offset): LocalMediaItem? {
        val visible = grid.layoutInfo.visibleItemsInfo
        val hit = visible.firstOrNull { point.x >= it.offset.x && point.x < it.offset.x + it.size.width && point.y >= it.offset.y && point.y < it.offset.y + it.size.height }
            ?: visible.filter { it.key.toString().startsWith("content:") }.minByOrNull { abs(it.offset.y + it.size.height / 2f - point.y) + abs(it.offset.x + it.size.width / 2f - point.x) }
        return latestEntries.getOrNull(hit?.index ?: -1)?.media
    }
    fun applyDrag(point: Offset) {
        val item = itemAt(point) ?: return
        val index = latestOrdered.indexOfFirst { it.uri == item.uri }
        if (index < 0) return
        val previous = lastDragIndex ?: index
        latestSetSelection(latestOrdered.subList(minOf(previous, index), maxOf(previous, index) + 1).map { it.uri.toString() }, dragSelect ?: return)
        lastDragIndex = index
    }
    LaunchedEffect(query) {
        if (previousQuery != query) { initialScrollDone = false; navigationItems = emptyList(); previousQuery = query; todayArmed = false }
    }
    LaunchedEffect(ordered.size, loading, query) {
        if (!loading && ordered.isNotEmpty() && !initialScrollDone) {
            grid.scrollToItem(if (query.ascending) entries.lastIndex else 0)
            initialScrollDone = true
        }
    }
    LaunchedEffect(entries, pendingScrollDate) {
        val target = pendingScrollDate ?: return@LaunchedEffect
        val index = entries.indexOfFirst { it.media == null && it.day == target }
        if (index >= 0) { grid.animateScrollToItem(index); pendingScrollDate = null }
    }
    LaunchedEffect(grid.isScrollInProgress) {
        if (grid.isScrollInProgress) scrollButtonsVisible = true
        else { delay(2_000); scrollButtonsVisible = false }
    }
    LaunchedEffect(dragSelect) {
        if (dragSelect == null) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (dragSelect != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - lastFrame) / 1_000_000_000f).coerceAtMost(.05f); lastFrame = now
            val point = dragPoint ?: continue
            val edge = with(density) { 105.dp.toPx() }
            val progress = when { point.y < edge -> -((edge - point.y) / edge).coerceIn(0f, 1f); point.y > gridHeight - edge -> ((point.y - (gridHeight - edge)) / edge).coerceIn(0f, 1f); else -> 0f }
            if (progress != 0f) {
                val pixels = (120f + 1180f * progress * progress) * density.density * seconds * if (progress < 0) -1 else 1
                val moved = grid.scrollBy(pixels)
                if (abs(moved) > .1f) { scrollButtonsVisible = true; applyDrag(Offset(point.x, if (progress < 0) 2f else gridHeight - 2f)) }
            }
        }
    }
    fun navigate(date: LocalDate, select: Boolean) {
        todayArmed = false
        val requestedQuery = query
        onRequestDay(date) completion@{ loaded ->
            if (latestQuery != requestedQuery) return@completion
            val matching = loaded.filter(::matches)
            navigationItems = (navigationItems + matching).distinctBy { canonicalKey(it.uri.toString()) }
            if (select) onSetSelection(matching.map { it.uri.toString() }, true)
            pendingScrollDate = date
        }
    }
    fun clearDuration() {
        onQueryChanged(query.copy(durationMs = null, photos = filtersBeforeDuration.getOrElse(0) { true }, motionPhotos = filtersBeforeDuration.getOrElse(1) { true }, videos = filtersBeforeDuration.getOrElse(2) { true }))
    }
    fun applyDuration(seconds: Long) {
        if (query.durationMs == null) filtersBeforeDuration = listOf(query.photos, query.motionPhotos, query.videos)
        onQueryChanged(query.copy(photos = false, motionPhotos = false, videos = true, durationMs = seconds.coerceAtLeast(1) * 1000L, durationAtLeast = durationAtLeast))
        durationEditor = false
    }

    Column(Modifier.fillMaxSize()) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { gridHeight = it.height }) {
            // Collection insets: 12 left/right, 8 top/bottom now that floating overlay is replaced by compact toolbar row.
            LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid, contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var multiTouch = false
                        var zoom = 1f
                        var vertical = false
                        val startItem = itemAt(down.position)
                        try {
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    multiTouch = true; dragSelect = null
                                    zoom *= event.calculateZoom()
                                    val stops = listOf(1, 3, 5, 8); val index = stops.indexOf(columns)
                                    if (zoom > 1.18f) { columns = stops[(index - 1).coerceAtLeast(0)]; zoom = 1f }
                                    else if (zoom < .82f) { columns = stops[(index + 1).coerceAtMost(stops.lastIndex)]; zoom = 1f }
                                    event.changes.forEach { it.consume() }
                                } else if (!multiTouch && !vertical && pressed.isNotEmpty()) {
                                    val point = pressed.first().position
                                    val delta = point - down.position
                                    if (dragSelect == null && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) vertical = true
                                    else if (dragSelect == null && abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y) && startItem != null) {
                                        dragSelect = latestCanonical(startItem.uri.toString()) !in latestSelected
                                        lastDragIndex = latestOrdered.indexOfFirst { it.uri == startItem.uri }
                                    }
                                    if (dragSelect != null) { dragPoint = point; applyDrag(point); event.changes.forEach { it.consume() } }
                                }
                            } while (event.changes.any { it.pressed })
                        } finally { dragSelect = null; dragPoint = null; lastDragIndex = null }
                    }
                },
            ) {
                entries.forEach { entry ->
                    if (entry.media == null) item(key = entry.key, span = { GridItemSpan(maxLineSpan) }) {
                        Text(entry.day?.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", java.util.Locale.KOREAN)) ?: "날짜 정보 없음", Modifier.fillMaxWidth().padding(vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
                    } else item(key = entry.key) {
                        LocalMediaThumbnail(entry.media, canonicalKey(entry.key) in selectedKeys, onTap = { onToggle(entry.key) }, onLongPress = { onPreview(ordered, entry.media) }, selectionOrder = selectedKeys.indexOf(canonicalKey(entry.key)).takeIf { it >= 0 }?.plus(1))
                    }
                }
            }
            if (ordered.isEmpty() && !loading) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Text("표시할 미디어가 없습니다."); TextButton(onClick = onLaunchPicker) { Text("미디어 선택") } }
            if (scrollButtonsVisible) Column(Modifier.align(Alignment.CenterEnd).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), RoundedCornerShape(12.dp))) {
                IconButton(onClick = { scope.launch { grid.animateScrollToItem(0) } }, enabled = grid.canScrollBackward) { Icon(Icons.Default.ArrowUpward, "맨 위로") }
                IconButton(onClick = { scope.launch { if (entries.isNotEmpty()) grid.animateScrollToItem(entries.lastIndex) } }, enabled = grid.canScrollForward) { Icon(Icons.Default.ArrowDownward, "맨 아래로") }
            }
        }
        // Compact action toolbar: 44dp accessible height row outside the grid so actions never float over content.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                CompactGridPill("필터", enabled = !copying) { filterMenu = true }
                DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    DropdownMenuItem(text = { Text("전체") }, onClick = { onQueryChanged(query.copy(photos = true, motionPhotos = true, videos = true, durationMs = null)); filterMenu = false })
                    listOf("사진" to query.photos, "모션 사진" to query.motionPhotos, "영상" to query.videos).forEachIndexed { index, (label, enabled) ->
                        DropdownMenuItem(text = { Text(if (index == 1 && !motionPhotoSupported) "모션 사진 구분 불가" else "${if (enabled) "✓ " else ""}$label") }, enabled = index != 1 || motionPhotoSupported, onClick = {
                            val flags = mutableListOf(query.photos, query.motionPhotos, query.videos)
                            if (!flags[index] || flags.count { it } > 1) { flags[index] = !flags[index]; onQueryChanged(query.copy(photos = flags[0], motionPhotos = flags[1], videos = flags[2])) }
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(query.durationMs?.let { "영상 ${it / 1000}초 ${if (query.durationAtLeast) "이상" else "이하"}" } ?: "영상 시간 제한") }, onClick = {
                        durationMinutes = ((query.durationMs ?: 60_000) / 60_000).toString(); durationSeconds = (((query.durationMs ?: 60_000) / 1000) % 60).toString(); durationAtLeast = query.durationAtLeast
                        filterMenu = false; durationEditor = true
                    })
                    if (query.durationMs != null) DropdownMenuItem(text = { Text("시간 제한 해제") }, onClick = { clearDuration(); filterMenu = false })
                    HorizontalDivider()
                    listOf(true to "촬영일", false to "추가일").forEach { (capture, label) ->
                        DropdownMenuItem(text = { Text("$label ${if (capture == query.captureSort) { if (query.ascending) "↑" else "↓" } else ""}") }, onClick = { onQueryChanged(query.copy(captureSort = capture, ascending = if (capture == query.captureSort) !query.ascending else true)); filterMenu = false })
                    }
                    HorizontalDivider()
                    listOf(1, 3, 5, 8).forEach { count -> DropdownMenuItem(text = { Text("${if (columns == count) "✓ " else ""}${count}열") }, onClick = { columns = count; filterMenu = false }) }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("표시한 항목 모두 선택") }, onClick = { onSetSelection(ordered.map { it.uri.toString() }, true); filterMenu = false }, enabled = ordered.isNotEmpty())
                    if (onLoadMore != null) DropdownMenuItem(text = { Text("이전 미디어 더 보기") }, onClick = { onLoadMore(); filterMenu = false }, enabled = !loading)
                }
            }
            CompactGridPill("전날", enabled = !copying && !loading) {
                val selectedDates = media.filter { canonicalKey(it.uri.toString()) in selectedKeys }.mapNotNull(::day)
                val dates = (dateCounts.keys + ordered.mapNotNull(::day)).toSet()
                val target = if (selectedDates.isEmpty()) dates.minWithOrNull(compareBy<LocalDate> { abs(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now().minusDays(1), it)) }.thenBy { it })
                    else dates.filter { it < selectedDates.minOrNull()!! }.maxOrNull()
                target?.let { navigate(it, true) }
            }
            CompactGridPill(if (todayArmed) "오늘 선택" else "오늘", enabled = !copying && !loading) {
                val today = LocalDate.now()
                val target = if (dateCounts.containsKey(today) || ordered.any { day(it) == today }) today else (dateCounts.keys + ordered.mapNotNull(::day)).maxOrNull()
                if (target != null) { val select = todayArmed && target == today; navigate(target, select); todayArmed = target == today && !select }
            }
            CompactGridPill("해제", enabled = !copying && selectedKeys.isNotEmpty(), onClick = onClearDeviceSelection)
            CompactGridPill("추가", enabled = !copying, onClick = onLaunchPicker)
        }
    }
    if (durationEditor) AlertDialog(onDismissRequest = { durationEditor = false }, title = { Text("영상 시간 제한") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(durationMinutes, { durationMinutes = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Text("분") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                OutlinedTextField(durationSeconds, { durationSeconds = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("초") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            }
            Row { FilterChip(durationAtLeast, { durationAtLeast = true }, label = { Text("이상") }); Spacer(Modifier.width(8.dp)); FilterChip(!durationAtLeast, { durationAtLeast = false }, label = { Text("이하") }) }
            Row { listOf(1, 3, 5, 10).forEach { minutes -> TextButton(onClick = { durationMinutes = minutes.toString(); durationSeconds = "0" }) { Text("${minutes}분") } } }
        }
    }, confirmButton = { TextButton(onClick = { applyDuration((durationMinutes.toLongOrNull() ?: 0) * 60 + (durationSeconds.toLongOrNull() ?: 0).coerceIn(0, 59)) }) { Text("적용") } }, dismissButton = { TextButton(onClick = { if (query.durationMs != null) clearDuration(); durationEditor = false }) { Text("제한 해제") } })
}

/** Compact accessible 44dp pill button for photo toolbar. */
@Composable
private fun CompactGridPill(title: String, enabled: Boolean, onClick: () -> Unit) {
    val configuration = androidx.compose.ui.platform.LocalViewConfiguration.current
    val exactTarget = remember(configuration) {
        object : androidx.compose.ui.platform.ViewConfiguration by configuration {
            override val minimumTouchTargetSize = androidx.compose.ui.unit.DpSize(44.dp, 44.dp)
        }
    }
    val shape = RoundedCornerShape(8.dp)
    CompositionLocalProvider(androidx.compose.ui.platform.LocalViewConfiguration provides exactTarget) {
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .clip(shape)
                .background(if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f), shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) .6f else .25f), shape)
                .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = title, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
                fontSize = 13.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
