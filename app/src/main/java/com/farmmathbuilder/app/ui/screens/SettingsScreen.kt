package com.farmmathbuilder.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.farmmathbuilder.app.data.entity.PlayerEntity
import com.farmmathbuilder.app.data.entity.SettingsEntity
import com.farmmathbuilder.app.domain.AgeBand
import com.farmmathbuilder.app.domain.TextSizeOption
import com.farmmathbuilder.app.ui.theme.CardCream
import com.farmmathbuilder.app.ui.theme.GrassGreenDark
import com.farmmathbuilder.app.ui.theme.SoilBrown
import com.farmmathbuilder.app.ui.theme.WheatGold

/** A visually-separated settings section: icon + label header above a card of rows. */
@Composable
private fun SettingsSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = GrassGreenDark)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = CardCream),
            border = BorderStroke(2.dp, SoilBrown.copy(alpha = 0.35f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                content()
            }
        }
    }
}

/** Label (+ optional description) on the left, arbitrary control on the right. */
@Composable
private fun SettingsRow(
    label: String,
    description: String? = null,
    control: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
        control()
    }
}

/** Simple segmented control replacing the old "✓ suffix" hack for text-size selection. */
@Composable
private fun TextSizeSegmentedControl(
    selected: TextSizeOption,
    onSelect: (TextSizeOption) -> Unit
) {
    Row(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .border(1.5.dp, SoilBrown.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextSizeOption.entries.forEach { option ->
            val isSelected = option == selected
            Row(
                modifier = Modifier
                    .background(
                        if (isSelected) GrassGreenDark else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 40.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Color.White else SoilBrown
                )
            }
        }
    }
}

/** Simple segmented control for the age band / math difficulty selector, matching TextSizeSegmentedControl. */
@Composable
private fun AgeBandSegmentedControl(
    selected: AgeBand,
    onSelect: (AgeBand) -> Unit
) {
    Row(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .border(1.5.dp, SoilBrown.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AgeBand.entries.forEach { option ->
            val isSelected = option == selected
            Row(
                modifier = Modifier
                    .background(
                        if (isSelected) GrassGreenDark else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 40.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Color.White else SoilBrown
                )
            }
        }
    }
}

/** FR-090-095: editable age/difficulty selector, volume, mute, text size, high contrast, notifications. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    player: PlayerEntity?,
    settings: SettingsEntity?,
    onBack: () -> Unit,
    onMusicVolumeChange: (Float) -> Unit,
    onSfxVolumeChange: (Float) -> Unit,
    onMuteToggle: (Boolean) -> Unit,
    onTextSizeChange: (TextSizeOption) -> Unit,
    onHighContrastToggle: (Boolean) -> Unit,
    onNotificationsToggle: (Boolean) -> Unit,
    onAgeBandChange: (AgeBand) -> Unit
) {
    val s = settings ?: SettingsEntity()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GrassGreenDark,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SettingsSection(icon = Icons.Filled.Person, title = "Player") {
                SettingsRow(label = "Age band") {}
                AgeBandSegmentedControl(
                    selected = player?.ageBand ?: AgeBand.AGE_6_9,
                    onSelect = onAgeBandChange
                )
                Text(
                    "This also sets how hard your math problems are.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            SettingsSection(icon = Icons.Filled.VolumeUp, title = "Sound") {
                SettingsRow(label = "Music volume") {}
                Slider(
                    value = s.musicVolume,
                    onValueChange = onMusicVolumeChange,
                    colors = SliderDefaults.colors(
                        thumbColor = GrassGreenDark,
                        activeTrackColor = GrassGreenDark,
                        inactiveTrackColor = SoilBrown.copy(alpha = 0.25f)
                    )
                )

                SettingsRow(label = "Sound effects volume") {}
                Slider(
                    value = s.sfxVolume,
                    onValueChange = onSfxVolumeChange,
                    colors = SliderDefaults.colors(
                        thumbColor = WheatGold,
                        activeTrackColor = WheatGold,
                        inactiveTrackColor = SoilBrown.copy(alpha = 0.25f)
                    )
                )

                SettingsRow(label = "Mute all sound") {
                    Switch(
                        checked = s.muted,
                        onCheckedChange = onMuteToggle,
                        colors = SwitchDefaults.colors(checkedTrackColor = GrassGreenDark)
                    )
                }
            }

            SettingsSection(icon = Icons.Filled.TextFields, title = "Display") {
                SettingsRow(label = "Text size") {}
                TextSizeSegmentedControl(selected = s.textSize, onSelect = onTextSizeChange)

                SettingsRow(
                    label = "High contrast mode",
                    description = "Boosts contrast for easier reading."
                ) {
                    Switch(
                        checked = s.highContrast,
                        onCheckedChange = onHighContrastToggle,
                        colors = SwitchDefaults.colors(checkedTrackColor = GrassGreenDark)
                    )
                }
            }

            SettingsSection(icon = Icons.Filled.NotificationsOff, title = "Notifications") {
                SettingsRow(
                    label = "No notifications",
                    description = "Turn off reminders and alerts."
                ) {
                    Switch(
                        checked = !s.notificationsEnabled,
                        onCheckedChange = { onNotificationsToggle(!it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = GrassGreenDark)
                    )
                }
            }
        }
    }
}
