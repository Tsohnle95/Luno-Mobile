package com.luno.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luno.mobile.ui.theme.PlayerPurple
import com.luno.mobile.ui.theme.PrimaryText

@Composable
internal fun LunoProfileButton(onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(PlayerPurple)
        .semantics { contentDescription = "Profile and settings" }
        .clickable(onClickLabel = "Open settings", onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text("L", color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
