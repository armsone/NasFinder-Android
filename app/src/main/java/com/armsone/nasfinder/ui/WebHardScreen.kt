@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.armsone.nasfinder.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.armsone.nasfinder.platform.NasFinderShareIntentFactory
import com.armsone.nasfinder.platform.WebHardFileItem
import com.armsone.nasfinder.platform.WebHardFileStore
import com.armsone.nasfinder.platform.WebHardHttpServer
import com.armsone.nasfinder.model.AppTheme
import com.armsone.nasfinder.ui.theme.LocalNasFinderTheme
import com.armsone.nasfinder.ui.theme.PhoneHardMark
import com.armsone.nasfinder.util.DownloadCacheContract
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

private enum class WebHardLayout(val title: String) {
    LIST("자세히"), SMALL("작은 썸네일"), LARGE("포스터")
}

private val BkPanelCharcoal = Color(0xFF34383B)
private val BkPanelChrome = Color(0xFF8B9093)
private val BkPanelRecessed = Color(0xFFE7E6E1)

@Stable
internal class WebHardConnectionState(private val store: WebHardFileStore) {
    var server by mutableStateOf<WebHardHttpServer?>(null)
        private set
    var port by mutableIntStateOf(0)
        private set
    var password by mutableStateOf("")
    var networkAddresses by mutableStateOf(localIpv4Addresses())
        private set
    var selectedAddress by mutableStateOf(networkAddresses.firstOrNull())
    var error by mutableStateOf<String?>(null)
        private set

    fun refreshAddresses() {
        if (server != null) return
        networkAddresses = localIpv4Addresses()
        selectedAddress = networkAddresses.firstOrNull()
    }

    fun toggle() {
        if (server != null) {
            stop()
            return
        }
        runCatching {
            val address = selectedAddress ?: throw IllegalStateException("접속 주소를 선택해 주세요.")
            WebHardHttpServer(store, password = password, bindAddress = address).also {
                port = it.start()
                server = it
            }
        }.onFailure { error = it.message ?: "폰하드를 열지 못했습니다." }
    }

    fun stop() {
        server?.close()
        server = null
        port = 0
    }

    fun dismissError() {
        error = null
    }
}

@Composable
internal fun rememberWebHardConnectionState(store: WebHardFileStore): WebHardConnectionState =
    remember(store) { WebHardConnectionState(store) }

