package com.armsone.nasfinder.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.armsone.nasfinder.R
import com.armsone.nasfinder.model.InterfaceIconPack
import com.armsone.nasfinder.ui.theme.LocalInterfaceIconPack
import com.armsone.nasfinder.ui.theme.LocalThemePalette
import androidx.compose.material3.Icon as MaterialIcon

/** Glyph identifiers shared by the four interface icon packs (production manifest order). */
enum class PackGlyph { HOME, NAS, PHOTOS, PHONEHARD, CLOUD, BROWSER, VIDEO, DRAFTS, TRANSFER, STORAGE, SETTINGS, FAVORITES, BACK, CLOSE, ADD, CHECK, SAVE, SHARE, EDIT, PLAY, PAUSE, SORT, GRID, TRASH, POCKET, PRIVACY, ANTENNA, APP_ICON, ARROW_LEFT, ARROW_RIGHT, AUTO_DELETE, BRAND, BROWSER_BACK, CABLE, CALENDAR, COLLAPSE_UP, COLLECTION, COMPRESS, CONNECTIONS, CONNECTION_ADD, CONNECTION_ERROR, CONNECTION_UNKNOWN, COPY, DESELECT_ALL, DEVICE_FILES, DISPLAY_DARK, DISPLAY_LIGHT, DOWNLOAD, DUPLICATE, EMPTY, ERROR, EXPAND_COLLAPSE, EXPAND_DOWN, EXPORT, EXTERNAL_LINK, EXTERNAL_PLAYER, FILE, FILE_ADD, FILE_ARCHIVE, FILE_PDF, FILE_TEXT, FOLDER, FOLDER_ADD, FOLDER_SELECTED, FOLDER_SETTINGS, FOLDER_SHARED, FOLDER_UPLOAD, FORWARD, FULLSCREEN, FULLSCREEN_EXIT, HAND_TAP, HELP, HISTORY, HISTORY_DONE, IMPORT, INFINITY, INFO, KEY, KEY_UNAVAILABLE, LINK, LIST, LIVE_PHOTO, LOCK, MENU, MORE, MOVE, MUSIC, MUTE, NETWORK, NETWORK_OFF, NETWORK_SECURE, NEXT, OPEN_SOURCE, PASTE, PHOTO_SELECTION, PIN, PREVIOUS, PROFILE, PROGRESS, QR_CODE, QR_SCAN, RECEIVE, REDO, REFRESH, REPEAT, REPEAT_ONE, SEARCH, SEARCH_FILE, SELECTED, SELECT_ALL, SEND, SHIELD, SHUFFLE, SLIDERS, SPARKLES, STACK, STATISTICS, STOP, SUPER_THUMBNAIL, THEME, THEME_HALF, THEME_HEXAGON, THUMBNAIL_CACHE, THUMBNAIL_STORAGE, UNDO, UNFAVORITE, UNSELECTED, UNSUPPORTED_VIDEO, UPLOAD, USER_KEY, VISIBILITY, VOLUME, WARNING, WAVEFORM, WIFI, WIFI_ERROR, WORKBENCH }

