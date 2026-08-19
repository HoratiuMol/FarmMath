package com.farmmathbuilder.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.farmmathbuilder.app.data.entity.PlayerEntity
import com.farmmathbuilder.app.data.repository.FarmRepository

/** FR-064/FR-065 (Should): exercises solved today + current streak, plus a few other counters. */
@Composable
fun StatsDialog(player: PlayerEntity?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your stats") },
        text = {
            Column {
                Text("Wheat currency: ${player?.wheatCurrency ?: 0}")
                Text("🥕 Cow feed: ${player?.carrotInventory ?: 0}")
                Text("⭐ Math stars: ${player?.mathStars ?: 0}")
                Text("Fields harvested: ${player?.fieldsCompletedTotal ?: 0}")
                Text("Exercises solved today: ${player?.exercisesSolvedToday ?: 0}")
                Text("Current streak: ${player?.currentStreak ?: 0}")
                Text("Extra fields earned today: ${player?.extraFieldsEarnedToday ?: 0}")
                val target = FarmRepository.DAILY_MISSION_TARGET
                val solvedToday = player?.exercisesSolvedToday ?: 0
                val claimed = player?.dailyMissionClaimed == true
                Text(
                    if (claimed) {
                        "🎯 Daily mission: done! (+${FarmRepository.DAILY_MISSION_STAR_BONUS} ⭐ claimed)"
                    } else {
                        "🎯 Daily mission: ${solvedToday.coerceAtMost(target)}/$target solved"
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
