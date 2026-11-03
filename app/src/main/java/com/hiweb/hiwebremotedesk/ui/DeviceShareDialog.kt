package com.hiweb.hiwebremotedesk.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.DeviceShareCode
import com.hiweb.hiwebremotedesk.ui.theme.Accent
import com.hiweb.hiwebremotedesk.ui.theme.DangerRed
import com.hiweb.hiwebremotedesk.ui.theme.Surface
import com.hiweb.hiwebremotedesk.ui.theme.SurfaceButton
import com.hiweb.hiwebremotedesk.ui.theme.TextPrimary
import com.hiweb.hiwebremotedesk.ui.theme.TextSecondary
import java.io.File

/**
 * «Поделиться ПК»: QR-код со всеми настройками ПК. На другом устройстве его сканируют кнопкой «Добавить по QR-коду»
 * или отправляют картинкой (мессенджер, почта) и открывают там из изображения.
 *
 * Код занимает почти весь экран: камера другого устройства читает его, только когда на модуль приходится несколько
 * точек, а в узком окне диалога код получался мелким. По нажатию на код открывается полноэкранный режим.
 * Пароль по умолчанию в код не кладётся: этот QR нередко отправляют в мессенджер, а пароль даёт вход на ПК.
 */
@Composable
fun DeviceShareDialog(device: Device, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val hasPassword = device.password.isNotEmpty()
    var includePassword by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    val code = remember(device, includePassword) { DeviceShareCode.encode(device, includePassword && hasPassword) }
    val qr = remember(code) { QrCodes.encode(code, QR_SIZE_PX) }

    if (fullScreen) {
        QrFullScreenDialog(qr = qr, title = device.name, onDismiss = { fullScreen = false })
        return
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(24.dp),
            color = Surface,
            modifier = Modifier.padding(16.dp).fillMaxSize(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val codeLimit = minOf(maxWidth, maxHeight)
                Column(Modifier.fillMaxSize()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        Text("Поделиться ПК", color = TextPrimary, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text(device.name, color = TextSecondary, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        QrCodeView(qr, device.name, Modifier.size(codeLimit * 0.7f).clickable { fullScreen = true })
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "На другом устройстве нажмите «Добавить по QR-коду» и наведите камеру на этот код или выберите присланную картинку с ним.",
                            color = TextSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { fullScreen = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = TextPrimary),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Fullscreen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Крупный QR на весь экран")
                        }
                        if (hasPassword) {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Передать пароль", color = TextPrimary, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Switch(checked = includePassword, onCheckedChange = { includePassword = it })
                            }
                            Text(
                                if (includePassword) "Любой, у кого окажется этот QR-код, сможет войти на ПК. Отправляйте его только тем, кому доверяете."
                                else "Логин передаётся, пароль нужно будет ввести при добавлении.",
                                color = if (includePassword) DangerRed else TextSecondary,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        TextButton(onClick = onDismiss) { Text("Закрыть", color = TextSecondary) }
                        Button(
                            onClick = { shareQrImage(context, device, qr) },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceButton, contentColor = TextPrimary),
                        ) {
                            Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Отправить картинкой")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Полноэкранный QR-код для сканирования другим устройством: максимум места под код и максимальная яркость экрана —
 * с приглушённым экраном камера видит вместо модулей серое пятно. Закрытие — нажатием на любое место.
 */
@Composable
private fun QrFullScreenDialog(qr: Bitmap, title: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.let {
                it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Яркость — только этого окна: настройки экрана устройства не меняются
                it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
            }
            onDispose {
                window?.let {
                    it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().background(Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            BoxWithConstraints(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize()) {
                // Код — по меньшей стороне экрана, минус место под подсказку снизу
                val side = minOf(maxWidth, maxHeight) - 120.dp
                QrCodeView(qr, title, Modifier.size(minOf(side, maxWidth)))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Наведите камеру другого устройства на код", color = Color.White, textAlign = TextAlign.Center)
                    TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Закрыть") }
                }
            }
        }
    }
}

/** QR-код крупным планом: белая подложка (камера читает только тёмный код на светлом фоне) и без обрезки полей. */
@Composable
private fun QrCodeView(qr: Bitmap, title: String, modifier: Modifier) {
    Image(
        bitmap = qr.asImageBitmap(),
        contentDescription = "QR-код ПК $title",
        // Целочисленное увеличение без сглаживания: модули остаются квадратными и с резкими краями
        filterQuality = FilterQuality.None,
        modifier = modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.White),
    )
}

/** Сторона картинки QR-кода в пикселях: крупнее, чем нужно экрану, чтобы при увеличении не было размытия. */
private const val QR_SIZE_PX = 1200

/** Отправка QR-кода картинкой через системное «Поделиться» (файл во временной папке, доступ через FileProvider). */
private fun shareQrImage(context: Context, device: Device, qr: Bitmap) {
    try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // Прежние картинки не копим: в папке только последний отправленный код
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "pc-qr.png")
        file.outputStream().use { qr.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                "ПК «${device.name}». Чтобы добавить его, в RemoteDesk нажмите «Добавить по QR-коду» → «Из изображения» и выберите эту картинку.",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Отправить QR-код ПК"))
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось отправить картинку: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