private fun packDrawable(pack: InterfaceIconPack, glyph: PackGlyph): Int = when (pack) {
    InterfaceIconPack.SIMPLE -> when (glyph) {
        PackGlyph.HOME -> R.drawable.pk_simple_home
        PackGlyph.NAS -> R.drawable.pk_simple_nas
        PackGlyph.PHOTOS -> R.drawable.pk_simple_photos
        PackGlyph.PHONEHARD -> R.drawable.pk_simple_phonehard
        PackGlyph.CLOUD -> R.drawable.pk_simple_cloud
        PackGlyph.BROWSER -> R.drawable.pk_simple_browser
        PackGlyph.VIDEO -> R.drawable.pk_simple_video
        PackGlyph.DRAFTS -> R.drawable.pk_simple_drafts
        PackGlyph.TRANSFER -> R.drawable.pk_simple_transfer
        PackGlyph.STORAGE -> R.drawable.pk_simple_storage
        PackGlyph.SETTINGS -> R.drawable.pk_simple_settings
        PackGlyph.FAVORITES -> R.drawable.pk_simple_favorites
        PackGlyph.BACK -> R.drawable.pk_simple_back
        PackGlyph.CLOSE -> R.drawable.pk_simple_close
        PackGlyph.ADD -> R.drawable.pk_simple_add
        PackGlyph.CHECK -> R.drawable.pk_simple_check
        PackGlyph.SAVE -> R.drawable.pk_simple_save
        PackGlyph.SHARE -> R.drawable.pk_simple_share
        PackGlyph.EDIT -> R.drawable.pk_simple_edit
        PackGlyph.PLAY -> R.drawable.pk_simple_play
        PackGlyph.PAUSE -> R.drawable.pk_simple_pause
        PackGlyph.SORT -> R.drawable.pk_simple_sort
        PackGlyph.GRID -> R.drawable.pk_simple_grid
        PackGlyph.TRASH -> R.drawable.pk_simple_trash
        PackGlyph.POCKET -> R.drawable.pk_simple_phonehard
        PackGlyph.PRIVACY -> R.drawable.pk_simple_settings
        else -> R.drawable.pk_simple_settings
    }
    InterfaceIconPack.SOLID -> when (glyph) {
        PackGlyph.HOME -> R.drawable.pk_solid_home
        PackGlyph.NAS -> R.drawable.pk_solid_nas
        PackGlyph.PHOTOS -> R.drawable.pk_solid_photos
        PackGlyph.PHONEHARD -> R.drawable.pk_solid_phonehard
        PackGlyph.CLOUD -> R.drawable.pk_solid_cloud
        PackGlyph.BROWSER -> R.drawable.pk_solid_browser
        PackGlyph.VIDEO -> R.drawable.pk_solid_video
        PackGlyph.DRAFTS -> R.drawable.pk_solid_drafts
        PackGlyph.TRANSFER -> R.drawable.pk_solid_transfer
        PackGlyph.STORAGE -> R.drawable.pk_solid_storage
        PackGlyph.SETTINGS -> R.drawable.pk_solid_settings
        PackGlyph.FAVORITES -> R.drawable.pk_solid_favorites
        PackGlyph.BACK -> R.drawable.pk_solid_back
        PackGlyph.CLOSE -> R.drawable.pk_solid_close
        PackGlyph.ADD -> R.drawable.pk_solid_add
        PackGlyph.CHECK -> R.drawable.pk_solid_check
        PackGlyph.SAVE -> R.drawable.pk_solid_save
        PackGlyph.SHARE -> R.drawable.pk_solid_share
        PackGlyph.EDIT -> R.drawable.pk_solid_edit
        PackGlyph.PLAY -> R.drawable.pk_solid_play
        PackGlyph.PAUSE -> R.drawable.pk_solid_pause
        PackGlyph.SORT -> R.drawable.pk_solid_sort
        PackGlyph.GRID -> R.drawable.pk_solid_grid
        PackGlyph.TRASH -> R.drawable.pk_solid_trash
        PackGlyph.POCKET -> R.drawable.pk_solid_phonehard
        // No legacy solid artwork: 0 lets PackIcon draw the glyph's own complete-pack shape (never a settings gear).
        else -> 0
    }
    InterfaceIconPack.DIMENSION -> when (glyph) {
        PackGlyph.HOME -> R.drawable.pk_dimension_home
        PackGlyph.NAS -> R.drawable.pk_dimension_nas
        PackGlyph.PHOTOS -> R.drawable.pk_dimension_photos
        PackGlyph.PHONEHARD -> R.drawable.pk_dimension_phonehard
        PackGlyph.CLOUD -> R.drawable.pk_dimension_cloud
        PackGlyph.BROWSER -> R.drawable.pk_dimension_browser
        PackGlyph.VIDEO -> R.drawable.pk_dimension_video
        PackGlyph.DRAFTS -> R.drawable.pk_dimension_drafts
        PackGlyph.TRANSFER -> R.drawable.pk_dimension_transfer
        PackGlyph.STORAGE -> R.drawable.pk_dimension_storage
        PackGlyph.SETTINGS -> R.drawable.pk_dimension_settings
        PackGlyph.FAVORITES -> R.drawable.pk_dimension_favorites
        PackGlyph.BACK -> R.drawable.pk_dimension_back
        PackGlyph.CLOSE -> R.drawable.pk_dimension_close
        PackGlyph.ADD -> R.drawable.pk_dimension_add
        PackGlyph.CHECK -> R.drawable.pk_dimension_check
        PackGlyph.SAVE -> R.drawable.pk_dimension_save
        PackGlyph.SHARE -> R.drawable.pk_dimension_share
        PackGlyph.EDIT -> R.drawable.pk_dimension_edit
        PackGlyph.PLAY -> R.drawable.pk_dimension_play
        PackGlyph.PAUSE -> R.drawable.pk_dimension_pause
        PackGlyph.SORT -> R.drawable.pk_dimension_sort
        PackGlyph.GRID -> R.drawable.pk_dimension_grid
        PackGlyph.TRASH -> R.drawable.pk_dimension_trash
        PackGlyph.POCKET -> R.drawable.pk_dimension_pocket
        PackGlyph.PRIVACY -> R.drawable.pk_dimension_privacy
        else -> R.drawable.pk_dimension_settings
    }
    InterfaceIconPack.REALISTIC -> when (glyph) {
        PackGlyph.HOME -> R.drawable.pk_realistic_home
        PackGlyph.NAS -> R.drawable.pk_realistic_nas
        PackGlyph.PHOTOS -> R.drawable.pk_realistic_photos
        PackGlyph.PHONEHARD -> R.drawable.pk_realistic_phonehard
        PackGlyph.CLOUD -> R.drawable.pk_realistic_cloud
        PackGlyph.BROWSER -> R.drawable.pk_realistic_browser
        PackGlyph.VIDEO -> R.drawable.pk_realistic_video
        PackGlyph.DRAFTS -> R.drawable.pk_realistic_drafts
        PackGlyph.TRANSFER -> R.drawable.pk_realistic_transfer
        PackGlyph.STORAGE -> R.drawable.pk_realistic_storage
        PackGlyph.SETTINGS -> R.drawable.pk_realistic_settings
        PackGlyph.FAVORITES -> R.drawable.pk_realistic_favorites
        PackGlyph.BACK -> R.drawable.pk_realistic_back
        PackGlyph.CLOSE -> R.drawable.pk_realistic_close
        PackGlyph.ADD -> R.drawable.pk_realistic_add
        PackGlyph.CHECK -> R.drawable.pk_realistic_check
        PackGlyph.SAVE -> R.drawable.pk_realistic_save
        PackGlyph.SHARE -> R.drawable.pk_realistic_share
        PackGlyph.EDIT -> R.drawable.pk_realistic_edit
        PackGlyph.PLAY -> R.drawable.pk_realistic_play
        PackGlyph.PAUSE -> R.drawable.pk_realistic_pause
        PackGlyph.SORT -> R.drawable.pk_realistic_sort
        PackGlyph.GRID -> R.drawable.pk_realistic_grid
        PackGlyph.TRASH -> R.drawable.pk_realistic_trash
        PackGlyph.POCKET -> R.drawable.pk_realistic_phonehard
        PackGlyph.PRIVACY -> R.drawable.pk_realistic_settings
        else -> R.drawable.pk_realistic_settings
    }
}

