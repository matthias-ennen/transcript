package de.matthiasennen.transcript

import android.content.Intent
import android.net.Uri
import android.os.Build
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
        if (savedInstanceState == null) handleIncomingIntent(intent)
        setContent {
            TranscriptTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                if ((intent.clipData?.itemCount ?: 0) > 1) {
                    viewModel.reportUnsupportedShare(
                        "Mehrere Dateien können nicht gleichzeitig übernommen werden."
                    )
                } else {
                    viewModel.receiveSharedMedia(uri, intent.type)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> viewModel.reportUnsupportedShare(
                "Mehrere Dateien können nicht gleichzeitig übernommen werden."
            )
        }
    }
}
