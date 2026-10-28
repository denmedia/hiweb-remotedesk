package com.hiweb.hiwebremotedesk.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hiweb.hiwebremotedesk.data.AppPermission
import com.hiweb.hiwebremotedesk.data.AppPermissions
import com.hiweb.hiwebremotedesk.ui.theme.Accent
import com.hiweb.hiwebremotedesk.ui.theme.DangerRed
import com.hiweb.hiwebremotedesk.ui.theme.OnlineGreen
import com.hiweb.hiwebremotedesk.ui.theme.Outline
import com.hiweb.hiwebremotedesk.ui.theme.Surface
import com.hiweb.hiwebremotedesk.ui.theme.SurfaceCard
import com.hiweb.hiwebremotedesk.ui.theme.TextPrimary
import com.hiweb.hiwebremotedesk.ui.theme.TextSecondary

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Счётчик, растущий при каждом возвращении на экран: права выдаются в системных настройках,
 * и после возврата список (и значок на кнопке) должен показать новое состояние.
 */
@Composable
fun rememberPermissionsTick(): Int {
    val activity = LocalContext.current.findActivity()
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(activity) {
        val owner = activity as? androidx.lifecycle.LifecycleOwner
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    return tick
}

/**
 * Список прав приложения: название, описание мелким шрифтом, справа — значок «выдано / не выдано»,
 * для невыданных — кнопка, которая запрашивает право или открывает нужный экран настроек.
 */
@Composable
fun PermissionsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    val tick = rememberPermissionsTick()
    var changes by remember { mutableIntStateOf(0) }
    val items = remember { AppPermissions.applicable() }
    val granted = remember(tick, changes) { items.associateWith { AppPermissions.isGranted(context, it) } }

    // Обычное право после двух отказов система больше не спрашивает: тогда кнопка ведёт в настройки.
    fun blockedKey(p: AppPermission) = "perm_blocked_${p.name}"
    var pending by remember { mutableStateOf<AppPermission?>(null) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending ?: return@rememberLauncherForActivityResult
        pending = null
        val denyForever = !ok && activity != null && !activity.shouldShowRequestPermissionRationale(p.runtime!!)
        prefs.edit().putBoolean(blockedKey(p), denyForever).apply()
        changes++
    }

    fun openSettings(p: AppPermission) {
        runCatching { context.startActivity(AppPermissions.settingsIntent(context, p)) }
            .onFailure { runCatching { context.startActivity(AppPermissions.appDetailsIntent(context)) } }
    }

    // Пункт, который нельзя открыть напрямую: сначала показываем, куда нажимать в настройках.
    var guide by remember { mutableStateOf<AppPermission?>(null) }

    fun act(p: AppPermission) {
        val runtime = p.runtime
        if (runtime == null && p.howTo != null) {
            guide = p
        } else if (runtime != null && !prefs.getBoolean(blockedKey(p), false)) {
            pending = p
            request.launch(runtime)
        } else {
            openSettings(p)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .fillMaxHeight(0.9f)
                .clip(shape)
                .background(Surface)
                .border(1.dp, Outline, shape),
        ) {
            val done = granted.values.count { it }
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Права приложения", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (done == items.size) "Все права выданы" else "Выдано $done из ${items.size}",
                        color = if (done == items.size) OnlineGreen else TextSecondary,
                        fontSize = 14.sp,
                    )
                }
                TextButton(onClick = onDismiss) { Text("Закрыть", color = TextSecondary) }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "Приложение работает и без них: каждое право включает только свою возможность.",
                        color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(items, key = { it.name }) { p ->
                    val ok = granted[p] == true
                    PermissionRow(
                        p, ok,
                        action = if (prefs.getBoolean(blockedKey(p), false) || p.runtime == null) "Открыть настройки" else "Разрешить",
                        onAction = { act(p) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
    guide?.let { p ->
        PermissionGuide(p, onOpen = { guide = null; openSettings(p) }, onDismiss = { guide = null })
    }
}

@Composable
private fun PermissionGuide(p: AppPermission, onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(p.title, color = TextPrimary) },
        text = { Text(p.howTo.orEmpty(), color = TextSecondary, fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = onOpen) { Text("Открыть настройки", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = TextSecondary) } },
    )
}

@Composable
private fun PermissionRow(p: AppPermission, granted: Boolean, action: String, onAction: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(SurfaceCard).border(1.dp, Outline, shape).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(p.description, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            if (!granted) {
                Box(
                    Modifier.padding(top = 6.dp).height(38.dp).clip(RoundedCornerShape(10.dp)).background(Accent)
                        .clickable(onClick = onAction).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(action, color = TextPrimary, fontSize = 14.sp, maxLines = 1) }
            }
        }
        Spacer(Modifier.size(12.dp))
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = if (granted) "Выдано" else "Не выдано",
            tint = if (granted) OnlineGreen else DangerRed,
            modifier = Modifier.size(28.dp),
        )
    }
}