private val materialGlyphs: Map<String, PackGlyph> = mapOf(
    "Filled.Home" to PackGlyph.HOME,
    "Filled.Folder" to PackGlyph.POCKET,
    "Filled.FolderOpen" to PackGlyph.POCKET,
    "Filled.Dns" to PackGlyph.NAS,
    "Filled.PhotoLibrary" to PackGlyph.PHOTOS,
    "Filled.Photo" to PackGlyph.PHOTOS,
    "Filled.Image" to PackGlyph.PHOTOS,
    "Filled.Collections" to PackGlyph.PHOTOS,
    "Filled.AddPhotoAlternate" to PackGlyph.PHOTOS,
    "Filled.PhoneAndroid" to PackGlyph.PHONEHARD,
    "Filled.Smartphone" to PackGlyph.PHONEHARD,
    "Filled.Cloud" to PackGlyph.CLOUD,
    "Filled.CloudQueue" to PackGlyph.CLOUD,
    "Filled.CloudCircle" to PackGlyph.CLOUD,
    "Filled.Language" to PackGlyph.BROWSER,
    "Filled.Public" to PackGlyph.BROWSER,
    "Filled.Movie" to PackGlyph.VIDEO,
    "Filled.MovieFilter" to PackGlyph.VIDEO,
    "Filled.VideoFile" to PackGlyph.VIDEO,
    "Filled.VideoCall" to PackGlyph.VIDEO,
    "Filled.LiveTv" to PackGlyph.VIDEO,
    "AutoMirrored.Filled.CompareArrows" to PackGlyph.TRANSFER,
    "Filled.SwapVert" to PackGlyph.TRANSFER,
    "Filled.Storage" to PackGlyph.STORAGE,
    "Filled.SdCard" to PackGlyph.STORAGE,
    "Filled.Settings" to PackGlyph.SETTINGS,
    "Filled.Tune" to PackGlyph.SETTINGS,
    "Filled.ArrowBack" to PackGlyph.BACK,
    "AutoMirrored.Filled.ArrowBack" to PackGlyph.BACK,
    "Filled.Close" to PackGlyph.CLOSE,
    "Filled.Add" to PackGlyph.ADD,
    "Filled.Check" to PackGlyph.CHECK,
    "Filled.SaveAlt" to PackGlyph.SAVE,
    "Filled.Download" to PackGlyph.SAVE,
    "Filled.Share" to PackGlyph.SHARE,
    "Filled.Edit" to PackGlyph.EDIT,
    "Filled.PlayArrow" to PackGlyph.PLAY,
    "Filled.Pause" to PackGlyph.PAUSE,
    "Filled.FilterList" to PackGlyph.SORT,
    "Filled.GridView" to PackGlyph.GRID,
    "Filled.GridOn" to PackGlyph.GRID,
    "Filled.ViewModule" to PackGlyph.GRID,
    "Filled.Delete" to PackGlyph.TRASH,
    "Filled.DeleteOutline" to PackGlyph.TRASH,
    "Filled.Star" to PackGlyph.FAVORITES,
)

