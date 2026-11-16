package `in`.aniscroll.tv.navigation

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.aniscroll.tv.core.designsystem.TvColors

import androidx.compose.runtime.saveable.rememberSaveable

enum class TvDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    SEARCH("search", "Search", Icons.Filled.Search, Icons.Outlined.Search),
    DISCOVER("discover", "Discover", Icons.Filled.Explore, Icons.Outlined.Explore),
    SCHEDULE("schedule", "Schedule", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    MY_LIST("my_list", "My List", Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder),
    CONTINUE_WATCHING("continue_watching", "Continue Watching", Icons.Filled.History, Icons.Outlined.History),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@Composable
fun TvNavigationSidebar(
    selectedDestination: TvDestination,
    onSelectDestination: (TvDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    var isSidebarExpanded by rememberSaveable { mutableStateOf(false) }

    val sidebarWidth by animateDpAsState(
        targetValue = if (isSidebarExpanded) 188.dp else 56.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sidebar_width"
    )

    Column(
        modifier = modifier
            .width(sidebarWidth)
            .fillMaxHeight()
            .background(TvColors.MainBackground)
            .border(width = 0.5.dp, color = TvColors.Divider)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Brand Header / Toggle Button
        val headerInteractionSource = remember { MutableInteractionSource() }
        val isHeaderFocused by headerInteractionSource.collectIsFocusedAsState()
        val headerBg by animateColorAsState(
            targetValue = if (isHeaderFocused) TvColors.FocusBackground else Color.Transparent,
            label = "header_bg"
        )
        val headerFg = if (isHeaderFocused) TvColors.FocusText else TvColors.PrimaryText

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(headerBg)
                .border(
                    width = if (isHeaderFocused) 1.5.dp else 0.dp,
                    color = if (isHeaderFocused) TvColors.FocusOutline else Color.Transparent,
                    shape = RoundedCornerShape(4.dp)
                )
                .onKeyEvent { event ->
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                        (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                    ) {
                        isSidebarExpanded = !isSidebarExpanded
                        true
                    } else {
                        false
                    }
                }
                .focusable(interactionSource = headerInteractionSource)
                .clickable(
                    interactionSource = headerInteractionSource,
                    indication = null,
                    onClick = { isSidebarExpanded = !isSidebarExpanded }
                )
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .testTag("sidebar_brand_header"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(TvColors.ElevatedSurface)
                    .border(0.5.dp, TvColors.Divider, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                `in`.aniscroll.tv.core.designsystem.AniScrollBrandLogo(
                    size = 18.dp,
                    contentDescription = "AniScroll Logo"
                )
            }
            AnimatedVisibility(
                visible = isSidebarExpanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "ANISCROLL TV",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = headerFg,
                        maxLines = 1
                    )
                }
            }
        }

        // Main Nav Items
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TvDestination.entries.forEach { dest ->
                TvSidebarItem(
                    destination = dest,
                    isSelected = selectedDestination == dest,
                    isExpanded = isSidebarExpanded,
                    onClick = { onSelectDestination(dest) }
                )
            }
        }

        // Bottom remote hint
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (isSidebarExpanded) {
                Text(
                    text = "D-Pad Remote Ready",
                    style = MaterialTheme.typography.labelSmall,
                    color = TvColors.MutedText,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TvSidebarItem(
    destination: TvDestination,
    isSelected: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bgColor by animateColorAsState(
        targetValue = when {
            isFocused -> TvColors.FocusBackground
            isSelected -> TvColors.ElevatedSurface
            else -> Color.Transparent
        },
        label = "nav_item_bg"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            isFocused -> TvColors.FocusText
            isSelected -> TvColors.PrimaryText
            else -> TvColors.SecondaryText
        },
        label = "nav_item_fg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(
                width = if (isFocused) 2.dp else if (isSelected) 1.dp else 0.dp,
                color = if (isFocused) TvColors.FocusOutline else if (isSelected) TvColors.Divider else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("nav_${destination.route}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isSelected || isFocused) destination.selectedIcon else destination.unselectedIcon,
            contentDescription = destination.label,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
