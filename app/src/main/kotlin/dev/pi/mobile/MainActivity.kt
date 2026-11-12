package dev.pi.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pi.mobile.debug.DebugExtras
import dev.pi.mobile.session.ChatController
import dev.pi.mobile.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PiRoot() }
    }

    @Composable
    private fun PiRoot() {
        val graph = (application as PiMobileApp).graph
        MaterialTheme {
            val controller: ChatController = viewModel(factory = ChatController.Factory(graph))
            if (BuildConfig.DEBUG) {
                LaunchedEffect(Unit) {
                    DebugExtras.from(intent)?.let(controller::applyDebugLaunch)
                }
            }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                AppRoot(controller)
            }
        }
    }
}
