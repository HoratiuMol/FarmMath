package com.farmmathbuilder.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.farmmathbuilder.app.data.entity.PlayerEntity
import com.farmmathbuilder.app.domain.DecorationType
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

        // Math stars — earned only by solving exercises (casual + Challenge),
        // deliberately its own pill so a child sees matemáticas pay off in
        // something visibly separate from the farm economy above.
        HudChip(emoji = "⭐", value = "${player?.mathStars ?: 0}", accent = WheatGold)

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

/**
 * Vertical column of shop icons, flush to the right edge of the screen
 * (founder spec: "pegados al borde derecho", vertical, icon-only — no
 * label/count on the chip itself, unlike [HudChip]). The cow icon buys an
 * animal at [com.farmmathbuilder.app.data.repository.FarmRepository.COW_COST]
 * wheat, greyed out (same disabled language as [ExpandMapButton]) once
 * unaffordable or the herd is at its cap. Right below it (founder request
 * 2026-08-18: "un segundo icono, debajo del icono de la vaca") is the map
 * decorations ("accidentes geográficos") shop — opens [DecorationPickerDialog]
 * to place a decoration (currently only a river) outside the fenced play area.
 */
@Composable
fun AnimalShopColumn(
    canBuyCow: Boolean,
    onBuyCow: () -> Unit,
    onOpenDecorationShop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(top = 10.dp, bottom = 10.dp, end = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FloatingActionButton(
            onClick = onBuyCow,
            containerColor = if (canBuyCow) CardCream.copy(alpha = 0.92f) else Color(0xFFBDBDBD),
            contentColor = SoilBrown,
            shape = CircleShape,
            modifier = Modifier.size(48.dp).shadow(2.dp, CircleShape)
        ) {
            Text("🐄", style = MaterialTheme.typography.titleLarge)
        }
        FloatingActionButton(
            onClick = onOpenDecorationShop,
            containerColor = CardCream.copy(alpha = 0.92f),
            contentColor = SoilBrown,
            shape = CircleShape,
            modifier = Modifier.size(48.dp).shadow(2.dp, CircleShape)
        ) {
            Icon(Icons.Filled.Terrain, contentDescription = "Map decorations")
        }
    }
}

/** One selectable row in [DecorationPickerDialog] — icon + label, same "flat
 * card" language as the rest of the shop UI. */
@Composable
private fun DecorationOptionRow(
    emoji: String,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(CardCream, RoundedCornerShape(12.dp))
            .border(1.5.dp, SoilBrown.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(emoji, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = SoilBrown)
    }
}

/** Picker for the map decorations shop (founder request 2026-08-18): lists
 * every placeable [DecorationType] — river, and (2026-08-18) a bear's cave —
 * and starts placement mode on tap (see FarmViewModel.startPlacingDecoration). */
@Composable
fun DecorationPickerDialog(
    onDismiss: () -> Unit,
    onPick: (DecorationType) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map decorations") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pick a decoration to place outside your fields.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoilBrown.copy(alpha = 0.8f)
                )
                DecorationOptionRow(emoji = "🏞️", label = "River") { onPick(DecorationType.RIVER) }
                DecorationOptionRow(emoji = "🐻", label = "Bear cave") { onPick(DecorationType.CAVE) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Banner shown while placing a decoration: instructs the player and offers a
 * cancel escape hatch (tapping a valid spot outside the fence on the grid
 * itself completes the placement) — same shape as the old move-barn banner. */
@Composable
fun DecorationPlacementBanner(
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
        Text("Tap outside the fence to place it", color = SoilBrown, fontWeight = FontWeight.Bold)
        FloatingActionButton(
            onClick = onCancel,
            containerColor = Color(0xFFBDBDBD),
            contentColor = Color.White,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel decoration placement")
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
