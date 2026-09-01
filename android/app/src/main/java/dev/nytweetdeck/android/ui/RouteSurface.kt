package dev.nytweetdeck.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
internal fun FullScreenRouteSurface(
    tag: String,
    onDismiss: () -> Unit,
    color: Color = MaterialTheme.colorScheme.background,
    content: @Composable BoxScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Surface(modifier = Modifier.fillMaxSize().testTag(tag), color = color) {
        Box(modifier = Modifier.fillMaxSize(), content = content)
    }
}

@Composable
internal fun FullScreenRoutePopup(
    tag: String,
    onDismiss: () -> Unit,
    color: Color = MaterialTheme.colorScheme.background,
    content: @Composable BoxScope.() -> Unit,
) {
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        FullScreenRouteSurface(tag, onDismiss, color, content)
    }
}
