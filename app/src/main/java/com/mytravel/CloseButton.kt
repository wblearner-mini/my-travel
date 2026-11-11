package com.mytravel

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** The close control on every page and card: a circle with a cross (Material "highlight_off"). */
@Composable
fun CloseButton(onClick: () -> Unit, tint: Color = Color(0xFF5F6368)) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_close_circle), contentDescription = "关闭", tint = tint, modifier = Modifier.size(28.dp))
    }
}
