package dev.nytweetdeck.android.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.MainMenuItemId

internal data class MenuItem(
    val id: MainMenuItemId,
    val labelRes: Int,
    val icon: ImageVector,
)

internal fun menuDefinition(id: MainMenuItemId): MenuItem = when (id) {
    MainMenuItemId.COMPOSE -> MenuItem(id, R.string.compose_post, Icons.Default.Create)
    MainMenuItemId.SEARCH -> MenuItem(id, R.string.search, Icons.Default.Search)
    MainMenuItemId.HOME -> MenuItem(id, R.string.home, Icons.Default.Home)
    MainMenuItemId.NOTIFICATIONS -> MenuItem(id, R.string.notifications, Icons.Default.Notifications)
    MainMenuItemId.MESSAGES -> MenuItem(id, R.string.direct_messages, Icons.Default.MailOutline)
    MainMenuItemId.TRENDS -> MenuItem(id, R.string.trends, Icons.AutoMirrored.Filled.TrendingUp)
    MainMenuItemId.FOLLOWING -> MenuItem(id, R.string.following, Icons.Default.People)
    MainMenuItemId.CHAT -> MenuItem(id, R.string.chat, Icons.Default.MailOutline)
    MainMenuItemId.GROK -> MenuItem(id, R.string.grok, Icons.Default.Search)
    MainMenuItemId.PREMIUM -> MenuItem(id, R.string.premium, Icons.Default.AccountCircle)
    MainMenuItemId.PROFILE -> MenuItem(id, R.string.profile, Icons.Default.AccountCircle)
    MainMenuItemId.COMMUNITIES -> MenuItem(id, R.string.communities, Icons.Default.People)
    MainMenuItemId.CREATOR_STUDIO -> MenuItem(id, R.string.creator_studio, Icons.Default.Create)
    MainMenuItemId.BUSINESS -> MenuItem(id, R.string.business, Icons.Default.Home)
    MainMenuItemId.ADS -> MenuItem(id, R.string.ads, Icons.AutoMirrored.Filled.TrendingUp)
    MainMenuItemId.SPACES -> MenuItem(id, R.string.spaces, Icons.Default.Notifications)
}

internal val externalMenuUrls = mapOf(
    MainMenuItemId.CHAT to "https://x.com/i/chat",
    MainMenuItemId.GROK to "https://x.com/i/grok",
    MainMenuItemId.PREMIUM to "https://x.com/i/premium_sign_up",
    MainMenuItemId.COMMUNITIES to "https://x.com/i/communities",
    MainMenuItemId.CREATOR_STUDIO to "https://business.x.com/en/products/media-studio",
    MainMenuItemId.BUSINESS to "https://business.x.com",
    MainMenuItemId.ADS to "https://ads.x.com",
    MainMenuItemId.SPACES to "https://x.com/i/spaces/start",
)

@Composable
internal fun MainMenu(
    menuItems: List<MainMenuItemId>,
    onActivate: (MainMenuItemId) -> Unit,
    onEditMenu: () -> Unit,
    onAccounts: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(60.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val shortViewport = maxHeight < 560.dp
            val scrollableMenu = shortViewport || menuItems.size > 10
            val scrollState = rememberScrollState()
            Column(
                modifier = (if (scrollableMenu) {
                    Modifier.fillMaxWidth().verticalScroll(scrollState)
                } else {
                    Modifier.fillMaxSize()
                })
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                menuItems.forEach { id ->
                    val item = menuDefinition(id)
                    MenuIcon(
                        icon = item.icon,
                        description = stringResource(item.labelRes),
                        selected = false,
                        onClick = { onActivate(item.id) },
                        tag = "menu-${item.id.name.lowercase()}",
                        emphasized = item.id == MainMenuItemId.COMPOSE,
                    )
                }
                MenuIcon(
                    icon = Icons.Default.Add,
                    description = stringResource(R.string.edit_menu),
                    selected = false,
                    onClick = onEditMenu,
                    tag = "edit-menu",
                )
                if (scrollableMenu) Spacer(Modifier.height(8.dp)) else Spacer(Modifier.weight(1f))
                MenuIcon(
                    icon = Icons.Default.AccountCircle,
                    description = stringResource(R.string.accounts),
                    selected = false,
                    onClick = onAccounts,
                    tag = "accounts",
                )
                MenuIcon(
                    icon = Icons.Default.Settings,
                    description = stringResource(R.string.settings),
                    selected = false,
                    onClick = onSettings,
                    tag = "settings",
                )
            }
        }
    }
}

@Composable
private fun MenuIcon(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    emphasized: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .testTag(tag),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = when {
                emphasized -> MaterialTheme.colorScheme.primary
                selected -> MaterialTheme.colorScheme.primaryContainer
                else -> Color.Transparent
            },
            contentColor = when {
                emphasized -> MaterialTheme.colorScheme.onPrimary
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Icon(icon, description)
    }
}