@Composable
internal fun PhoneHardConnectionPanel(
    connection: WebHardConnectionState,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val largeFont = configuration.fontScale >= 1.3f
    val theme = LocalNasFinderTheme.current
    val enamel = theme == AppTheme.SKEUOMORPHIC
    val panelShape = RoundedCornerShape(16.dp)
    val fieldHeight = if (largeFont) 56.dp else 48.dp
    val addressHeight = if (largeFont) 48.dp else 40.dp
    var passwordExpanded by rememberSaveable { mutableStateOf(false) }
    val fieldColors = if (enamel) {
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = BkPanelCharcoal,
            unfocusedTextColor = BkPanelCharcoal,
            disabledTextColor = BkPanelCharcoal.copy(alpha = .72f),
            focusedContainerColor = BkPanelRecessed,
            unfocusedContainerColor = BkPanelRecessed,
            disabledContainerColor = BkPanelRecessed.copy(alpha = .72f),
            cursorColor = BkPanelCharcoal,
            focusedBorderColor = BkPanelChrome,
            unfocusedBorderColor = BkPanelChrome.copy(alpha = .72f),
            disabledBorderColor = BkPanelChrome.copy(alpha = .42f),
            focusedLabelColor = BkPanelCharcoal,
            unfocusedLabelColor = Color(0xFF686C6F),
            disabledLabelColor = Color(0xFF686C6F).copy(alpha = .68f),
            focusedTrailingIconColor = BkPanelCharcoal,
            unfocusedTrailingIconColor = BkPanelCharcoal,
            disabledTrailingIconColor = BkPanelCharcoal.copy(alpha = .48f),
        )
    } else {
        OutlinedTextFieldDefaults.colors()
    }

    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (enamel) {
                    BkConnectionIcon()
                } else {
                    Icon(Icons.Default.WifiTethering, null, tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "서버 열기",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enamel) BkPanelCharcoal else Color.Unspecified,
                )
            }

            Surface(
                color = if (enamel) BkPanelRecessed else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                border = if (enamel) BorderStroke(1.dp, BkPanelChrome.copy(alpha = .72f)) else null,
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = addressHeight).padding(start = 12.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            "접속 주소",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (enamel) Color(0xFF686C6F) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            connection.selectedAddress?.hostAddress ?: "사용 가능한 접속 주소가 없습니다",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (enamel) BkPanelCharcoal else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = connection::refreshAddresses, enabled = connection.server == null) {
                        Icon(
                            Icons.Default.Refresh,
                            "접속 주소 새로고침",
                            tint = if (enamel) BkPanelCharcoal else LocalContentColor.current,
                        )
                    }
                }
            }

            if (passwordExpanded) {
                PhoneHardPasswordField(connection, Modifier.fillMaxWidth().height(fieldHeight), fieldColors, enamel)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhoneHardPasswordButton(
                    expanded = passwordExpanded,
                    enamel = enamel,
                    onClick = { passwordExpanded = !passwordExpanded },
                    modifier = Modifier.weight(1f).height(fieldHeight),
                )
                PhoneHardConnectionButton(connection, enamel, Modifier.weight(1f).height(fieldHeight))
            }

            if (connection.server != null && connection.selectedAddress != null) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        "http://${connection.selectedAddress!!.hostAddress}:${connection.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enamel) BkPanelCharcoal else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (enamel) {
        Box(
            modifier
                .shadow(7.dp, panelShape, ambientColor = Color(0x5534383B), spotColor = Color(0x4434383B))
                .clip(panelShape)
                .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFF7F6F2), Color(0xFFEDECE7))))
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(Color.White, BkPanelChrome, Color(0xFFD8DADD), Color.White)),
                    panelShape,
                ),
        ) {
            content()
            HorizontalDivider(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 12.dp),
                thickness = 1.dp,
                color = Color.White.copy(alpha = .9f),
            )
        }
    } else {
        ElevatedCard(modifier) { content() }
    }

    connection.error?.let { message ->
        AlertDialog(
            onDismissRequest = connection::dismissError,
            confirmButton = { TextButton(onClick = connection::dismissError) { Text("확인") } },
            title = { Text("폰하드") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun BkConnectionIcon() {
    val shape = CircleShape
    Box(
        Modifier.size(30.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFE5E5E1))))
            .border(1.dp, BkPanelChrome, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.WifiTethering, null, tint = BkPanelCharcoal, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PhoneHardPasswordButton(
    expanded: Boolean,
    enamel: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = if (enamel) {
            ButtonDefaults.outlinedButtonColors(
                containerColor = Color.White.copy(alpha = .72f),
                contentColor = BkPanelCharcoal,
            )
        } else ButtonDefaults.outlinedButtonColors(),
        border = BorderStroke(1.dp, if (enamel) BkPanelChrome else MaterialTheme.colorScheme.outline),
    ) {
        Text("비밀번호")
        Spacer(Modifier.width(4.dp))
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            if (expanded) "비밀번호 입력 접기" else "비밀번호 입력 펼치기",
            Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PhoneHardConnectionButton(
    connection: WebHardConnectionState,
    enamel: Boolean,
    modifier: Modifier = Modifier,
) {
    val enabled = connection.server != null || connection.selectedAddress != null
    val label = if (connection.server == null) "열기" else "닫기"
    if (!enamel) {
        Button(onClick = connection::toggle, enabled = enabled, modifier = modifier) { Text(label) }
        return
    }
    Surface(
        onClick = connection::toggle,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = 80.dp, minHeight = 48.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color(0xFF6D7275)),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    if (enabled) listOf(Color(0xFF666B6E), BkPanelCharcoal, Color(0xFF24282B))
                    else listOf(Color(0xFFB9BBBA), Color(0xFF8D9090)),
                ),
            ).padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
private fun PhoneHardPasswordField(
    connection: WebHardConnectionState,
    modifier: Modifier,
    colors: TextFieldColors,
    enamel: Boolean,
) {
    OutlinedTextField(
        value = connection.password,
        onValueChange = { if (connection.server == null) connection.password = it },
        enabled = connection.server == null,
        label = { Text("비밀번호 (선택)", style = MaterialTheme.typography.labelSmall) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = modifier,
        shape = if (enamel) RoundedCornerShape(10.dp) else OutlinedTextFieldDefaults.shape,
        colors = colors,
    )
}

@Composable
fun WebHardScreen(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val store = remember { WebHardFileStore(context) }
    val connection = rememberWebHardConnectionState(store)
    var currentPath by rememberSaveable { mutableStateOf("/") }
    var fileItems by remember { mutableStateOf(store.list("/")) }
    var error by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<WebHardFileItem?>(null) }
    val thumbnails = remember { mutableStateMapOf<String, Bitmap>() }
    val preferences = remember { context.getSharedPreferences("webhard.ui.v1", android.content.Context.MODE_PRIVATE) }
    var layout by remember {
        mutableStateOf(runCatching { WebHardLayout.valueOf(preferences.getString("layout", WebHardLayout.SMALL.name)!!) }.getOrDefault(WebHardLayout.SMALL))
    }
    var coverFlowDark by remember {
        mutableStateOf(preferences.getBoolean("cover_flow_dark", false))
    }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val largeFont = configuration.fontScale >= 1.3f
    val theme = LocalNasFinderTheme.current
    val showsCoverFlow = layout == WebHardLayout.LARGE && landscape

    fun stop() {
        connection.stop()
    }
    fun refresh() {
        runCatching { store.list(currentPath) }
            .onSuccess { fileItems = it; error = null }
            .onFailure { error = it.message ?: "파일 목록을 읽지 못했습니다." }
    }
    fun openFolder(item: WebHardFileItem) {
        if (!item.isDirectory) return
        currentPath = item.path; refresh()
    }
    fun navigateUp() {
        if (currentPath == "/") return
        currentPath = currentPath.substringBeforeLast('/', "").ifBlank { "/" }; refresh()
    }
    fun selectLayout(value: WebHardLayout) {
        layout = value; preferences.edit().putString("layout", value.name).apply()
    }
    fun share(item: WebHardFileItem) {
        if (item.isDirectory) {
            error = "폴더는 파일로 공유할 수 없습니다. 폴더 안의 파일을 선택해 주세요."
            return
        }
        scope.launch {
            var preparedDirectory: File? = null
            val result = runCatching {
                val prepared = withContext(Dispatchers.IO) {
                    val source = store.file(item.path)
                    val root = safeWebHardShareRoot(context.cacheDir)
                    pruneWebHardShares(root, source.length())
                    val directory = File(root, "webhard-${UUID.randomUUID()}").canonicalFile
                    check(directory.parentFile == root && directory.mkdirs())
                    preparedDirectory = directory
                    val destination = File(directory, safeWebHardShareName(item.name)).canonicalFile
                    check(destination.parentFile == directory)
                    source.copyTo(destination)
                }
                val intent = NasFinderShareIntentFactory.create(context, listOf(prepared))
                context.startActivity(Intent.createChooser(intent, "폰하드 파일 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            result.onFailure {
                withContext(Dispatchers.IO) { preparedDirectory?.let(::cleanupWebHardShareDirectory) }
                error = it.message ?: "파일을 공유하지 못했습니다."
            }
        }
    }

    BackHandler {
        if (showsCoverFlow && currentPath != "/") navigateUp()
        else { stop(); onClose() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); stop() }
    }
    LaunchedEffect(connection.server) {
        while (connection.server != null) { delay(900); refresh() }
    }
    LaunchedEffect(currentPath) { thumbnails.clear() }

    Scaffold(
        contentWindowInsets = if (showsCoverFlow) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!showsCoverFlow) TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (theme == AppTheme.SKEUOMORPHIC) PhoneHardMark(28.dp)
                        Text("폰하드")
                    }
                },
                navigationIcon = { IconButton(onClick = { stop(); onClose() }) { Icon(Icons.Default.ArrowBack, "뒤로") } },
                actions = {
                    IconButton(
                        onClick = connection::refreshAddresses,
                        enabled = connection.server == null,
                    ) { Icon(Icons.Default.Refresh, "주소 새로고침") }
                },
            )
        },
    ) { padding ->
        if (showsCoverFlow) {
            WebHardCoverFlow(
                items = fileItems,
                currentPath = currentPath,
                usesDarkBackground = coverFlowDark,
                store = store,
                thumbnails = thumbnails,
                modifier = Modifier.padding(padding).fillMaxSize(),
                onBack = { if (currentPath == "/") { stop(); onClose() } else navigateUp() },
                onToggleBackground = { dark ->
                    coverFlowDark = dark
                    preferences.edit().putBoolean("cover_flow_dark", dark).apply()
                },
                onActivate = ::openFolder,
            )
        } else {
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PhoneHardConnectionPanel(connection, Modifier.fillMaxWidth()) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (currentPath == "/") "파일" else currentPath, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    WebHardLayout.entries.forEach { style ->
                        IconButton(onClick = { selectLayout(style) }) {
                            Icon(when (style) { WebHardLayout.LIST -> Icons.Default.ViewList; WebHardLayout.SMALL -> Icons.Default.GridView; WebHardLayout.LARGE -> Icons.Default.ViewModule }, style.title, tint = if (layout == style) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (currentPath != "/") item { TextButton(onClick = ::navigateUp) { Icon(Icons.Default.DriveFolderUpload, null); Spacer(Modifier.width(6.dp)); Text("상위 폴더") } }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(folderName, { folderName = it }, label = { Text("새 폴더") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val name = folderName.trim()
                        if (name.isNotEmpty()) runCatching { store.createDirectory(joinWebHardPath(currentPath, name)) }
                            .onSuccess { folderName = ""; refresh() }.onFailure { error = it.message }
                    }) { Icon(Icons.Default.CreateNewFolder, "폴더 만들기") }
                }
            }
            if (fileItems.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MoveToInbox, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(9.dp)); Text("파일이 없습니다", fontWeight = FontWeight.SemiBold)
                    Text("접속한 기기에서 파일을 올리면 바로 표시됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else when {
                layout == WebHardLayout.LIST -> items(fileItems, key = { it.path }) { item ->
                    WebHardListRow(item, store, thumbnails, onOpen = { openFolder(item) }, onShare = { share(item) }, onDelete = { deleteCandidate = item })
                }
                layout == WebHardLayout.LARGE && landscape -> item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(fileItems, key = { it.path }) { item ->
                            WebHardGridCard(item, store, thumbnails, Modifier.width(190.dp), onOpen = { openFolder(item) }, onShare = { share(item) }, onDelete = { deleteCandidate = item })
                        }
                    }
                }
                else -> {
                    val columns = when {
                        largeFont && layout == WebHardLayout.LARGE -> 1
                        largeFont -> 2
                        layout == WebHardLayout.LARGE -> 2
                        else -> 3
                    }
                    items(fileItems.chunked(columns)) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            row.forEach { item -> WebHardGridCard(item, store, thumbnails, Modifier.weight(1f), onOpen = { openFolder(item) }, onShare = { share(item) }, onDelete = { deleteCandidate = item }) }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        }
    }

    deleteCandidate?.let { item ->
        AlertDialog(onDismissRequest = { deleteCandidate = null }, title = { Text("삭제할까요?") }, text = { Text("${item.name}을(를) 삭제합니다.") }, dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("취소") } }, confirmButton = { TextButton(onClick = { deleteCandidate = null; runCatching { store.delete(item.path) }.onSuccess { refresh() }.onFailure { error = it.message } }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") } })
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, confirmButton = { TextButton(onClick = { error = null }) { Text("확인") } }, title = { Text("폰하드") }, text = { Text(message) }) }
}