private val designGlyphs: Map<String, PackGlyph> = mapOf(
    "AutoMirrored.Filled.ArrowBack" to PackGlyph.BACK,
    "AutoMirrored.Filled.ArrowForward" to PackGlyph.FORWARD,
    "AutoMirrored.Filled.CompareArrows" to PackGlyph.TRANSFER,
    "AutoMirrored.Filled.NoteAdd" to PackGlyph.FILE_ADD,
    "AutoMirrored.Filled.Redo" to PackGlyph.REDO,
    "AutoMirrored.Filled.Undo" to PackGlyph.UNDO,
    "Filled.Add" to PackGlyph.ADD,
    "Filled.AddPhotoAlternate" to PackGlyph.PHOTOS,
    "Filled.AllInclusive" to PackGlyph.INFINITY,
    "Filled.Apps" to PackGlyph.GRID,
    "Filled.ArrowBack" to PackGlyph.BACK,
    "Filled.ArrowDownward" to PackGlyph.DOWNLOAD,
    "Filled.ArrowDropDown" to PackGlyph.EXPAND_DOWN,
    "Filled.ArrowForward" to PackGlyph.FORWARD,
    "Filled.ArrowUpward" to PackGlyph.UPLOAD,
    "Filled.Audiotrack" to PackGlyph.MUSIC,
    "Filled.AutoAwesome" to PackGlyph.SPARKLES,
    "Filled.AutoDelete" to PackGlyph.AUTO_DELETE,
    "Filled.Bookmarks" to PackGlyph.FAVORITES,
    "Filled.Cached" to PackGlyph.THUMBNAIL_CACHE,
    "Filled.Cancel" to PackGlyph.CLOSE,
    "Filled.Check" to PackGlyph.CHECK,
    "Filled.CheckCircle" to PackGlyph.CHECK,
    "Filled.ChevronLeft" to PackGlyph.BACK,
    "Filled.ChevronRight" to PackGlyph.FORWARD,
    "Filled.Close" to PackGlyph.CLOSE,
    "Filled.Cloud" to PackGlyph.CLOUD,
    "Filled.CloudCircle" to PackGlyph.CLOUD,
    "Filled.CloudQueue" to PackGlyph.CLOUD,
    "Filled.CloudUpload" to PackGlyph.UPLOAD,
    "Filled.Code" to PackGlyph.OPEN_SOURCE,
    "Filled.Collections" to PackGlyph.PHOTOS,
    "Filled.Compress" to PackGlyph.COMPRESS,
    "Filled.ContentCopy" to PackGlyph.COPY,
    "Filled.ContentPaste" to PackGlyph.PASTE,
    "Filled.CreateNewFolder" to PackGlyph.FOLDER_ADD,
    "Filled.Delete" to PackGlyph.TRASH,
    "Filled.DeleteOutline" to PackGlyph.TRASH,
    "Filled.Clear" to PackGlyph.CLOSE,
    "Filled.CheckBox" to PackGlyph.SELECT_ALL,
    "Filled.AddToDrive" to PackGlyph.CLOUD,
    "Filled.Description" to PackGlyph.FILE_TEXT,
    "Filled.Dns" to PackGlyph.NAS,
    "Filled.Download" to PackGlyph.DOWNLOAD,
    "Filled.DriveFileMove" to PackGlyph.MOVE,
    "Filled.DriveFolderUpload" to PackGlyph.FOLDER_UPLOAD,
    "Filled.Edit" to PackGlyph.EDIT,
    "Filled.ErrorOutline" to PackGlyph.ERROR,
    "Filled.ExpandLess" to PackGlyph.COLLAPSE_UP,
    "Filled.ExpandMore" to PackGlyph.EXPAND_DOWN,
    "Filled.FilterList" to PackGlyph.SORT,
    "Filled.FindInPage" to PackGlyph.SEARCH_FILE,
    "Filled.Folder" to PackGlyph.FOLDER,
    "Filled.FolderOpen" to PackGlyph.FOLDER,
    "Filled.FolderShared" to PackGlyph.FOLDER_SHARED,
    "Filled.FolderSpecial" to PackGlyph.FAVORITES,
    "Filled.Fullscreen" to PackGlyph.FULLSCREEN,
    "Filled.FullscreenExit" to PackGlyph.FULLSCREEN_EXIT,
    "Filled.GridOn" to PackGlyph.GRID,
    "Filled.GridView" to PackGlyph.GRID,
    "Filled.History" to PackGlyph.HISTORY,
    "Filled.Home" to PackGlyph.HOME,
    "Filled.Hub" to PackGlyph.CONNECTIONS,
    "Filled.Image" to PackGlyph.PHOTOS,
    "Filled.Inbox" to PackGlyph.WORKBENCH,
    "Filled.Info" to PackGlyph.INFO,
    "Filled.InsertDriveFile" to PackGlyph.FILE,
    "Filled.Key" to PackGlyph.KEY,
    "Filled.KeyboardArrowDown" to PackGlyph.EXPAND_DOWN,
    "Filled.KeyboardArrowUp" to PackGlyph.COLLAPSE_UP,
    "Filled.Language" to PackGlyph.BROWSER,
    "Filled.Link" to PackGlyph.LINK,
    "Filled.List" to PackGlyph.LIST,
    "Filled.LiveTv" to PackGlyph.VIDEO,
    "Filled.Menu" to PackGlyph.MENU,
    "Filled.MoreHoriz" to PackGlyph.MORE,
    "Filled.MoreVert" to PackGlyph.MORE,
    "Filled.MotionPhotosOn" to PackGlyph.LIVE_PHOTO,
    "Filled.MoveToInbox" to PackGlyph.DOWNLOAD,
    "Filled.Movie" to PackGlyph.VIDEO,
    "Filled.MovieFilter" to PackGlyph.VIDEO,
    "Filled.MusicNote" to PackGlyph.MUSIC,
    "Filled.OpenInBrowser" to PackGlyph.EXTERNAL_PLAYER,
    "Filled.Palette" to PackGlyph.THEME,
    "Filled.Password" to PackGlyph.KEY,
    "Filled.Pause" to PackGlyph.PAUSE,
    "Filled.Person" to PackGlyph.PROFILE,
    "Filled.PhoneAndroid" to PackGlyph.PHONEHARD,
    "Filled.Photo" to PackGlyph.PHOTOS,
    "Filled.PhotoLibrary" to PackGlyph.PHOTOS,
    "Filled.PictureAsPdf" to PackGlyph.FILE_PDF,
    "Filled.PlayArrow" to PackGlyph.PLAY,
    "Filled.PlayCircle" to PackGlyph.PLAY,
    "Filled.Public" to PackGlyph.BROWSER,
    "Filled.PushPin" to PackGlyph.PIN,
    "Filled.QrCode" to PackGlyph.QR_CODE,
    "Filled.QrCodeScanner" to PackGlyph.QR_SCAN,
    "Filled.RadioButtonChecked" to PackGlyph.SELECTED,
    "Filled.RadioButtonUnchecked" to PackGlyph.UNSELECTED,
    "Filled.Refresh" to PackGlyph.REFRESH,
    "Filled.Repeat" to PackGlyph.REPEAT,
    "Filled.RepeatOne" to PackGlyph.REPEAT_ONE,
    "Filled.SaveAlt" to PackGlyph.WORKBENCH,
    "Filled.Schedule" to PackGlyph.HISTORY,
    "Filled.SdCard" to PackGlyph.DEVICE_FILES,
    "Filled.Search" to PackGlyph.SEARCH,
    "Filled.Security" to PackGlyph.LOCK,
    "Filled.Send" to PackGlyph.SEND,
    "Filled.Settings" to PackGlyph.SETTINGS,
    "Filled.Share" to PackGlyph.SHARE,
    "Filled.Shuffle" to PackGlyph.SHUFFLE,
    "Filled.SkipNext" to PackGlyph.NEXT,
    "Filled.SkipPrevious" to PackGlyph.PREVIOUS,
    "Filled.Smartphone" to PackGlyph.PHONEHARD,
    "Filled.Star" to PackGlyph.FAVORITES,
    "Filled.StarBorder" to PackGlyph.FAVORITES,
    "Filled.Stop" to PackGlyph.STOP,
    "Filled.Storage" to PackGlyph.DEVICE_FILES,
    "Filled.SwapVert" to PackGlyph.SORT,
    "Filled.Tune" to PackGlyph.SLIDERS,
    "Filled.VideoCall" to PackGlyph.VIDEO,
    "Filled.VideoFile" to PackGlyph.VIDEO,
    "Filled.VideoLibrary" to PackGlyph.COLLECTION,
    "Filled.ViewCarousel" to PackGlyph.COLLECTION,
    "Filled.ViewList" to PackGlyph.LIST,
    "Filled.ViewModule" to PackGlyph.GRID,
    "Filled.Visibility" to PackGlyph.VISIBILITY,
    "Filled.Warning" to PackGlyph.WARNING,
    "Filled.Wifi" to PackGlyph.WIFI,
    "Filled.WifiOff" to PackGlyph.WIFI_ERROR,
    "Filled.WifiTethering" to PackGlyph.NETWORK,
)

