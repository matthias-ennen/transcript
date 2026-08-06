package de.matthiasennen.transcript

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import de.matthiasennen.transcript.ui.main.MainScreen
import de.matthiasennen.transcript.ui.main.MainScreenViewModel
import de.matthiasennen.transcript.ui.theme.TranscriptTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainScreenViewModel by viewModels { MainScreenViewModel.factory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TranscriptTheme {
                MainScreen(viewModel)
            }
        }
    }
}
