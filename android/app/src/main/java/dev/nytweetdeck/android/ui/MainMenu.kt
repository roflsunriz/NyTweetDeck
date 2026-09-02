package dev.nytweetdeck.android.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nytweetdeck.android.R
import dev.nytweetdeck.android.model.MainMenuItemId
import dev.nytweetdeck.android.model.NavigationPosition
import kotlin.math.max

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

internal fun navigationRevealDelta(
    position: NavigationPosition,
    layoutDirection: LayoutDirection,
    horizontalDrag: Float,
    verticalDrag: Float,
): Float = when (position) {
    NavigationPosition.LEFT -> if (layoutDirection == LayoutDirection.Ltr) {
        max(horizontalDrag, 0f)
    } else {
        max(-horizontalDrag, 0f)
    }
    NavigationPosition.BOTTOM -> max(-verticalDrag, 0f)
}

@Composable
internal fun MainMenuRevealEdge(
    position: NavigationPosition,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    val edgeModifier = when (position) {
        NavigationPosition.LEFT -> Modifier
            .fillMaxHeight()
            .width(28.dp)
            .pointerInput(onReveal, layoutDirection) {
                val revealThreshold = 48.dp.toPx()
                var inwardDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { inwardDrag = 0f },
                    onDragCancel = { inwardDrag = 0f },
                    onDragEnd = {
                        if (inwardDrag >= revealThreshold) onReveal()
                        inwardDrag = 0f
                    },
                ) { change, dragAmount ->
                    inwardDrag += navigationRevealDelta(
                        position = position,
                        layoutDirection = layoutDirection,
                        horizontalDrag = dragAmount,
                        verticalDrag = 0f,
                    )
                    change.consume()
                }
            }
        NavigationPosition.BOTTOM -> Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(onReveal) {
                val revealThreshold = 48.dp.toPx()
                var upwardDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = { upwardDrag = 0f },
                    onDragCancel = { upwardDrag = 0f },
                    onDragEnd = {
                        if (upwardDrag >= revealThreshold) onReveal()
                        upwardDrag = 0f
                    },
                ) { change, dragAmount ->
                    upwardDrag += navigationRevealDelta(
                        position = position,
                        layoutDirection = layoutDirection,
                        horizontalDrag = 0f,
                        verticalDrag = dragAmount,
                    )
                    change.consume()
                }
            }
    }
    Box(modifier = modifier.then(edgeModifier).testTag("navigation-swipe-edge"))
}

@Composable
internal fun MainMenu(
    position: NavigationPosition,
    menuItems: List<MainMenuItemId>,
    onActivate: (MainMenuItemId) -> Unit,
    onEditMenu: () -> Unit,
    onAccounts: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val menuModifier = when (position) {
        NavigationPosition.LEFT -> modifier.fillMaxHeight().width(60.dp)
        NavigationPosition.BOTTOM -> modifier.fillMaxWidth().height(60.dp)
    }
    Surface(
        modifier = menuModifier.testTag("main-menu-${position.name.lowercase()}"),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        when (position) {
            NavigationPosition.LEFT -> VerticalMainMenu(
                menuItems = menuItems,
                onActivate = onActivate,
                onEditMenu = onEditMenu,
                onAccounts = onAccounts,
                onSettings = onSettings,
            )
            NavigationPosition.BOTTOM -> BottomMainMenu(
                menuItems = menuItems,
                onActivate = onActivate,
                onEditMenu = onEditMenu,
                onAccounts = onAccounts,
                onSettings = onSettings,
            )
        }
    }
}

@Composable
private fun VerticalMainMenu(
    menuItems: List<MainMenuItemId>,
    onActivate: (MainMenuItemId) -> Unit,
    onEditMenu: () -> Unit,
    onAccounts: () -> Unit,
    onSettings: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scrollableMenu = maxHeight < 560.dp || menuItems.size > 10
        val scrollState = rememberScrollState()
        Column(
            modifier = (if (scrollableMenu) {
                Modifier.fillMaxWidth().verticalScroll(scrollState)
            } else {
                Modifier.fillMaxSize()
            }).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MainMenuItems(menuItems, onActivate)
            EditMenuIcon(onEditMenu)
            if (scrollableMenu) Spacer(Modifier.height(8.dp)) else Spacer(Modifier.weight(1f))
            AccountAndSettingsIcons(onAccounts, onSettings)
        }
    }
}

@Composable
private fun BottomMainMenu(
    menuItems: List<MainMenuItemId>,
    onActivate: (MainMenuItemId) -> Unit,
    onEditMenu: () -> Unit,
    onAccounts: () -> Unit,
    onSettings: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainMenuItems(menuItems, onActivate)
            EditMenuIcon(onEditMenu)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountAndSettingsIcons(onAccounts, onSettings)
        }
    }
}

@Composable
private fun MainMenuItems(
    menuItems: List<MainMenuItemId>,
    onActivate: (MainMenuItemId) -> Unit,
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
}

@Composable
private fun EditMenuIcon(onEditMenu: () -> Unit) {
    MenuIcon(
        icon = Icons.Default.Add,
        description = stringResource(R.string.edit_menu),
        selected = false,
        onClick = onEditMenu,
        tag = "edit-menu",
    )
}

@Composable
private fun AccountAndSettingsIcons(
    onAccounts: () -> Unit,
    onSettings: () -> Unit,
) {
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