@Composable
private fun WebHardCoverFlow(
    items: List<WebHardFileItem>,
    currentPath: String,
    usesDarkBackground: Boolean,
    store: WebHardFileStore,
    thumbnails: MutableMap<String, Bitmap>,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onToggleBackground: (Boolean) -> Unit,
    onActivate: (WebHardFileItem) -> Unit,
) {
    var backgroundMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var selectedIndex by remember(items) { mutableIntStateOf(0) }
    LaunchedEffect(listState, items) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - center) }?.index
        }.distinctUntilChanged().collect { index -> if (index != null) selectedIndex = index }
    }
    val background = if (usesDarkBackground) Color.Black else Color(0xFFF7F8FA)
    val chromeForeground = if (usesDarkBackground) Color.White.copy(alpha = .88f) else Color.Black.copy(alpha = .82f)
    val chromeBackground = if (usesDarkBackground) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .92f)
    val chromeBorder = if (usesDarkBackground) Color.White.copy(alpha = .16f) else Color.Black.copy(alpha = .10f)

    BoxWithConstraints(modifier.background(background)) {
        val cardSide = minOf(maxWidth * .48f, maxHeight * .64f).coerceIn(140.dp, 330.dp)
        val titleMaxWidth = minOf(maxWidth * .44f, 340.dp)
        Box(
            Modifier.fillMaxWidth().height(if (usesDarkBackground) 72.dp else 82.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        if (usesDarkBackground) listOf(Color.White.copy(alpha = .10f), Color.Transparent)
                        else listOf(Color.White.copy(alpha = .88f), Color.Transparent)
                    )
                )
        )
        if (items.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.MoveToInbox, null, modifier = Modifier.size(48.dp), tint = chromeForeground.copy(alpha = .7f))
                Spacer(Modifier.height(10.dp))
                Text("파일이 없습니다", color = chromeForeground, fontWeight = FontWeight.SemiBold)
            }
        } else {
            LazyRow(
                state = listState,
                flingBehavior = rememberSnapFlingBehavior(listState),
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = (maxWidth - cardSide) / 2),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                items(items, key = { it.path }) { item ->
                    val index = items.indexOf(item)
                    val selected = index == selectedIndex
                    Column(
                        Modifier.width(cardSide).padding(bottom = 18.dp)
                            .graphicsLayer {
                                scaleX = if (selected) 1f else .80f
                                scaleY = if (selected) 1f else .80f
                                rotationY = when { selected -> 0f; index < selectedIndex -> 32f; else -> -32f }
                                cameraDistance = 14f * density
                                alpha = if (kotlin.math.abs(index - selectedIndex) > 3) .42f else 1f
                            }
                            .clearAndSetSemantics {
                                contentDescription = item.name
                                role = Role.Button
                                this.selected = selected
                            }
                            .clickable { onActivate(item) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        WebHardArtwork(
                            item,
                            store,
                            thumbnails,
                            Modifier.size(cardSide).clip(RoundedCornerShape(if (selected) 18.dp else 13.dp)),
                        )
                        Spacer(Modifier.height(2.dp))
                        WebHardArtwork(
                            item,
                            store,
                            thumbnails,
                            Modifier.width(cardSide).height((cardSide * if (usesDarkBackground) .15f else .10f).coerceAtMost(44.dp))
                                .graphicsLayer { rotationX = 180f; alpha = if (usesDarkBackground) .26f else .14f },
                            contentDescription = null,
                            loadThumbnail = false,
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                onClick = onBack,
                modifier = Modifier.size(44.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = chromeBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, chromeBorder),
                shadowElevation = if (usesDarkBackground) 8.dp else 2.dp,
            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.ChevronLeft, if (currentPath == "/") "폰하드 닫기" else "상위 폴더", tint = chromeForeground) } }
            Text(
                if (currentPath == "/") "폰하드" else currentPath,
                color = chromeForeground,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = titleMaxWidth).heightIn(min = 44.dp).wrapContentHeight(Alignment.CenterVertically),
            )
            Spacer(Modifier.weight(1f))
            Box {
                Surface(
                    onClick = { backgroundMenu = true },
                    modifier = Modifier.size(44.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = chromeBackground,
                    border = androidx.compose.foundation.BorderStroke(1.dp, chromeBorder),
                    shadowElevation = if (usesDarkBackground) 8.dp else 2.dp,
                ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MoreHoriz, "오버플로우 배경", tint = MaterialTheme.colorScheme.primary) } }
                DropdownMenu(backgroundMenu, onDismissRequest = { backgroundMenu = false }) {
                    DropdownMenuItem(text = { Text("흰색") }, onClick = { backgroundMenu = false; onToggleBackground(false) })
                    DropdownMenuItem(text = { Text("검정") }, onClick = { backgroundMenu = false; onToggleBackground(true) })
                }
            }
        }
    }
}

