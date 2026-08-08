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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Calculate
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
import com.farmmathbuilder.app.ui.theme.CardCream
import com.farmmathbuilder.app.ui.theme.GrassGreenDark
import com.farmmathbuilder.app.ui.theme.SoilBrown
import com.farmmathbuilder.app.ui.theme.WheatGold

/** A single stat "chip" inside the HUD panel: icon/emoji + bold number + small caption. */
@Composable
private fun HudChip(
    emoji: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    accent: Color = SoilBrown
) {
    Row(
        modifier = modifier
            .background(Color(0xFFFFFFFF).copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .border(1.5.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
        Column {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = SoilBrown)
        }
    }
}

@Composable
fun TopHud(player: PlayerEntity?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(12.dp)
            .background(CardCream, RoundedCornerShape(20.dp))
            .border(3.dp, SoilBrown, RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HudChip(
            emoji = "🌾",
            value = "${player?.wheatCurrency ?: 0}",
            caption = "Wheat",
            accent = SoilBrown
        )

        val free = player?.freeFieldsUsedToday ?: 0
        HudChip(
            emoji = "🌱",
            value = "$free/5",
            caption = "Free fields",
            accent = GrassGreenDark
        )

        val extraEarned = player?.extraFieldsEarnedToday ?: 0
        val extraUsed = player?.extraFieldsUsedToday ?: 0
        val extraRemaining = (extraEarned - extraUsed).coerceAtLeast(0)
        if (extraEarned > 0) {
            HudChip(
                emoji = "✨",
                value = "+$extraRemaining",
                caption = "Extra today",
                accent = SoilBrown
            )
        }
    }
}

@Composable
fun FabColumn(
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
