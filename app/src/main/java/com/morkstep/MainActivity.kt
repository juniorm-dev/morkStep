package com.morkstep

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morkstep.ui.MainViewModel
import com.morkstep.ui.MainViewModelFactory
import com.morkstep.ui.MorkApp
import com.morkstep.ui.MorkTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModelFactory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Per-profile dark theme: null (default) follows the system; the
            // profile's switch forces dark or light.
            val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
            val dark = when (activeProfile?.darkMode) {
                true -> true
                false -> false
                null -> isSystemInDarkTheme()
            }
            MorkTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MorkApp(viewModel)
                }
            }
        }
    }
}