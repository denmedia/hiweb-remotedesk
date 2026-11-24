package com.hiweb.hiwebremotedesk.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiweb.hiwebremotedesk.MainActivity
import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.DeviceRepository
import com.hiweb.hiwebremotedesk.data.OsType
import com.hiweb.hiwebremotedesk.data.ThumbnailStore
import com.hiweb.hiwebremotedesk.ui.OsLogo
import com.hiweb.hiwebremotedesk.ui.theme.Accent
import com.hiweb.hiwebremotedesk.ui.theme.Background
import com.hiweb.hiwebremotedesk.ui.theme.HiWebRemoteDeskTheme
import com.hiweb.hiwebremotedesk.ui.theme.Outline
import com.hiweb.hiwebremotedesk.ui.theme.SurfaceCard
import com.hiweb.hiwebremotedesk.ui.theme.TextPrimary
import com.hiweb.hiwebremotedesk.ui.theme.TextSecondary

/**
 * Выбор ПК для виджета «ПК»: открывается системой при добавлении виджета, при его перенастройке
 * (виджет объявлен `reconfigurable`) и по значку «⇄» на самом виджете.
 */
class PcWidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Закрытие без выбора отменяет добавление виджета.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        val current = PcWidgets.deviceFor(this, widgetId)
        setContent {
            HiWebRemoteDeskTheme {
                val devices by DeviceRepository.get(this).devices.collectAsState()
                PickScreen(devices, current, onPick = ::pick, onOpenApp = {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                })
            }
        }
    }

    private fun pick(device: Device) {
        PcWidgets.bind(this, widgetId, device.id)
        PcWidgets.watch(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@Composable
private fun PickScreen(devices: List<Device>, current: String?, onPick: (Device) -> Unit, onOpenApp: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Background).safeDrawingPadding().padding(16.dp)) {
        Text("Какой ПК показать на виджете?", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Нажатие на превью в виджете сразу открывает рабочий стол этого ПК.", color = TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        if (devices.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Список ПК пуст. Сначала добавьте ПК в приложении.", color = TextSecondary, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenApp) { Text("Открыть RemoteDesk") }
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 260.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(devices, key = { it.id }) { d -> PickCard(d, selected = d.id == current, onClick = { onPick(d) }) }
        }
    }
}

/** Карточка ПК в выборе — упрощённая карточка из списка: превью, название, адрес и система. */
@Composable
private fun PickCard(device: Device, selected: Boolean, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val thumb = remember(device.id) { ThumbnailStore.get(context).load(device.id) }
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .clip(shape)
            .background(SurfaceCard)
            .border(if (selected) 2.dp else 1.dp, if (selected) Accent else Outline, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        val previewShape = RoundedCornerShape(12.dp)
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(previewShape), contentAlignment = Alignment.Center) {
            if (thumb != null) {
                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            if (device.os == OsType.UBUNTU) listOf(Color(0xFF5E2750), Color(0xFFE95420), Color(0xFF2C001E))
                            else listOf(Color(0xFF0B3C9E), Color(0xFF1E6BF2), Color(0xFF0A1F5C)),
                        ),
                    ),
                    contentAlignment = Alignment.Center,
                ) { OsLogo(device.os, size = 44.dp, color = Color.White.copy(alpha = 0.85f)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(device.host, color = TextSecondary, fontSize = 14.sp, maxLines = 1)
            }
            OsLogo(device.os, 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(device.osLabel, color = TextSecondary, fontSize = 14.sp)
        }
    }
}
