package app.wraith.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.wraith.client.ui.ACCENTS
import app.wraith.client.ui.AppModel
import app.wraith.client.ui.Backdrop
import app.wraith.client.ui.FloatingNav
import app.wraith.client.ui.HomeScreen
import app.wraith.client.ui.Host
import app.wraith.client.ui.Ic
import app.wraith.client.ui.NavItem
import app.wraith.client.ui.Overlays
import app.wraith.client.ui.PURPOSE_RESTORE
import app.wraith.client.ui.ServersScreen
import app.wraith.client.ui.SettingsScreen
import app.wraith.client.ui.StatsScreen
import app.wraith.client.ui.WraithTheme
import app.wraith.client.ui.palette
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File

class MainActivity : ComponentActivity(), Host {
    private val model: AppModel by viewModels()
    private var documentPurpose = 0
    private var exportData = ""

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) CoreRouter.start(this) else model.toast(L.t("Разрешение VPN не выдано", "VPN permission not granted"))
    }
    private val qrScan = registerForActivityResult(ScanContract()) { r -> r.contents?.let { model.importText = it } }
    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.importFile(uri, documentPurpose == PURPOSE_RESTORE) }
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.task(L.t("Сохраняем…", "Saving…")) { contentResolver.openOutputStream(uri)!!.use { it.write(exportData.toByteArray()) }; exportData = ""; L.t("Сохранено", "Saved") }
    }
    private val photoPick = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) model.setPhoto(uri) }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        model.host = this
        setContent { App(model) }
        if (model.storeError == null) {
            if (savedInstanceState == null) { model.onOpen(); model.handleIntent(intent) }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); model.handleIntent(intent) }
    override fun onResume() { super.onResume(); if (model.storeError == null) model.resumeUpdate() }
    override fun onDestroy() { if (model.host === this) model.host = null; super.onDestroy() }

    // ---- Host ------------------------------------------------------------------------------------
    override fun requestVpn(intent: Intent) = vpnPermission.launch(intent)
    override fun scanQr() = qrScan.launch(ScanOptions().setOrientationLocked(false).setBeepEnabled(false).setPrompt(L.t("QR-код сервера или подписки", "QR code of a server or subscription")))
    override fun openDocument(purpose: Int) { documentPurpose = purpose; openDoc.launch(arrayOf("*/*")) }
    override fun export(name: String, data: String) { exportData = data; createDoc.launch(name) }
    override fun pickPhoto() = photoPick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    override fun open(intent: Intent) { try { startActivity(intent) } catch (e: Exception) { model.error(e) } }
    override fun install(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun App(m: AppModel) {
    if (m.storeError != null) {
        WraithTheme(palette(ACCENTS.getValue("sakura"), true)) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(L.t("Не удалось открыть зашифрованное хранилище. Данные сохранены; не очищайте приложение.", "Could not open the encrypted storage. Your data is kept; do not clear the app."), color = Color.White)
            }
        }
        return
    }
    val rev by m.revision.collectAsState()
    val status by Tunnel.status.collectAsState()
    val settings = remember(rev) { m.store.settings() }
    val accentKey = settings.optString("accent", "system")
    val accent = when (accentKey) {
        "photo" -> m.photoAccent ?: ACCENTS.getValue("sakura")
        "system" -> if (Build.VERSION.SDK_INT >= 31) Color(m.ctx.getColor(android.R.color.system_accent1_200)) else ACCENTS.getValue("sakura")
        else -> ACCENTS[accentKey] ?: ACCENTS.getValue("sakura")
    }
    WraithTheme(palette(accent, settings.optBoolean("amoled"))) {
        BackHandler(m.inSettings || m.tab != 0 || m.pages.isNotEmpty()) { m.back() }
        Box(Modifier.fillMaxSize()) {
            Backdrop(m.photo, settings.optDouble("bgBlur", 0.0).toFloat(), settings.optDouble("bgDim", 0.35).toFloat(), status.state)
            val screen = if (m.inSettings) 10 + m.pages.size else m.tab
            AnimatedContent(screen, transitionSpec = {
                val forward = targetState > initialState
                (fadeIn(tween(260)) + slideInHorizontally(tween(320)) { w -> if (forward) w / 8 else -w / 8 }) togetherWith
                    (fadeOut(tween(180)) + slideOutHorizontally(tween(320)) { w -> if (forward) -w / 10 else w / 10 })
            }, label = "screen") { s ->
                when {
                    s >= 10 -> SettingsScreen(m)
                    s == 1 -> ServersScreen(m)
                    s == 2 -> StatsScreen(m)
                    else -> HomeScreen(m)
                }
            }
            // A soft fade under the floating bar keeps scrolled content from colliding with it.
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)) {
                FloatingNav(
                    listOf(NavItem(Ic.Home, L.t("Главная", "Home")), NavItem(Ic.Globe, L.t("Серверы", "Servers")), NavItem(Ic.Bars, L.t("Статистика", "Stats"))),
                    m.tab, m.inSettings, { m.inSettings = false; m.pages.clear(); m.tab = it }, { if (m.inSettings) { m.pages.clear() } else m.openSettings() })
            }
            Overlays(m)
        }
    }
}