/**
 * App-wide icon standards. Meaningful action glyphs use [IconGlyphMin] by default (24dp); primary action
 * toolbars use [ToolbarActionIcon] (28dp); interactive controls offer at least [IconHitTarget] (48dp).
 */
val IconGlyphMin = 24.dp
val ToolbarActionIcon = 28.dp
val IconHitTarget = 48.dp
private val IconFloor = Modifier.sizeIn(minWidth = IconGlyphMin, minHeight = IconGlyphMin)

/** Central action glyphs rendered as flat single-colour vectors, keeping service/brand illustrations separate. */
fun isActionGlyph(glyph: PackGlyph): Boolean = when (glyph) {
    PackGlyph.BACK,
    PackGlyph.CLOSE,
    PackGlyph.PLAY,
    PackGlyph.PAUSE,
    PackGlyph.SHARE,
    PackGlyph.SAVE,
    PackGlyph.UNDO,
    PackGlyph.REDO,
    PackGlyph.CHECK,
    PackGlyph.TRASH,
    PackGlyph.COPY,
    PackGlyph.MOVE,
    PackGlyph.MORE,
    PackGlyph.SEARCH,
    PackGlyph.SEARCH_FILE,
    PackGlyph.FULLSCREEN,
    PackGlyph.FULLSCREEN_EXIT,
    PackGlyph.FORWARD,
    PackGlyph.PREVIOUS,
    PackGlyph.NEXT,
    PackGlyph.STOP,
    PackGlyph.REFRESH,
    PackGlyph.ADD,
    PackGlyph.EDIT,
    PackGlyph.SELECT_ALL,
    PackGlyph.DESELECT_ALL,
    PackGlyph.EXPAND_DOWN,
    PackGlyph.COLLAPSE_UP,
    PackGlyph.EXPAND_COLLAPSE,
    PackGlyph.PASTE,
    PackGlyph.PIN,
    PackGlyph.SHUFFLE,
    PackGlyph.REPEAT,
    PackGlyph.REPEAT_ONE,
    PackGlyph.MUTE,
    PackGlyph.VOLUME,
    PackGlyph.SORT,
    PackGlyph.GRID,
    PackGlyph.LIST,
    PackGlyph.DOWNLOAD,
    PackGlyph.UPLOAD,
    PackGlyph.EXPORT,
    PackGlyph.IMPORT,
    PackGlyph.DUPLICATE,
    PackGlyph.SLIDERS,
    PackGlyph.SELECTED,
    PackGlyph.UNSELECTED,
    PackGlyph.SEND,
    PackGlyph.RECEIVE,
    PackGlyph.ARROW_LEFT,
    PackGlyph.ARROW_RIGHT,
    PackGlyph.BROWSER_BACK,
    PackGlyph.MENU,
    PackGlyph.VISIBILITY,
    PackGlyph.COMPRESS,
    PackGlyph.FILE_ADD,
    PackGlyph.FOLDER_ADD,
    PackGlyph.FOLDER_UPLOAD,
    PackGlyph.UNFAVORITE -> true
    else -> false
}

