package com.hiweb.hiwebremotedesk.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hiweb.hiwebremotedesk.data.Device
import com.hiweb.hiwebremotedesk.data.DeviceShareCode
import com.hiweb.hiwebremotedesk.ui.theme.Accent
import com.hiweb.hiwebremotedesk.ui.theme.DangerRed
import com.hiweb.hiwebremotedesk.ui.theme.Surface
import com.hiweb.hiwebremotedesk.ui.theme.SurfaceButton
import com.hiweb.hiwebremotedesk.ui.theme.TextPrimary
import com.hiweb.hiwebremotedesk.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Подпись кнопки добавления ПК по QR-коду. */
const val QR_ADD_LABEL = "Добавить по QR-коду"

/**
 * Добавление ПК по QR-коду из «Поделиться ПК» ([DeviceShareDialog]): код сканируется камерой устройства или читается
 * с картинки (снимок экрана, фото из мессенджера). [onScanned] получает ПК с пустым id.
 */
@Composable
fun QrScanDialog(onScanned: (Device) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnScanned by rememberUpdatedState(onScanned)
    // Код распознан: дальше кадры не разбираем, иначе форма добавления откроется несколько раз
    val done = remember { AtomicBoolean(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var decodingImage by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var permissionDenied by remember { mutableStateOf(false) }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    /** Обработка текста из кода; true — это ПК и он передан дальше. */
    fun handle(text: String?): Boolean {
        if (done.get()) return true
        if (text == null) return false
        val device = DeviceShareCode.decode(text)
        if (device == null) {
            message = "Это QR-код не с настройками ПК. Откройте на другом устройстве «Поделиться» у нужного ПК."
            return false
        }
        if (!done.compareAndSet(false, true)) return true
        currentOnScanned(device)
        return true
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        permissionDenied = !granted
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        decodingImage = true
        message = null
        scope.launch {
            val text = withContext(Dispatchers.Default) { decodeImage(context, uri) }
            decodingImage = false
            if (text == null) message = "На картинке не найден QR-код. Выберите картинку, где код виден целиком и не обрезан."
            else handle(text)
        }
    }

    LaunchedEffect(Unit) {
        if (hasCamera && !hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(24.dp),
            color = Surface,
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.94f),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                Text(QR_ADD_LABEL, color = TextPrimary, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Наведите камеру на QR-код из «Поделиться» на другом устройстве или выберите картинку с ним.",
                    color = TextSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                ) {
                    when {
                        hasCamera && hasCameraPermission -> {
                            QrCameraPreview(onText = { text -> handle(text) })
                            // Рамка-прицел посередине
                            Box(Modifier.fillMaxSize(0.7f).border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp)))
                        }
                        else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                            Icon(Icons.Outlined.QrCodeScanner, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                when {
                                    !hasCamera -> "У устройства нет камеры — выберите картинку с QR-кодом."
                                    permissionDenied -> "Нет доступа к камере. Разрешите его или выберите картинку с QR-кодом."
                                    else -> "Нужен доступ к камере, чтобы сканировать QR-код."
                                },
                                color = Color.White, textAlign = TextAlign.Center,
                            )
                            if (hasCamera) {
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = TextPrimary),
                                ) { Text("Разрешить камеру") }
                            }
                        }
                    }
                    if (decodingImage) CircularProgressIndicator()
                }
                message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = DangerRed, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !decodingImage,
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceButton, contentColor = TextPrimary),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Image, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Из изображения", maxLines = 1)
                    }
                    TextButton(onClick = onDismiss) { Text("Отмена", color = TextSecondary) }
                }
            }
        }
    }
}

/** Видео с основной камеры устройства; каждый кадр проверяется на QR-код, текст найденного кода — в [onText]. */
@Composable
private fun QrCameraPreview(onText: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val mainExecutor = ContextCompat.getMainExecutor(context)
        // Один и тот же «чужой» QR-код не разбираем повторно в каждом кадре
        var lastText: String? = null
        // Окно закрыли раньше, чем камера была готова: подключать её уже не нужно
        var disposed = false
        providerFuture.addListener({
            if (disposed) return@addListener
            val cameraProvider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
            provider = cameraProvider
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val text = QrCodes.decode(bytes, image.width, image.height, plane.rowStride)
                    if (text != null && text != lastText) {
                        lastText = text
                        mainExecutor.execute { currentOnText(text) }
                    }
                } catch (_: Exception) {
                } finally {
                    image.close()
                }
            }
            val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
            else CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            } catch (_: Exception) {
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** Текст QR-кода с картинки, выбранной пользователем, или null. */
private fun decodeImage(context: Context, uri: Uri): String? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    // Программный буфер: из «аппаратного» Bitmap нельзя прочитать пиксели
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
    QrCodes.decode(bitmap)
}.getOrNull()
