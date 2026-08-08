package com.farmmathbuilder.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.farmmathbuilder.app.domain.AgeBand
import com.farmmathbuilder.app.viewmodel.TutorialStep

/**
 * First-run age-band selection (exactly two options — R-6). No preceding welcome
 * screen: a fresh install goes straight here, and any player with a saved game
 * (onboardingCompleted) never sees this screen again — see FarmViewModel's
 * `needsOnboarding` check.
 */
@Composable
fun OnboardingScreen(
    step: TutorialStep,
    onAgeBandSelected: (AgeBand) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (step) {
            TutorialStep.AGE_SELECT -> {
                Text(
                    "How old are you?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "You can change this anytime in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                AgeBandCard("6-9 years old", "Easier math problems") { onAgeBandSelected(AgeBand.AGE_6_9) }
                Spacer(Modifier.height(16.dp))
                AgeBandCard("10-12 years old", "Bigger numbers") { onAgeBandSelected(AgeBand.AGE_10_12) }
            }
            else -> {}
        }
    }
}

@Composable
private fun AgeBandCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick) { Text("Choose") }
        }
    }
}
