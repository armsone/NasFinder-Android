package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
internal fun LocalPhotoLibraryCalendarScreen(
    commonPickedUris: List<String>,
    canonicalKey: (String) -> String,
    onToggleUri: (String) -> Unit,
    onAddUris: (List<String>) -> Unit,
    onPreview: (Uri) -> Unit,
    onPreviewItems: ((List<Uri>, Uri) -> Unit)? = null,
    onSwitchToPhotoTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var permissionState by remember { mutableStateOf(LocalPhotoLibraryCalendarRepository.checkPermissionState(context)) }
    var currentYearMonth by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var selectedDateStrings by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var calendarMutationGeneration by remember { mutableStateOf(0) }
    var pendingDateAdds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var excludedKeys by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var calendarError by remember { mutableStateOf<String?>(null) }
    var dateCounts by remember { mutableStateOf<Map<LocalDate, Int>>(emptyMap()) }
    var holidays by remember { mutableStateOf<Map<LocalDate, String>>(emptyMap()) }
    var selectedAssets by remember { mutableStateOf<List<CalendarDayMedia>>(emptyList()) }
    var indexLoading by remember { mutableStateOf(false) }
    var selectionLoading by remember { mutableStateOf(false) }
    var refreshRevision by remember { mutableStateOf(0) }
    var pickerVisible by remember { mutableStateOf(false) }
    var draftYear by remember { mutableStateOf(currentYearMonth.year) }
    var draftMonth by remember { mutableStateOf(currentYearMonth.monthValue) }
    var thumbnailColumns by rememberSaveable { mutableStateOf(6) }
    var scrollButtonsVisible by remember { mutableStateOf(false) }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val selectedDates = selectedDateStrings.map(LocalDate::parse).toSet()
    val selectedKeys = commonPickedUris.map(canonicalKey).toSet()
    val visibleAssets = selectedAssets

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionState = LocalPhotoLibraryCalendarRepository.checkPermissionState(context)
                refreshRevision++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionState = LocalPhotoLibraryCalendarRepository.checkPermissionState(context)
        refreshRevision++
    }
    fun requestPermission() {
        permissionLauncher.launch(when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        })
    }
    LaunchedEffect(permissionState, refreshRevision) {
        indexLoading = true
        calendarError = null
        dateCounts = emptyMap()
        try {
            dateCounts = LocalPhotoLibraryCalendarRepository.loadMediaDateCounts(context)
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { calendarError = "사진 날짜를 불러오지 못했습니다. 새로고침해 주세요." }
        finally { indexLoading = false }
    }
    LaunchedEffect(Unit) { holidays = LocalPhotoLibraryCalendarHolidays.load(context) }
    // Individual photo selections also highlight their dates. They never select other photos implicitly.
    LaunchedEffect(commonPickedUris) {
        val generation = calendarMutationGeneration
        val dates = LocalPhotoLibraryCalendarRepository.datesForUris(context, commonPickedUris)
        if (calendarMutationGeneration == generation) selectedDateStrings = (selectedDateStrings + dates.map(LocalDate::toString)).distinct()
    }
    LaunchedEffect(selectedDateStrings, refreshRevision, permissionState) {
        selectionLoading = true
        try {
            val dates = selectedDateStrings.map(LocalDate::parse).toSet()
            selectedAssets = LocalPhotoLibraryCalendarRepository.loadDatesMedia(context, dates)
            val pending = pendingDateAdds.toSet()
            val newlySelected = selectedAssets.filter { LocalPhotoLibraryCalendarRepository.resolveLocalDate(it)?.toString() in pending && canonicalKey(it.uri.toString()) !in excludedKeys }
            if (newlySelected.isNotEmpty()) onAddUris(newlySelected.map { it.uri.toString() })
            pendingDateAdds = pendingDateAdds.filterNot { it in pending }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { calendarError = "선택한 날짜의 사진을 불러오지 못했습니다. 새로고침해 주세요." }
        finally { selectionLoading = false }
    }
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) scrollButtonsVisible = true
        else { kotlinx.coroutines.delay(2_000); scrollButtonsVisible = false }
    }
    fun addDate(date: LocalDate) {
        if ((dateCounts[date] ?: 0) == 0) return
        val value = date.toString()
        if (value !in selectedDateStrings) {
            calendarMutationGeneration++
            pendingDateAdds = pendingDateAdds + value
            selectedDateStrings = selectedDateStrings + value
        }
    }
    fun toggleDate(date: LocalDate) {
        val value = date.toString()
        if (value in selectedDateStrings) {
            calendarMutationGeneration++
            selectedDateStrings = selectedDateStrings - value
            pendingDateAdds = pendingDateAdds - value
            val dayKeys = selectedAssets.filter { LocalPhotoLibraryCalendarRepository.resolveLocalDate(it) == date }.map { canonicalKey(it.uri.toString()) }.toSet()
            commonPickedUris.filter { canonicalKey(it) in dayKeys }.forEach(onToggleUri)
        } else addDate(date)
    }
    fun toggleAsset(uri: String) {
        calendarMutationGeneration++
        val key = canonicalKey(uri)
        excludedKeys = if (key in selectedKeys) (excludedKeys + key).distinct() else excludedKeys - key
        // Keep the date and its media visible so a cleared checkbox can be selected again.
        onToggleUri(uri)
    }

    fun selectPrevious() {
        val target = if (selectedDates.isEmpty()) {
            val yesterday = LocalDate.now().minusDays(1)
            dateCounts.keys.minWithOrNull(compareBy<LocalDate> { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(yesterday, it)) }.thenBy { it })
        } else dateCounts.keys.filter { it < selectedDates.minOrNull()!! }.maxOrNull()
        target?.let { addDate(it); currentYearMonth = YearMonth.from(it) }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 2.dp)) {
        if (permissionState != CalendarMediaPermissionState.FULL_ACCESS) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (permissionState == CalendarMediaPermissionState.DENIED) "사진·영상 접근 권한이 필요합니다." else "허용한 사진·영상만 표시합니다.", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                if (permissionState == CalendarMediaPermissionState.DENIED) androidx.compose.material3.TextButton(onClick = ::requestPermission) { Text("권한 허용") }
            }
        }
        val monthHeaderHeight = maxOf(44f, 24f * androidx.compose.ui.platform.LocalDensity.current.fontScale + 8f).dp
        Row(Modifier.fillMaxWidth().height(monthHeaderHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(modifier = Modifier.size(44.dp), onClick = { currentYearMonth = currentYearMonth.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "이전 달") }
            androidx.compose.material3.TextButton(modifier = Modifier.height(monthHeaderHeight), onClick = { draftYear = currentYearMonth.year; draftMonth = currentYearMonth.monthValue; pickerVisible = true }) { Text("${currentYearMonth.year}년 ${currentYearMonth.monthValue}월", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            IconButton(modifier = Modifier.size(44.dp), onClick = { currentYearMonth = currentYearMonth.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "다음 달") }
        }
        CalendarMonthGrid(currentYearMonth, selectedDates, dateCounts, holidays, enabled = !selectionLoading, onSelectDate = ::toggleDate)
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("선택 ${selectedDates.size}일 · 미디어 ${commonPickedUris.size}개", modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (indexLoading || selectionLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalendarActionText("전날", enabled = !indexLoading && !selectionLoading && dateCounts.isNotEmpty(), onClick = ::selectPrevious)
            CalendarActionText("오늘", enabled = !selectionLoading, onClick = {
                val today = LocalDate.now()
                if (currentYearMonth != YearMonth.from(today)) currentYearMonth = YearMonth.from(today)
                else addDate(today)
            })
            CalendarActionText("해제", enabled = selectedDates.isNotEmpty() && !selectionLoading && !indexLoading && pendingDateAdds.isEmpty(), onClick = {
                if (!selectionLoading && !indexLoading && pendingDateAdds.isEmpty()) {
                    val calendarKeys = selectedAssets.filter {
                        LocalPhotoLibraryCalendarRepository.resolveLocalDate(it) in selectedDates
                    }.map { canonicalKey(it.uri.toString()) }.toSet()
                    val calendarSelection = commonPickedUris.filter { canonicalKey(it) in calendarKeys }
                    calendarMutationGeneration++
                    pendingDateAdds = emptyList(); selectedDateStrings = emptyList(); selectedAssets = emptyList(); excludedKeys = emptyList()
                    // Only clear device items in the selected calendar dates; keep file/cloud selections.
                    calendarSelection.forEach(onToggleUri)
                }
            })
            }
        }
        calendarError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(thumbnailColumns), state = gridState,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var magnification = 1f
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                magnification *= event.calculateZoom()
                                val steps = listOf(8, 6, 4, 2, 1)
                                val index = steps.indexOf(thumbnailColumns)
                                if (magnification > 1.35f) { thumbnailColumns = steps[(index + 1).coerceAtMost(steps.lastIndex)]; magnification = 1f }
                                else if (magnification < .65f) { thumbnailColumns = steps[(index - 1).coerceAtLeast(0)]; magnification = 1f }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
                verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(visibleAssets, key = { it.uri.toString() }) { item ->
                    val selected = canonicalKey(item.uri.toString()) in selectedKeys
                    CalendarMediaThumbnailCell(context, item, selected, false, onTap = { if (onPreviewItems != null) onPreviewItems(selectedAssets.map { it.uri }, item.uri) else onPreview(item.uri) }, onToggleSelected = { toggleAsset(item.uri.toString()) })
                }
            }
            if (visibleAssets.isEmpty() && !selectionLoading) Text(if (indexLoading) "사진·영상 날짜를 불러오는 중" else "자료가 있는 날짜를 선택하세요", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (scrollButtonsVisible) Column(Modifier.align(Alignment.CenterEnd).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), RoundedCornerShape(12.dp))) {
                IconButton(onClick = { scope.launch { gridState.animateScrollToItem(0) } }, enabled = gridState.canScrollBackward) { Icon(Icons.Default.ArrowUpward, "맨 위로") }
                IconButton(onClick = { scope.launch { if (visibleAssets.isNotEmpty()) gridState.animateScrollToItem(visibleAssets.lastIndex) } }, enabled = gridState.canScrollForward) { Icon(Icons.Default.ArrowDownward, "맨 아래로") }
            }
        }
    }
    if (pickerVisible) {
        val yearRange = (LocalDate.now().year - 30..LocalDate.now().year + 10).toList()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickerVisible = false }, title = { Text("년월 이동") },
            text = {
                Row(Modifier.fillMaxWidth().height(220.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = yearRange.indexOf(draftYear).coerceAtLeast(0))) {
                        items(yearRange) { year -> androidx.compose.material3.TextButton(onClick = { draftYear = year }, modifier = Modifier.fillMaxWidth()) { Text("${year}년", fontWeight = if (year == draftYear) FontWeight.SemiBold else FontWeight.Normal) } }
                    }
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = draftMonth - 1)) {
                        items((1..12).toList()) { month -> androidx.compose.material3.TextButton(onClick = { draftMonth = month }, modifier = Modifier.fillMaxWidth()) { Text("${month}월", fontWeight = if (month == draftMonth) FontWeight.SemiBold else FontWeight.Normal) } }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { currentYearMonth = YearMonth.of(draftYear, draftMonth); pickerVisible = false }) { Text("이동") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pickerVisible = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun CalendarMonthGrid(
    yearMonth: YearMonth,
    selectedDates: Set<LocalDate>,
    mediaCounts: Map<LocalDate, Int>,
    holidays: Map<LocalDate, String>,
    enabled: Boolean,
    onSelectDate: (LocalDate) -> Unit,
) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val dayHeight = maxOf(38f, 26f * fontScale + 2f).dp
    val weekdayHeight = maxOf(22f, 16f * fontScale).dp
    val firstDayOfMonth = yearMonth.atDay(1)
    val daysInMonth = yearMonth.lengthOfMonth()

    // Week starts on Sunday
    val firstDayOfWeek = firstDayOfMonth.dayOfWeek.value % 7 // Sunday = 0, Monday = 1, ...
    val totalCells = ((firstDayOfWeek + daysInMonth + 6) / 7) * 7

    val dayHeaders = listOf("일", "월", "화", "수", "목", "금", "토")

    Column(modifier = Modifier.fillMaxWidth()) {
        // Weekday header
        Row(modifier = Modifier.fillMaxWidth().height(weekdayHeight), verticalAlignment = Alignment.CenterVertically) {
            dayHeaders.forEachIndexed { index, name ->
                val textColor = when (index) {
                    0 -> Color.Red
                    6 -> Color.Blue
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    text = name,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Month rows
        for (row in 0 until (totalCells / 7)) {
            if (row > 0) Spacer(Modifier.height(1.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0..6) {
                    val cellIndex = row * 7 + col
                    val dayNumber = cellIndex - firstDayOfWeek + 1
                    if (dayNumber in 1..daysInMonth) {
                        val date = yearMonth.atDay(dayNumber)
                        val count = mediaCounts[date] ?: 0
                        val isSelected = date in selectedDates
                        val holiday = holidays[date]
                        val textColor = when {
                            col == 0 || holiday != null -> Color.Red
                            col == 6 -> Color.Blue
                            else -> MaterialTheme.colorScheme.onSurface
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(dayHeight)
                                .padding(1.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .then(if (date == LocalDate.now()) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier)
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.primaryContainer
                                        count > 0 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable(enabled = enabled && count > 0) { onSelectDate(date) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = dayNumber.toString(), fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1,
                                        fontWeight = if (isSelected || count > 0) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else textColor,
                                    )
                                    if (count > 0) Text(
                                        text = if (count > 99) "99+" else count.toString(),
                                        fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1, fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (holiday != null) Text(holiday, fontSize = 8.sp, lineHeight = 10.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = Color.Red)
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CalendarMediaThumbnailCell(
    context: Context,
    item: CalendarDayMedia,
    isSelected: Boolean,
    isAlreadyInBasket: Boolean,
    onTap: () -> Unit,
    onToggleSelected: () -> Unit,
) {
    var thumbnail by remember(item.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.uri) {
        thumbnail = loadLocalLibraryThumbnail(context, item.uri)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onToggleSelected, onLongClick = onTap),
    ) {
        thumbnail?.let { bitmap ->
            SkinAwareThumbnailImage(
                bitmap = bitmap,
                cacheIdentity = "cal-media:${item.uri}",
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
        }

        if (item.isVideo) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(24.dp),
            )
            item.durationMs?.let { durationMs ->
                val seconds = (durationMs / 1000.0).toInt().coerceAtLeast(0)
                val durationStr = "%d:%02d".format(seconds / 60, seconds % 60)
                Text(
                    text = durationStr,
                    maxLines = 1,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Normal),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(2.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                )
            }
        }

        if (isAlreadyInBasket) {
            Text(
                text = "담김",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }

        Box(
            modifier = Modifier.align(Alignment.TopEnd).size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isSelected) "선택 해제" else "선택",
                tint = Color.White,
                modifier = Modifier
                    .size(24.dp)
                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    .padding(3.dp),
            )
        }
    }
}


@Composable
private fun CalendarActionText(label: String, enabled: Boolean, onClick: () -> Unit) {
    val configuration = androidx.compose.ui.platform.LocalViewConfiguration.current
    val exactTarget = remember(configuration) {
        object : androidx.compose.ui.platform.ViewConfiguration by configuration {
            override val minimumTouchTargetSize = androidx.compose.ui.unit.DpSize(44.dp, 44.dp)
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalViewConfiguration provides exactTarget) {
        Box(Modifier.size(44.dp).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
        }
    }
}