private fun canonicalName(glyph: PackGlyph): String = if (glyph == PackGlyph.SAVE) "workbench" else glyph.name.lowercase()

@Composable
private fun designDrawable(pack: InterfaceIconPack, glyph: PackGlyph): Int {
    if (pack == InterfaceIconPack.SOLID) return 0
    val context = androidx.compose.ui.platform.LocalContext.current
    val key = canonicalName(glyph)
    return androidx.compose.runtime.remember(pack, key, context) {
        context.resources.getIdentifier("cp_${pack.name.lowercase()}_$key", "drawable", context.packageName)
    }
}

/** Solid has legacy artwork for the core glyphs only; the rest reuse the matching simple-pack shape, tinted as solid. */
@Composable
private fun solidFallbackDrawable(glyph: PackGlyph): Int {
    val context = androidx.compose.ui.platform.LocalContext.current
    val key = canonicalName(glyph)
    return androidx.compose.runtime.remember(key, context) {
        context.resources.getIdentifier("cp_simple_$key", "drawable", context.packageName)
    }
}

/** Neutral dark tints keep the pack artwork; light or coloured tints keep their meaning. */
private fun keepsPackColours(tint: Color): Boolean {
    val max = maxOf(tint.red, tint.green, tint.blue)
    val min = minOf(tint.red, tint.green, tint.blue)
    val luminance = 0.2126f * tint.red + 0.7152f * tint.green + 0.0722f * tint.blue
    return luminance < .5f && max - min < .25f
}

