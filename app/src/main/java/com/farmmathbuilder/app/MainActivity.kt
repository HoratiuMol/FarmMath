package com.farmmathbuilder.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.farmmathbuilder.app.audio.SoundManager
import com.farmmathbuilder.app.data.db.AppDatabase
import com.farmmathbuilder.app.data.repository.FarmRepository
import com.farmmathbuilder.app.domain.TextSizeOption
import com.farmmathbuilder.app.ui.screens.FarmScreen
import com.farmmathbuilder.app.ui.screens.SettingsScreen
import com.farmmathbuilder.app.ui.theme.FarmMathBuilderTheme
import com.farmmathbuilder.app.viewmodel.FarmViewModel

class MainActivity : ComponentActivity() {

    private lateinit var repository: FarmRepository

    private val viewModel: FarmViewModel by viewModels {
        val db = AppDatabase.getInstance(applicationContext)
        repository = FarmRepository(db.cellDao(), db.playerDao(), db.settingsDao())
        FarmViewModel.factory(repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SoundManager.init(applicationContext)
        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val settings = uiState.settings
            FarmMathBuilderTheme(
                highContrast = settings?.highContrast ?: false,
                textSize = settings?.textSize ?: TextSizeOption.MEDIUM
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showSettings by rememberSaveable { mutableStateOf(false) }

                    // Keep SoundManager's volume/mute in sync with the persisted
                    // settings, and start the farm's ambient loop once settings have
                    // resolved from Room (avoids starting at hardcoded defaults then
                    // jumping to the real value a frame later).
                    LaunchedEffect(settings?.sfxVolume, settings?.musicVolume, settings?.muted) {
                        if (settings != null) {
                            SoundManager.updateSettings(
                                sfxVolume = settings.sfxVolume,
                                musicVolume = settings.musicVolume,
                                muted = settings.muted
                            )
                            SoundManager.playAmbient(SoundManager.Sounds.RIVER_AMBIENT)
                        }
                    }

                    when {
                        uiState.isLoading -> {
                            // Splash-equivalent: empty surface while first Room read resolves (<3s cold start, NFR-009).
                        }
                        showSettings -> {
                            SettingsScreen(
                                player = uiState.player,
                                settings = uiState.settings,
                                onBack = {
                                    SoundManager.playSfx(SoundManager.Sounds.BACK)
                                    showSettings = false
                                },
                                onMusicVolumeChange = { v -> viewModel.updateSettings { it.copy(musicVolume = v) } },
                                onSfxVolumeChange = { v -> viewModel.updateSettings { it.copy(sfxVolume = v) } },
                                onMuteToggle = { m -> viewModel.updateSettings { it.copy(muted = m) } },
                                onTextSizeChange = { t -> viewModel.updateSettings { it.copy(textSize = t) } },
                                onHighContrastToggle = { h -> viewModel.updateSettings { it.copy(highContrast = h) } },
                                onNotificationsToggle = { n -> viewModel.updateSettings { it.copy(notificationsEnabled = n) } },
                                onAgeBandChange = { a -> viewModel.updateAgeBand(a) }
                            )
                        }
                        else -> {
                            FarmScreen(
                                viewModel = viewModel,
                                onOpenSettings = {
                                    SoundManager.playSfx(SoundManager.Sounds.CLICK)
                                    showSettings = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // FR-073: save state on app background/pause in addition to per-action Room writes.
        if (::repository.isInitialized) {
            viewModel.onAppBackgrounded()
        }
        SoundManager.pauseAmbient()
    }

    override fun onStop() {
        super.onStop()
        if (::repository.isInitialized) {
            viewModel.onAppBackgrounded()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) {
            viewModel.onAppForegrounded()
        }
        SoundManager.resumeAmbient()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            SoundManager.release()
        }
    }
}
