package app.wraith.client.ui

import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.wraith.client.L
import app.wraith.client.RoutingConfig
import app.wraith.client.formatBytes
import app.wraith.client.jsonOf
import app.wraith.client.safe
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.delay

/** Every overlay of the app, driven by the model's state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Overlays(m: AppModel) {
    // Snackbar-like message at the top of the screen.
    val msg = m.message
    LaunchedEffect(msg) { if (msg != null) { delay(3800); if (m.message == msg) m.message = null } }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(msg != null || m.busy != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Row(Modifier.clip(RoundedCornerShape(20.dp)).background(W.glassHigh).border(1.dp, W.stroke, RoundedCornerShape(20.dp)).clickable { m.message = null }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (m.busy != null) { CircularProgressIndicator(Modifier.size(18.dp), color = W.accent, strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
                Text(m.busy ?: msg.orEmpty(), color = W.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
            }
        }
    }

    m.confirm?.let { c ->
        AlertDialog(onDismissRequest = { m.confirm = null }, containerColor = W.glassHigh.copy(alpha = 1f),
            title = { Text(c.title, color = W.text) }, text = { Text(c.text, color = W.muted) },
            confirmButton = { TextButton({ m.confirm = null; try { c.run() } catch (e: Exception) { m.error(e) } }) { Text(c.action, color = if (c.danger) W.bad else W.accent) } },
            dismissButton = { TextButton({ m.confirm = null }) { Text(L.t("Отмена", "Cancel"), color = W.muted) } })
    }

    m.info?.let { (title, text) ->
        AlertDialog(onDismissRequest = { m.info = null }, containerColor = Color(0xFF1B1822),
            title = { Text(title, color = W.text) },
            text = { SelectionContainer { Text(text, Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), color = W.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton({ m.info = null }) { Text(L.t("Готово", "Done"), color = W.accent) } },
            dismissButton = { TextButton({ m.copy(text) }) { Text(L.t("Скопировать", "Copy"), color = W.muted) } })
    }

    m.input?.let { spec -> InputDialog(m, spec) }

    m.choice?.let { c ->
        ModalBottomSheet({ m.choice = null }, sheetState = rememberModalBottomSheetState(true), containerColor = Color(0xFF17151D)) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(c.title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = W.text, style = MaterialTheme.typography.titleLarge)
                c.labels.forEachIndexed { i, label ->
                    Row(Modifier.fillMaxWidth().clickable { m.choice = null; try { c.pick(i) } catch (e: Exception) { m.error(e) } }.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(i == c.selected, null, colors = RadioButtonDefaults.colors(selectedColor = W.accent, unselectedColor = W.faint))
                        Spacer(Modifier.width(10.dp))
                        Text(label, Modifier.padding(vertical = 12.dp), color = W.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }

    m.sheet?.let { sh ->
        ModalBottomSheet({ m.sheet = null }, sheetState = rememberModalBottomSheetState(true), containerColor = Color(0xFF17151D)) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(sh.title, Modifier.padding(horizontal = 24.dp), color = W.text, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                if (sh.subtitle.isNotEmpty()) Text(sh.subtitle, Modifier.padding(horizontal = 24.dp), color = W.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Spacer(Modifier.height(10.dp))
                sh.actions.forEach { a ->
                    Row(Modifier.fillMaxWidth().clickable { m.sheet = null; try { a.run() } catch (e: Exception) { m.error(e) } }.padding(horizontal = 24.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(a.icon, null, Modifier.size(22.dp), tint = if (a.danger) W.bad else W.muted)
                        Spacer(Modifier.width(18.dp))
                        Text(a.label, color = if (a.danger) W.bad else W.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }

    m.importText?.let { initial -> ImportSheet(m, initial) }
    m.editor?.let { e -> EditorDialog(m, e) }
    m.ruleEdit?.let { (index, rule) -> RuleDialog(m, index, rule) }

    m.qr?.let { value ->
        val bitmap = remember(value) {
            try {
                val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 720, 720)
                Bitmap.createBitmap(720, 720, Bitmap.Config.RGB_565).also { b -> for (y in 0 until 720) for (x in 0 until 720) b.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()) }.asImageBitmap()
            } catch (_: Exception) { null }
        }
        AlertDialog(onDismissRequest = { m.qr = null }, containerColor = Color(0xFF1B1822),
            title = { Text(L.t("QR-код", "QR code"), color = W.text) },
            text = { if (bitmap != null) Image(bitmap, null, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) else Text(L.t("Конфигурация слишком большая для QR. Скопируйте её текстом.", "The configuration is too large for a QR code. Copy it as text."), color = W.muted) },
            confirmButton = { TextButton({ m.qr = null }) { Text(L.t("Готово", "Done"), color = W.accent) } })
    }

    m.update?.let { r ->
        AlertDialog(onDismissRequest = { m.update = null }, containerColor = Color(0xFF1B1822),
            title = { Text(L.t("Доступна версия ", "Version available: ") + r.name, color = W.text) },
            text = { Text(r.notes.ifEmpty { L.t("Новая версия Wraith.", "A new Wraith version.") }, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), color = W.muted) },
            confirmButton = { TextButton({ m.downloadUpdate(r) }) { Text(L.t("Обновить", "Update"), color = W.accent) } },
            dismissButton = { TextButton({ m.update = null }) { Text(L.t("Позже", "Later"), color = W.muted) } })
    }

    m.download?.let { (done, total) ->
        Dialog({}, DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Glass(Modifier.fillMaxWidth(), RoundedCornerShape(28.dp), Color(0xFF1B1822)) {
                Column(Modifier.padding(24.dp)) {
                    Text(L.t("Загрузка обновления", "Downloading the update"), color = W.text, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    if (total > 0) LinearProgressIndicator({ (done.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = W.accent, trackColor = W.glass)
                    else LinearProgressIndicator(Modifier.fillMaxWidth(), color = W.accent, trackColor = W.glass)
                    Spacer(Modifier.height(10.dp))
                    Text(formatBytes(done) + if (total > 0) L.t(" из ", " of ") + formatBytes(total) else "", color = W.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = W.accent, unfocusedBorderColor = Color(0xFF3A3644), cursorColor = W.accent,
    focusedTextColor = W.text, unfocusedTextColor = W.text, focusedLabelColor = W.accent, unfocusedLabelColor = W.muted)

@Composable
private fun InputDialog(m: AppModel, spec: Input) {
    var value by remember(spec) { mutableStateOf(spec.value) }
    var error by remember(spec) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { m.input = null }, containerColor = Color(0xFF1B1822),
        title = { Text(spec.title, color = W.text, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(value, { value = it; error = null }, Modifier.fillMaxWidth().then(if (spec.multiline) Modifier.heightIn(min = 140.dp) else Modifier),
                singleLine = !spec.multiline, isError = error != null, supportingText = error?.let { { Text(it, color = W.bad) } },
                keyboardOptions = KeyboardOptions(keyboardType = if (spec.number) KeyboardType.Number else KeyboardType.Text), colors = fieldColors(), shape = RoundedCornerShape(16.dp))
        },
        confirmButton = { TextButton({ try { spec.save(value); m.input = null } catch (e: Exception) { error = e.safe() } }) { Text(L.t("Сохранить", "Save"), color = W.accent) } },
        dismissButton = { TextButton({ m.input = null }) { Text(L.t("Отмена", "Cancel"), color = W.muted) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(m: AppModel, initial: String) {
    var text by remember(initial) { mutableStateOf(initial) }
    ModalBottomSheet({ m.importText = null }, sheetState = rememberModalBottomSheetState(true), containerColor = Color(0xFF17151D)) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(L.t("Новое подключение", "New connection"), color = W.text, style = MaterialTheme.typography.titleLarge)
            Text(L.t("Ссылка на сервер, HTTPS-подписка или JSON Xray", "A server link, an HTTPS subscription or Xray JSON"), color = W.muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp), placeholder = { Text("vless://…  https://…", color = W.faint) },
                colors = fieldColors(), shape = RoundedCornerShape(18.dp), textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(L.t("Вставить", "Paste"), Ic.Copy, modifier = Modifier.weight(1f)) {
                    m.ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(m.ctx)?.toString()?.let { text = it.trim() }
                }
                Pill("QR", Ic.Qr, modifier = Modifier.weight(1f)) { m.host?.scanQr() }
                Pill(L.t("Файл", "File"), Ic.Download, modifier = Modifier.weight(1f)) { m.host?.openDocument(PURPOSE_IMPORT) }
            }
            Spacer(Modifier.height(10.dp))
            Pill(L.t("Добавить", "Add"), Ic.Plus, filled = true, modifier = Modifier.fillMaxWidth(), enabled = text.isNotBlank()) {
                val v = text.trim(); m.importText = null; m.doImport(v)
            }
        }
    }
}

@Composable
private fun EditorDialog(m: AppModel, e: Editor) {
    var value by remember(e) { mutableStateOf(e.value) }
    var error by remember(e) { mutableStateOf<String?>(null) }
    Dialog({ m.editor = null }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(W.bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Back, L.t("Назад", "Back")) { m.editor = null }
                Spacer(Modifier.width(12.dp))
                Text(e.title, Modifier.weight(1f), color = W.text, style = MaterialTheme.typography.titleLarge, maxLines = 2)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value, { value = it; error = null }, Modifier.fillMaxWidth().weight(1f), isError = error != null,
                supportingText = error?.let { { Text(it, color = W.bad) } }, colors = fieldColors(), shape = RoundedCornerShape(18.dp),
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(L.t("Отмена", "Cancel"), modifier = Modifier.weight(1f)) { m.editor = null }
                Pill(L.t("Сохранить", "Save"), Ic.Check, filled = true, modifier = Modifier.weight(1f)) { try { e.save(value.trim()); m.editor = null } catch (ex: Exception) { error = ex.safe() } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(m: AppModel, index: Int, original: org.json.JSONObject?) {
    var name by remember { mutableStateOf(original?.optString("name").orEmpty()) }
    var value by remember { mutableStateOf(original?.optString("value").orEmpty()) }
    var kind by remember { mutableStateOf(original?.optString("kind")?.takeIf { it in RoutingConfig.KINDS } ?: "domain_suffix") }
    var action by remember { mutableStateOf(original?.optString("action", "proxy") ?: "proxy") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { m.ruleEdit = null }, containerColor = Color(0xFF1B1822),
        title = { Text(if (original == null) L.t("Новое правило", "New rule") else L.t("Правило", "Rule"), color = W.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(L.t("Название", "Name")) }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoutingConfig.KINDS.forEach { k -> Tag(RoutingConfig.kindLabel(k), k == kind) { kind = k } }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value, { value = it; error = null }, Modifier.fillMaxWidth().heightIn(min = 110.dp), label = { Text(when (kind) { "geosite" -> "category-ru, google…"; "geoip" -> "ru, private…"; "ip_cidr" -> "10.0.0.0/8"; else -> "example.org" }) },
                    isError = error != null, supportingText = { Text(error ?: L.t("Одна запись на строку", "One entry per line"), color = if (error != null) W.bad else W.faint) }, colors = fieldColors(), shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("proxy" to L.t("Через VPN", "Via VPN"), "direct" to L.t("Напрямую", "Direct"), "block" to L.t("Блок", "Block")).forEach { (v, l) -> Tag(l, v == action) { action = v } }
                }
            }
        },
        confirmButton = { TextButton({
            try {
                if (value.isBlank()) throw IllegalArgumentException(L.t("Заполните правило", "Fill in the rule"))
                val r = jsonOf("name" to name.trim().ifEmpty { value.trim().lines().first() }, "value" to value.trim(), "kind" to kind, "action" to action, "enabled" to (original?.optBoolean("enabled", true) ?: true))
                if (index < 0) m.store.addRule(r) else m.store.replaceRule(index, r)
                m.ruleEdit = null; m.changed()
            } catch (e: Exception) { error = e.safe() }
        }) { Text(L.t("Сохранить", "Save"), color = W.accent) } },
        dismissButton = { TextButton({ m.ruleEdit = null }) { Text(L.t("Отмена", "Cancel"), color = W.muted) } })
}

@Composable
private fun Tag(text: String, on: Boolean, onClick: () -> Unit) {
    Text(text, Modifier.clip(CircleShape).background(if (on) W.accent else W.glass).border(1.dp, if (on) Color.Transparent else W.stroke, CircleShape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        color = if (on) W.onAccent else W.text, style = MaterialTheme.typography.labelMedium)
}