/**
 * Distinct NasFinder brand mark following the approved Jarvis 'N' mark capsule.
 */
@Composable
fun NasFinderBrandMark(modifier: Modifier = Modifier) {
    Box(
        modifier = IconFloor.then(modifier)
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(LocalThemePalette.current.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "N",
            color = LocalThemePalette.current.onAccent,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            lineHeight = 15.sp,
        )
    }
}

/**
 * Draws one glyph of the selected interface icon pack. Functional action glyphs (play, back, check, etc.)
 * render as clean, flat single-colour vectors from SIMPLE or Material with explicit tint contrast (including
 * pure white on video players), while branded service and destination icons preserve the pack's artwork.
 */
@Composable
fun PackIcon(
    glyph: PackGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    pack: InterfaceIconPack = LocalInterfaceIconPack.current,
) {
    val isAction = isActionGlyph(glyph)
    val effectivePack = if (isAction) InterfaceIconPack.SIMPLE else pack
    val completePackDrawable = designDrawable(effectivePack, glyph)
    val semanticsModifier = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    // Caller modifier comes first so explicit caller sizes take precedence over the 24dp default; un-sized callers default to 24dp.
    val sized = modifier.then(Modifier.size(24.dp)).then(semanticsModifier)
    if (glyph == PackGlyph.BRAND && completePackDrawable == 0) {
        NasFinderBrandMark(sized)
        return
    }
    val effective = if (tint != Color.Unspecified) {
        tint
    } else {
        val content = LocalContentColor.current
        if (content != Color.Unspecified) content
        else if (LocalThemePalette.current.isDark) Color.White else Color.Black
    }
    val legacyGlyph = when (glyph) {
        PackGlyph.BRAND -> PackGlyph.HOME
        PackGlyph.CONNECTIONS -> PackGlyph.TRANSFER
        PackGlyph.WORKBENCH -> PackGlyph.SAVE
        PackGlyph.DEVICE_FILES -> PackGlyph.STORAGE
        PackGlyph.COLLECTION -> PackGlyph.VIDEO
        PackGlyph.PHOTO_SELECTION, PackGlyph.SUPER_THUMBNAIL, PackGlyph.THUMBNAIL_STORAGE, PackGlyph.THUMBNAIL_CACHE -> PackGlyph.PHOTOS
        PackGlyph.FOLDER -> PackGlyph.PHONEHARD
        else -> glyph
    }
    val legacyDrawable = if (completePackDrawable != 0) 0 else packDrawable(effectivePack, legacyGlyph)
    val solidFallback = if (completePackDrawable == 0 && legacyDrawable == 0) solidFallbackDrawable(glyph) else 0
    val resId = if (completePackDrawable != 0) completePackDrawable else if (legacyDrawable != 0) legacyDrawable else solidFallback
    if (resId == 0) return
    val painter = painterResource(resId)
    val bitmapPack = !isAction && (pack == InterfaceIconPack.DIMENSION || pack == InterfaceIconPack.REALISTIC)
    val keep = bitmapPack || (solidFallback == 0 && !isAction && pack != InterfaceIconPack.SIMPLE && !LocalThemePalette.current.isDark && keepsPackColours(effective))
    val finalSized = sized.then(if (bitmapPack && tint != Color.Unspecified) Modifier.alpha(tint.alpha) else Modifier)
    if (keep) Image(painter, null, finalSized, contentScale = ContentScale.Fit)
    else Image(painter, null, finalSized, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(effective))
}

/** Same-package shadow of material3 Icon so every existing call site follows the interface icon pack. */
@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    design: PackGlyph? = null,
) {
    val fullPack = designDrawable(LocalInterfaceIconPack.current, PackGlyph.HOME) != 0
    val glyph = if (fullPack) design ?: designGlyphs[imageVector.name] ?: materialGlyphs[imageVector.name]
                else materialGlyphs[imageVector.name]?.takeIf { tint == Color.Unspecified || tint.alpha >= .9f }
    if (glyph != null && (fullPack || (glyph != PackGlyph.POCKET && glyph != PackGlyph.PRIVACY))) {
        PackIcon(glyph, contentDescription, modifier, tint)
    } else {
        MaterialIcon(imageVector, contentDescription, modifier.then(Modifier.size(24.dp)), if (tint == Color.Unspecified) LocalContentColor.current else tint)
    }
}

/** The same unmodified Google Photos asset as Apple; no platform-only card or inset. */
@Composable
fun GooglePhotosMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.google_photos_mark), null, IconFloor.then(modifier).then(Modifier.size(24.dp)), contentScale = ContentScale.Fit)
}
