package com.armsone.nasfinder.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PhoneHardMark(size: Dp, modifier: Modifier = Modifier) {
    val corner = RoundedCornerShape(size * .22f)
    Box(
        modifier = modifier
            .size(size)
            .shadow(size * .08f, corner)
            .clip(corner)
            .background(
                Brush.linearGradient(listOf(Color(0xFF393C3E), Color(0xFF0E1011))),
            )
            .border(
                size * .045f,
                Brush.linearGradient(
                    listOf(Color.White, Color(0xFF5C6165), Color(0xFFB9BDC0)),
                ),
                corner,
            ),
    ) {
        Column(
            modifier = Modifier.padding(size * .105f),
            verticalArrangement = Arrangement.spacedBy(size * .015f),
        ) {
            val phoneSize = size.value * .225f
            Text(
                text = "Phone",
                color = Color.White,
                fontSize = phoneSize.sp,
                lineHeight = phoneSize.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(size * .36f)
                    .clip(RoundedCornerShape(size * .075f))
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFFFF8F14), Color(0xFFF45100))),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val hardSize = size.value * .285f
                Text(
                    text = "Hard",
                    color = Color(0xFF131516),
                    fontSize = hardSize.sp,
                    lineHeight = hardSize.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((size.value * .018f).coerceAtLeast(.5f).dp)
                        .padding(horizontal = size * .045f)
                        .background(Color.White.copy(alpha = .35f)),
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(size * .075f)
                .size(size * .09f)
                .background(
                    Brush.linearGradient(listOf(Color.White, Color(0xFF585D60))),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = size * .055f, height = (size.value * .012f).coerceAtLeast(.5f).dp)
                    .background(Color(0xFF424649)),
            )
        }
    }
}
