package com.armsone.nasfinder.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
enum class PackGlyph { HOME, NAS, PHOTOS, PHONEHARD, CLOUD, BROWSER, VIDEO, DRAFTS, TRANSFER, STORAGE, SETTINGS, FAVORITES, BACK, CLOSE, ADD, CHECK, SAVE, SHARE, EDIT, PLAY, PAUSE, SORT, GRID, TRASH }

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
    }
}

private val materialGlyphs: Map<String, PackGlyph> = mapOf(
    "Filled.Home" to PackGlyph.HOME,
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
    "Filled.Star" to PackGlyph.FAVORITES,
)

/** Neutral dark tints keep the pack artwork; light or coloured tints keep their meaning. */
private fun keepsPackColours(tint: Color): Boolean {
    val max = maxOf(tint.red, tint.green, tint.blue)
    val min = minOf(tint.red, tint.green, tint.blue)
    val luminance = 0.2126f * tint.red + 0.7152f * tint.green + 0.0722f * tint.blue
    return luminance < .5f && max - min < .25f
}

/**
 * Draws one glyph of the selected interface icon pack. Dimension and realistic packs are baked bitmaps and always render
 * their original artwork (never tinted). Simple and solid packs are line art: in dark themes they are tinted with the
 * requested content colour (theme text by default) so the graphite strokes never vanish; in light themes neutral dark
 * tints keep the original pack colours and coloured tints keep their meaning.
 */
@Composable
fun PackIcon(
    glyph: PackGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    pack: InterfaceIconPack = LocalInterfaceIconPack.current,
) {
    val effective = if (tint == Color.Unspecified) LocalContentColor.current else tint
    val painter = painterResource(packDrawable(pack, glyph))
    val bitmapPack = pack == InterfaceIconPack.DIMENSION || pack == InterfaceIconPack.REALISTIC
    val keep = bitmapPack || (!LocalThemePalette.current.isDark && keepsPackColours(effective))
    val semanticsModifier = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    val sized = modifier.then(Modifier.size(24.dp)).then(semanticsModifier)
    if (keep) Image(painter, null, sized, contentScale = ContentScale.Fit)
    else Image(painter, null, sized, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(effective))
}

/** Same-package shadow of material3 Icon so every existing call site follows the interface icon pack. */
@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    // Dimmed or decorative tints (watermarks, disabled states) keep the plain Material glyph.
    val glyph = materialGlyphs[imageVector.name]?.takeIf { tint == Color.Unspecified || tint.alpha >= .9f }
    if (glyph != null) {
        PackIcon(glyph, contentDescription, modifier, tint)
    } else {
        MaterialIcon(imageVector, contentDescription, modifier, if (tint == Color.Unspecified) LocalContentColor.current else tint)
    }
}

/** Official Google Photos mark (unmodified partner asset) on a white card; independent of the interface icon pack. */
@Composable
fun GooglePhotosMark(modifier: Modifier = Modifier) {
    Box(modifier.then(Modifier.size(24.dp)).clip(RoundedCornerShape(22)).background(Color.White).padding(2.dp)) {
        Image(painterResource(R.drawable.google_photos_mark), null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
    }
}
