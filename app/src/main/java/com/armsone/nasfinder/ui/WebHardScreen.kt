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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.armsone.nasfinder.platform.NasFinderShareIntentFactory
import com.armsone.nasfinder.platform.WebHardFileItem
import com.armsone.nasfinder.platform.WebHardFileStore
import com.armsone.nasfinder.platform.WebHardHttpServer
import com.armsone.nasfinder.model.AppTheme
import com.armsone.nasfinder.model.RemoteFileItem
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

private enum class WebHardLayout(val title: String) {
    LIST("자세히"), SMALL("작은 썸네일"), LARGE("포스터")
}

private val BkPanelCharcoal = Color(0xFF1A1C1F)
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
    val fieldHeight = if (enamel) {
        if (largeFont) 48.dp else 40.dp
    } else {
        if (largeFont) 56.dp else 48.dp
    }
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
        Column(
            Modifier.fillMaxWidth().padding(if (enamel) 14.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (enamel) 10.dp else 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (enamel) 9.dp else 10.dp),
            ) {
                if (enamel) {
                    PhoneHardMark(28.dp)
                } else {
                    Icon(Icons.Default.WifiTethering, null, tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "서버 열기",
                    style = if (enamel) {
                        MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp)
                    } else MaterialTheme.typography.titleMedium,
                    fontWeight = if (enamel) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (enamel) BkPanelCharcoal else Color.Unspecified,
                )
                Spacer(Modifier.weight(1f))
                if (enamel) {
                    PhoneHardRefreshButton(
                        onClick = connection::refreshAddresses,
                        enabled = connection.server == null,
                    )
                }
            }

            if (enamel) {
                PhoneHardBkAddressRow(connection, largeFont)
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = addressHeight).padding(start = 12.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                "접속 주소",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                connection.selectedAddress?.hostAddress ?: "사용 가능한 접속 주소가 없습니다",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = connection::refreshAddresses, enabled = connection.server == null) {
                            Icon(Icons.Default.Refresh, "접속 주소 새로고침")
                        }
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

            if (!enamel && connection.server != null && connection.selectedAddress != null) {
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
                .shadow(8.dp, panelShape, ambientColor = Color(0x24000000), spotColor = Color(0x24000000))
                .clip(panelShape)
                .background(Brush.linearGradient(listOf(Color.White, Color(0xFFF7F6F2))))
                .border(
                    1.25.dp,
                    Brush.linearGradient(
                        listOf(Color.White, Color(0xFF7A7D80), Color.White.copy(alpha = .82f)),
                    ),
                    panelShape,
                ),
        ) {
            content()
            HorizontalDivider(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .padding(horizontal = 22.dp).padding(top = 2.dp),
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
private fun PhoneHardRefreshButton(onClick: () -> Unit, enabled: Boolean) {
    Box(
        Modifier.size(44.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(30.dp)
                .shadow(2.4.dp, CircleShape, ambientColor = Color.Transparent, spotColor = Color(0x3D000000))
                .graphicsLayer { alpha = if (enabled) 1f else .45f },
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(
                1.5.dp,
                Brush.linearGradient(listOf(Color.White, Color(0xFF5C6166))),
            ),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        listOf(Color.White, Color(0xFFD1D1CC)),
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Refresh,
                    "접속 주소 새로고침",
                    tint = Color(0xFF292B2E),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun PhoneHardBkAddressRow(connection: WebHardConnectionState, largeFont: Boolean) {
    val address = connection.selectedAddress
    val value = when {
        address == null -> "사용 가능한 접속 주소가 없습니다"
        connection.server != null -> "http://${address.hostAddress}:${connection.port}"
        else -> listOfNotNull(webHardNetworkKind(address), address.hostAddress).joinToString(" · ")
    }
    if (largeFont) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PhoneHardBkAddressLabel()
            PhoneHardBkAddressValue(value, Modifier.fillMaxWidth())
        }
    } else {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoneHardBkAddressLabel()
            Spacer(Modifier.weight(1f))
            PhoneHardBkAddressValue(value, Modifier.widthIn(max = 250.dp))
        }
    }
}

@Composable
private fun PhoneHardBkAddressLabel() {
    Text(
        "접속 주소",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
        color = Color(0xFF686C6F),
    )
}

@Composable
private fun PhoneHardBkAddressValue(value: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.selection.SelectionContainer(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            ),
            color = BkPanelCharcoal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun webHardNetworkKind(address: Inet4Address): String? {
    val interfaceName = runCatching { NetworkInterface.getByInetAddress(address)?.name }
        .getOrNull()
        ?.lowercase()
        ?: return null
    return when {
        interfaceName.startsWith("wlan") || interfaceName.startsWith("wifi") -> "Wi-Fi"
        interfaceName.startsWith("eth") -> "이더넷"
        interfaceName.startsWith("rmnet") || interfaceName.startsWith("ccmni") -> "셀룰러"
        interfaceName.startsWith("tun") || interfaceName.startsWith("wg") ||
            interfaceName.contains("tailscale") || interfaceName.contains("wireguard") -> "네트워크"
        else -> null
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
        shape = RoundedCornerShape(if (enamel) 20.dp else 12.dp),
        colors = if (enamel) {
            ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0xFFD9D9D7),
                contentColor = BkPanelCharcoal,
            )
        } else ButtonDefaults.outlinedButtonColors(),
        border = BorderStroke(1.dp, if (enamel) Color(0xFFD1D1CF) else MaterialTheme.colorScheme.outline),
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
    val isRunning = connection.server != null
    val label = if (connection.server == null) "열기" else "닫기"
    if (!enamel) {
        Button(onClick = connection::toggle, enabled = enabled, modifier = modifier) { Text(label) }
        return
    }
    Surface(
        onClick = connection::toggle,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minWidth = 80.dp, minHeight = 40.dp)
            .shadow(4.dp, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        contentColor = if (isRunning) BkPanelCharcoal else Color.White,
        border = BorderStroke(1.dp, Color(0xFF6D7275)),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    if (!enabled) listOf(Color(0xFFB9BBBA), Color(0xFF8D9090))
                    else if (isRunning) listOf(Color.White, Color(0xFFD4D4CF))
                    else listOf(Color(0xFF474A4D), Color(0xFF14171A))
                ),
            ).padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalDivider(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp).padding(top = 2.dp),
                thickness = 1.dp,
                color = Color.White.copy(alpha = if (isRunning) .92f else .36f),
            )
            Text(
                label,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp),
                fontWeight = FontWeight.SemiBold,
                color = if (isRunning) BkPanelCharcoal else Color.White,
            )
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
    val theme = LocalNasFinderTheme.current
    val scope = rememberCoroutineScope()
    val requested = remember(items) { mutableSetOf<String>() }
    val itemsByPath = remember(items) { items.associateBy(WebHardFileItem::path) }
    val coverItems = remember(items) {
        items.map { item ->
            RemoteFileItem(
                id = item.path,
                name = item.name,
                path = item.path,
                isDirectory = item.isDirectory,
                size = item.size ?: 0,
                modifiedAt = item.modifiedAt,
            )
        }
    }

    Box(modifier) {
        RemoteBrowserCoverFlow(
            items = coverItems,
            thumbnails = thumbnails,
            theme = theme,
            title = if (currentPath == "/") "폰하드" else currentPath,
            usesDarkBackground = usesDarkBackground,
            onBack = onBack,
            onToggleBackground = onToggleBackground,
            onActivate = { item -> itemsByPath[item.path]?.let(onActivate) },
            onLoadThumbnail = { item ->
                val webHardItem = itemsByPath[item.path] ?: return@RemoteBrowserCoverFlow
                if (requested.add(item.id)) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            loadWebHardThumbnail(store.file(webHardItem.path), webHardItem)
                        }?.let { thumbnails[item.id] = it }
                    }
                }
            },
        )
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
    if (extension in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")) {
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
