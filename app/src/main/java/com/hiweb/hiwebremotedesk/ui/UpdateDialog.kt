package com.hiweb.hiwebremotedesk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hiweb.hiwebremotedesk.R
import com.hiweb.hiwebremotedesk.data.DownloadsFolder
import com.hiweb.hiwebremotedesk.data.UpdateManager
import com.hiweb.hiwebremotedesk.data.UpdateState
import com.hiweb.hiwebremotedesk.ui.theme.Accent
import com.hiweb.hiwebremotedesk.ui.theme.DangerRed
import com.hiweb.hiwebremotedesk.ui.theme.OnlineGreen
import com.hiweb.hiwebremotedesk.ui.theme.Outline
import com.hiweb.hiwebremotedesk.ui.theme.Surface
import com.hiweb.hiwebremotedesk.ui.theme.SurfaceButton
import com.hiweb.hiwebremotedesk.ui.theme.TextPrimary
import com.hiweb.hiwebremotedesk.ui.theme.TextSecondary

/**
 * Ручная проверка обновления: проверка стартует при открытии окна, при наличии новой версии появляется
 * кнопка «Скачать новую версию: …». Файл попадает в Downloads, установку пользователь делает сам.
 */
@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsState()
    val version = remember(context) { UpdateManager.currentVersion(context) }
    LaunchedEffect(Unit) { UpdateManager.check(context) }

    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .clip(shape)
                .background(Surface)
                .border(1.dp, Outline, shape)
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center,
            ) { Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.requiredSize(108.dp)) }
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(title(state), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (state is UpdateState.Checking || state is UpdateState.Idle) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Текущая версия: $version", color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Text(
                "Новая версия будет скачана с GitHub в папку Downloads, её нужно будет установить самостоятельно.",
                color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp,
            )

            when (val s = state) {
                is UpdateState.Available -> {
                    Spacer(Modifier.height(18.dp))
                    UpdateButton("Скачать новую версию: ${s.release.version}", Accent) { UpdateManager.download(context, s.release) }
                }
                is UpdateState.Downloading -> {
                    Spacer(Modifier.height(18.dp))
                    LinearProgressIndicator(
                        progress = { s.percent / 100f },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                        color = Accent,
                        trackColor = SurfaceButton,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Скачивание ${s.release.version}: ${s.percent}%", color = TextSecondary, fontSize = 13.sp)
                }
                is UpdateState.Downloaded -> {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Файл ${s.release.apkName} сохранён в папке Downloads. Откройте его и установите.",
                        color = OnlineGreen, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    UpdateButton("Открыть папку Downloads", SurfaceButton) { DownloadsFolder.open(context) }
                }
                is UpdateState.Failed -> {
                    Spacer(Modifier.height(18.dp))
                    Text(s.message, color = DangerRed, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    UpdateButton("Повторить", SurfaceButton) { UpdateManager.retry(context) }
                }
                else -> {}
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("Закрыть", color = TextSecondary) }
        }
    }
}

private fun title(state: UpdateState): String = when (state) {
    UpdateState.Idle, UpdateState.Checking -> "Проверка обновления"
    UpdateState.UpToDate -> "У вас последняя версия"
    is UpdateState.Available -> "Доступна новая версия"
    is UpdateState.Downloading -> "Скачивание обновления"
    is UpdateState.Downloaded -> "Обновление скачано"
    is UpdateState.Failed -> "Не получилось"
}

@Composable
private fun UpdateButton(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}
