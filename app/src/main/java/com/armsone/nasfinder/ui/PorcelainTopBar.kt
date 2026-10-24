package com.armsone.nasfinder.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.armsone.nasfinder.ui.theme.HomeType
import com.armsone.nasfinder.ui.theme.PorcelainType

/**
 * The one sub-page top bar (iOS inline navigation title): centred 17/600 title on the page colour, optional one-line 12 caption,
 * one back arrow with a 48 touch target, trailing actions. Screens that need a different back gesture (long-press folder
 * browsers, the full-screen preview) keep their own navigation icon but use the same title and height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PorcelainTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backLabel: String = "뒤로",
    subtitle: String? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(title, style = PorcelainType.Section, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = HomeType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, backLabel, tint = MaterialTheme.colorScheme.onBackground)
                }
            }
        },
        actions = actions,
        expandedHeight = 52.dp,
        windowInsets = windowInsets,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
    )
}