@Composable
private fun WebHardListRow(item: WebHardFileItem, store: WebHardFileStore, thumbnails: MutableMap<String, Bitmap>, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { ExtensionPreservingName(item.name) },
        supportingContent = { if (!item.isDirectory) Text(formatWebHardBytes(item.size ?: 0)) },
        leadingContent = { WebHardArtwork(item, store, thumbnails, Modifier.size(52.dp)) },
        trailingContent = { WebHardItemMenu(item, onShare, onDelete) },
        modifier = Modifier.clickable(enabled = item.isDirectory, onClick = onOpen),
    )
}

@Composable
private fun WebHardGridCard(item: WebHardFileItem, store: WebHardFileStore, thumbnails: MutableMap<String, Bitmap>, modifier: Modifier, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(modifier.clickable(enabled = item.isDirectory, onClick = onOpen)) {
        Box {
            WebHardArtwork(item, store, thumbnails, Modifier.fillMaxWidth().aspectRatio(1f))
            Box(Modifier.align(Alignment.TopEnd)) { WebHardItemMenu(item, onShare, onDelete) }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            if (!item.isDirectory) Text(formatWebHardBytes(item.size ?: 0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExtensionPreservingName(name: String) {
    val extension = name.substringAfterLast('.', "").takeIf { name.contains('.') }
    val stem = if (extension == null) name else name.removeSuffix(".$extension")
    Row { Text(stem, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis); if (extension != null) Text(".$extension", maxLines = 1) }
}

@Composable
private fun WebHardArtwork(
    item: WebHardFileItem,
    store: WebHardFileStore,
    thumbnails: MutableMap<String, Bitmap>,
    modifier: Modifier,
    contentDescription: String? = item.name,
    loadThumbnail: Boolean = true,
) {
    val key = "${item.path}|${item.modifiedAt}|${item.size}"
    if (loadThumbnail) LaunchedEffect(key) {
        if (!item.isDirectory && key !in thumbnails) withContext(Dispatchers.IO) { loadWebHardThumbnail(store.file(item.path), item) }?.let { thumbnails[key] = it }
    }
    Box(modifier.clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        thumbnails[key]?.let { Image(it.asImageBitmap(), contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(if (item.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, modifier = Modifier.fillMaxSize(.48f), tint = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WebHardItemMenu(item: WebHardFileItem, onShare: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "${item.name} 작업") }
        DropdownMenu(expanded, { expanded = false }) {
            if (!item.isDirectory) DropdownMenuItem(text = { Text("받기·공유") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { expanded = false; onShare() })
            DropdownMenuItem(text = { Text("삭제") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { expanded = false; onDelete() })
        }
    }
}

private fun loadWebHardThumbnail(file: File, item: WebHardFileItem): Bitmap? = runCatching {
    val extension = item.name.substringAfterLast('.', "").lowercase()
    if (extension in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 720) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    else if (extension in setOf("mp4", "mov", "m4v", "mkv", "webm")) MediaMetadataRetriever().let { retriever ->
        try {
            retriever.setDataSource(file.path)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                val ratio = minOf(720f / frame.width, 720f / frame.height, 1f)
                if (ratio >= 1f) frame else Bitmap.createScaledBitmap(frame, (frame.width * ratio).toInt().coerceAtLeast(1), (frame.height * ratio).toInt().coerceAtLeast(1), true).also { frame.recycle() }
            }
        } finally { retriever.release() }
    } else null
}.getOrNull()

private fun localIpv4Addresses(): List<Inet4Address> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { network -> network.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { isAdvertisableWebHardAddress(network.name.orEmpty(), it) } }.distinctBy { it.hostAddress }
}.getOrElse { emptyList() }

internal fun isAdvertisableWebHardAddress(interfaceName: String, address: Inet4Address): Boolean {
    if (address.isLoopbackAddress || address.isLinkLocalAddress) return false
    val bytes = address.address.map(Byte::toInt).map { it and 0xff }
    val privateLan = bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) || (bytes[0] == 192 && bytes[1] == 168)
    if (privateLan) return true
    if (bytes[0] == 192 && bytes[1] == 0 && bytes[2] == 0) return false
    val name = interfaceName.lowercase()
    return name.startsWith("tun") || name.startsWith("wg") || name.contains("tailscale") || name.contains("wireguard")
}

private fun joinWebHardPath(parent: String, name: String) = if (parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"
private fun safeWebHardShareName(name: String) = DownloadCacheContract.safeFilename(name)
private fun safeWebHardShareRoot(cacheDirectory: File): File {
    val cacheRoot = cacheDirectory.canonicalFile
    val requestedRoot = File(cacheRoot, "shares")
    check(!java.nio.file.Files.isSymbolicLink(requestedRoot.toPath())) { "공유 캐시 폴더가 안전하지 않습니다." }
    check(requestedRoot.mkdirs() || requestedRoot.isDirectory) { "공유 캐시 폴더를 만들 수 없습니다." }
    return requestedRoot.canonicalFile.also { root ->
        check(root.parentFile == cacheRoot && !java.nio.file.Files.isSymbolicLink(requestedRoot.toPath())) {
            "공유 캐시 폴더가 안전하지 않습니다."
        }
    }
}

private fun pruneWebHardShares(root: File, incomingBytes: Long) {
    check(incomingBytes in 0..WEBHARD_SHARE_MAX_BYTES) { "512MB보다 큰 파일은 공유할 수 없습니다." }
    val now = System.currentTimeMillis()
    val directories = root.listFiles().orEmpty().filter { directory ->
        directory.isDirectory && !java.nio.file.Files.isSymbolicLink(directory.toPath()) &&
            directory.name.startsWith("webhard-") &&
            runCatching { UUID.fromString(directory.name.removePrefix("webhard-")) }.isSuccess &&
            directory.canonicalFile.parentFile == root
    }
    directories.filter { now - it.lastModified() > WEBHARD_SHARE_MAX_AGE_MILLIS }
        .forEach { check(cleanupWebHardShareDirectory(it)) { "오래된 폰하드 공유 캐시를 정리하지 못했습니다." } }
    val remaining = directories.filter(File::exists).sortedBy(File::lastModified).toMutableList()
    var bytes = remaining.sumOf(::webHardShareDirectoryBytes)
    while (remaining.isNotEmpty() && bytes + incomingBytes > WEBHARD_SHARE_MAX_BYTES) {
        val oldest = remaining.removeAt(0)
        val oldestBytes = webHardShareDirectoryBytes(oldest)
        check(cleanupWebHardShareDirectory(oldest)) { "오래된 폰하드 공유 캐시를 정리하지 못했습니다." }
        bytes -= oldestBytes
    }
}

private fun webHardShareDirectoryBytes(directory: File): Long = directory.listFiles().orEmpty().sumOf { child ->
    if (!java.nio.file.Files.isSymbolicLink(child.toPath()) && child.isFile) child.length() else 0L
}

private fun cleanupWebHardShareDirectory(directory: File): Boolean {
    if (java.nio.file.Files.isSymbolicLink(directory.toPath()) ||
        !directory.name.startsWith("webhard-") ||
        runCatching { UUID.fromString(directory.name.removePrefix("webhard-")) }.isFailure
    ) return false
    directory.listFiles()?.forEach { child ->
        if (!java.nio.file.Files.isSymbolicLink(child.toPath()) && child.isFile) child.delete()
    }
    return directory.delete() || !directory.exists()
}

private const val WEBHARD_SHARE_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L
private const val WEBHARD_SHARE_MAX_BYTES = 512L * 1024L * 1024L
private fun formatWebHardBytes(value: Long): String = when { value < 1024 -> "$value B"; value < 1024 * 1024 -> "%.1f KB".format(value / 1024.0); value < 1024L * 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024)); else -> "%.1f GB".format(value / (1024.0 * 1024 * 1024)) }
