package com.farmmathbuilder.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.farmmathbuilder.app.data.entity.PlayerEntity
import com.farmmathbuilder.app.ui.theme.BuildingRoof
import com.farmmathbuilder.app.ui.theme.CardCream
import com.farmmathbuilder.app.ui.theme.GrassGreenDark
import com.farmmathbuilder.app.ui.theme.SoilBrown
import com.farmmathbuilder.app.ui.theme.WheatGold

/**
 * A single stat "pill": icon/emoji + bold number, one line, no caption and no
 * per-chip border — sized to its own content instead of stretching to fill a
 * shared card. Redesigned 2026-08-14 (founder: the old two-line, individually
 * bordered chips nested inside an outer bordered card read as a heavy "frame
 * within a frame" that ate too much of the map). A soft shadow (instead of a
 * hard border) gives it just enough lift to read as floating over the map,
 * not a boxed panel sitting on top of it.
 */
@Composable
private fun HudChip(
    emoji: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = SoilBrown
) {
    Row(
        modifier = modifier
            .shadow(2.dp, RoundedCornerShape(50))
            .background(CardCream.copy(alpha = 0.92f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(emoji, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = accent
        )
    }
}

/** Loose row of floating pills (see [HudChip]) — deliberately not wrapped in
 * any shared card/border of its own, so the HUD's total footprint is just the
 * sum of its compact pills rather than a large fixed panel, minimizing how
 * much of the map underneath it gets covered. */
@Composable
fun TopHud(player: PlayerEntity?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HudChip(emoji = "🌾", value = "${player?.wheatCurrency ?: 0}", accent = SoilBrown)

        // Cow feed stockpile — only carrot's use, so surfaced right next to
        // wheat currency (the two crops' rewards are now visibly distinct:
        // wheat sells, carrot feeds).
        HudChip(emoji = "🥕", value = "${player?.carrotInventory ?: 0}", accent = BuildingRoof)

        val free = player?.freeFieldsUsedToday ?: 0
        HudChip(emoji = "🌱", value = "$free/5", accent = GrassGreenDark)

        val extraEarned = player?.extraFieldsEarnedToday ?: 0
        val extraUsed = player?.extraFieldsUsedToday ?: 0
        val extraRemaining = (extraEarned - extraUsed).coerceAtLeast(0)
        if (extraEarned > 0) {
            HudChip(emoji = "✨", value = "+$extraRemaining", accent = SoilBrown)
        }
    }
}

@Composable
fun FabColumn(
    onChallengeClick: () -> Unit,
    onMathClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.End
    ) {
        // Dedicated "10 in a row" Challenge entry point — deliberately its own
        // button, placed above the casual single-exercise FAB (per founder
        // request), not appended as a continuation of that flow.
        FloatingActionButton(
            onClick = onChallengeClick,
            containerColor = BuildingRoof,
            contentColor = Color.White,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = "10-exercise challenge")
        }
        FloatingActionButton(
            onClick = onMathClick,
            containerColor = WheatGold,
            contentColor = SoilBrown,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(Icons.Filled.Calculate, contentDescription = "Math exercise")
        }
        FloatingActionButton(
            onClick = onStatsClick,
            containerColor = GrassGreenDark,
            contentColor = Color.White,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(Icons.Filled.BarChart, contentDescription = "Stats")
        }
        FloatingActionButton(
            onClick = onSettingsClick,
            containerColor = SoilBrown,
            contentColor = Color.White,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}

/** Harvests every currently-mature cell at once; only shown when there's at least one ready. */
@Composable
fun HarvestAllButton(
    matureCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = WheatGold,
        contentColor = SoilBrown,
        icon = { Icon(Icons.Filled.Agriculture, contentDescription = "Harvest all") },
        text = { Text("Harvest all ($matureCount)", fontWeight = FontWeight.Bold) }
    )
}

/** Enters "move barn" mode (only offered while no wheat is growing/mature anywhere —
 * disabled/greyed otherwise, same visual language as ExpandMapButton's afford check). */
@Composable
fun MoveBarnButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = if (enabled) SoilBrown else Color(0xFFBDBDBD),
        contentColor = Color.White,
        icon = { Icon(Icons.Filled.Home, contentDescription = "Move barn") },
        text = { Text("Move barn", fontWeight = FontWeight.Bold) }
    )
}

/** Banner shown while in "move barn" mode: instructs the player and offers a cancel
 * escape hatch (tapping a valid tile on the grid itself completes the move). */
@Composable
fun MoveBarnBanner(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(CardCream, RoundedCornerShape(16.dp))
            .border(2.dp, SoilBrown, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Tap a highlighted tile to place the barn", color = SoilBrown, fontWeight = FontWeight.Bold)
        FloatingActionButton(
            onClick = onCancel,
            containerColor = Color(0xFFBDBDBD),
            contentColor = Color.White,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel move barn")
        }
    }
}

/** Map expansion (founder request): grows the grid one ring at a cost of wheat currency. */
@Composable
fun ExpandMapButton(
    cost: Int,
    canAfford: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = if (canAfford) GrassGreenDark else Color(0xFFBDBDBD),
        contentColor = Color.White,
        icon = { Icon(Icons.Filled.OpenInFull, contentDescription = "Expand map") },
        text = { Text("Expand map ($cost 🌾)", fontWeight = FontWeight.Bold) }
    )
}
