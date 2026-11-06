package com.hebowiz.speakerarray

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            LaunchedEffect(model.session.reverseLandscape, model.session.orientationChosen) {
                if (model.session.orientationChosen) requestedOrientation = if (model.session.reverseLandscape) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            SpeakerArrayApp(model)
        }
    }
    override fun onStop() { if (!isChangingConfigurations) model.background(); super.onStop() }
}
