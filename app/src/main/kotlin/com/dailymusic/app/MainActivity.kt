package com.dailymusic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dailymusic.app.ui.screen.HomeScreen
import com.dailymusic.app.ui.theme.DailyMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DailyMusicTheme {
                HomeScreen()
            }
        }
    }
}
