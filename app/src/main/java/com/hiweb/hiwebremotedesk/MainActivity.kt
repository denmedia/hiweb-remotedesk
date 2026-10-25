package com.hiweb.hiwebremotedesk

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hiweb.hiwebremotedesk.ui.HomeScreen
import com.hiweb.hiwebremotedesk.ui.theme.HiWebRemoteDeskTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            HiWebRemoteDeskTheme {
                HomeScreen(
                    onLaunch = { id, power ->
                        startActivity(
                            Intent(this, RemoteActivity::class.java)
                                .putExtra(RemoteActivity.EXTRA_DEVICE_ID, id)
                                .putExtra(RemoteActivity.EXTRA_POWER_ACTION, power?.name),
                        )
                    },
                )
            }
        }
        // Если ПК привязаны к HiWeb Agent, ждём их команду «Переключиться на Планшет».
        com.hiweb.hiwebremotedesk.agent.SwitchListenerService.startIfPaired(this)
    }
}
