package com.tia.player
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

const val APP_VERSION = "1.0"
var BLUE by mutableStateOf(Color(0xFF1E6BFF))
val GLASS = Color(0xEE1A1B20)
object Queue { var items: List<Pair<String, String>> = emptyList() }   // (uri, nom) de la liste affichée

fun hms(ms: Long): String { val t = ms / 1000; return "%02d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60) }
fun ratioOf(w: Int, h: Int): String { val r = w.toFloat() / h
    return listOf("16:9" to 16f / 9, "4:3" to 4f / 3, "21:9" to 21f / 9, "1:1" to 1f, "9:16" to 9f / 16, "3:2" to 1.5f).firstOrNull { kotlin.math.abs(it.second - r) < .03f }?.first ?: "%.2f:1".format(r) }

class PlayerActivity : ComponentActivity() {
    private lateinit var lib: LibVLC
    private lateinit var mp: MediaPlayer
    private lateinit var pr: Prefs
    private var q = listOf<Pair<String, String>>()
    private var idx = 0
    private var loop = false
    private var pip = false
    private val title = mutableStateOf("")
    private val shuffle = mutableStateOf(false)
    private val inPip = mutableStateOf(false)
    private val themeIdx = mutableIntStateOf(0)
    private val cur get() = q[idx]

    private fun load(i: Int) {
        idx = i.coerceIn(q.indices); title.value = cur.second
        val m = Media(lib, Uri.parse(cur.first)); m.setHWDecoderEnabled(pr.b("hw", true), false)
        mp.media = m; m.release(); mp.play()
        val k = "pos_" + cur.first
        window.decorView.postDelayed({ val s = pr.p.getLong(k, 0L); if (pr.b("resume", true) && s > 5000) mp.setTime(s) }, 700)
    }
    private fun next() { if (shuffle.value) load(q.indices.random()) else if (idx < q.lastIndex) load(idx + 1) }
    private fun prev() { if (mp.time > 3000) mp.setTime(0) else load(idx - 1) }
    private fun share() {
        val u = Uri.parse(cur.first)
        if (u.scheme != "content") { Toast.makeText(this, "Partage indisponible pour ce fichier", Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    }
    private fun enterPip() { if (Build.VERSION.SDK_INT >= 26) enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        pr = Prefs(this)
        themeIdx.intValue = pr.i("theme", 0).coerceIn(0, THEMES.lastIndex)
        BLUE = Color(THEMES[themeIdx.intValue].acc)
        val tmp = intent.hasExtra("tmp")
        val useQ = !tmp && intent.hasExtra("idx") && Queue.items.isNotEmpty()
        q = if (useQ) Queue.items else listOf((intent.getStringExtra("uri") ?: intent.dataString ?: "") to (intent.getStringExtra("name") ?: "Vidéo"))
        idx = if (useQ) intent.getIntExtra("idx", 0).coerceIn(q.indices) else 0
        if (tmp) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (pr.b("keep", true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (pr.b("land", true)) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        lib = LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames", "--freetype-rel-fontsize=${intArrayOf(24, 18, 12)[pr.i("subsize", 1)]}"))
        mp = MediaPlayer(lib)
        mp.setEventListener { e -> if (e.type == MediaPlayer.Event.EndReached) runOnUiThread { if (loop) { mp.stop(); mp.play() } else if (pr.b("next", true)) next() } }
        val step = intArrayOf(5, 10, 15, 30)[pr.i("step", 1)] * 1000L
        val gest = pr.b("gest", true)

        setContent { Themed(themeIdx.intValue) {
            var show by remember { mutableStateOf(true) }; var playing by remember { mutableStateOf(true) }
            var pos by remember { mutableFloatStateOf(0f) }; var drag by remember { mutableStateOf(false) }
            var menu by remember { mutableStateOf("") }; var locked by remember { mutableStateOf(false) }
            var vol by remember { mutableFloatStateOf(100f) }; var res by remember { mutableStateOf("Auto") }; var ratio by remember { mutableStateOf("16:9") }
            var speed by remember { mutableFloatStateOf(floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)[pr.i("speed", 2)]) }
            var sub by remember { mutableStateOf("Désactivés") }; var loopUi by remember { mutableStateOf(false) }; var pipUi by remember { mutableStateOf(false) }
            var now by remember { mutableLongStateOf(0L) }; var len by remember { mutableLongStateOf(0L) }
            var fx by remember { mutableLongStateOf(0L) }; var fxOn by remember { mutableStateOf(false) }; var fxRight by remember { mutableStateOf(true) }
            val fxA by animateFloatAsState(if (fxOn) 1f else 0f, tween(250), label = "fx")
            val subPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { mp.addSlave(Media.Slave.Type.Subtitle, it, true); sub = "Fichier chargé" } }
            BackHandler(menu.isNotEmpty()) { menu = "" }
            LaunchedEffect(fx) { if (fx != 0L) { fxOn = true; delay(500); fxOn = false } }
            LaunchedEffect(Unit) {
                mp.rate = speed
                while (true) { delay(400); if (!drag) pos = mp.position; playing = mp.isPlaying; now = mp.time; len = mp.length
                    val t = runCatching { mp.currentVideoTrack }.getOrNull(); if (t != null && t.height > 0) { res = "${t.height}p"; ratio = ratioOf(t.width, t.height) } }
            }
            LaunchedEffect(show, menu, playing) { if (show && menu.isEmpty() && playing) { delay(3500); show = false } }

            Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                detectTapGestures(onTap = { if (menu.isNotEmpty()) menu = "" else show = !show },
                    onDoubleTap = { o -> if (gest && !locked) { val r = o.x > size.width / 2; mp.setTime(mp.time + if (r) step else -step); fxRight = r; fx = System.nanoTime() } })
            }) {
                AndroidView({ ctx -> VLCVideoLayout(ctx).also { l -> mp.attachViews(l, null, true, false); load(idx) } }, Modifier.fillMaxSize())
                Box(Modifier.align(if (fxRight) Alignment.CenterEnd else Alignment.CenterStart).size(150.dp).graphicsLayer { alpha = fxA; scaleX = .6f + .4f * fxA; scaleY = .6f + .4f * fxA }.clip(CircleShape).background(Color(0x33FFFFFF)), Alignment.Center) {
                    Text(if (fxRight) "⏩ ${step / 1000} s" else "⏪ ${step / 1000} s", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                if (locked) { if (show) IconButton({ locked = false }, Modifier.align(Alignment.CenterEnd).padding(16.dp)) { Icon(Icons.Default.Lock, "Déverrouiller", tint = Color.White) } }
                else {
                    val vis = show && !inPip.value
                    AnimatedVisibility(vis, Modifier.align(Alignment.TopCenter), enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                    // ── Barre du haut : retour, logo TIA Player, titre, verrou / CC / réglages / menu ──
                    Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ finish() }) { Icon(Icons.Default.ArrowBack, "Retour", tint = Color.White, modifier = Modifier.size(30.dp)) }
                        Icon(Icons.Default.PlayArrow, null, tint = BLUE, modifier = Modifier.size(46.dp))
                        Column { Text("TIA", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 18.sp)
                            Text("Player", color = BLUE, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 18.sp) }
                        Box(Modifier.padding(horizontal = 14.dp).width(1.dp).height(40.dp).background(Color(0x55FFFFFF)))
                        Column(Modifier.weight(1f)) { Text(title.value, color = Color.White, fontSize = 18.sp, maxLines = 1)
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("${res.uppercase()} • $ratio", color = Color(0xCCFFFFFF), fontSize = 14.sp); if (playing) Eq() } }
                        IconButton({ locked = true; show = true }) { Icon(Icons.Default.Lock, "Verrouiller", tint = Color.White, modifier = Modifier.size(28.dp)) }
                        IconButton({ if (sub == "Désactivés") { val t = mp.spuTracks?.firstOrNull { it.id >= 0 }; if (t != null) { mp.setSpuTrack(t.id); sub = t.name } else menu = "sub" } else { mp.setSpuTrack(-1); sub = "Désactivés" } }) { Icon(Icons.Default.Subtitles, "Sous-titres", tint = Color.White, modifier = Modifier.size(28.dp)) }
                        IconButton({ menu = "gear" }) { Icon(Icons.Default.Settings, "Réglages", tint = Color.White, modifier = Modifier.size(28.dp)) }
                        IconButton({ menu = if (menu == "main") "" else "main" }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White, modifier = Modifier.size(28.dp)) }
                    }
                    // ── Panneau de menu (qualité, vitesse, sous-titres, PiP, partager, répéter, à propos) ──
                    }
                    AnimatedVisibility(vis && menu == "main", Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 16.dp),
                        enter = fadeIn() + scaleIn(initialScale = .92f, transformOrigin = TransformOrigin(1f, 0f)), exit = fadeOut() + scaleOut(targetScale = .92f, transformOrigin = TransformOrigin(1f, 0f))) {
                    Column(Modifier.width(340.dp).clip(RoundedCornerShape(20.dp)).background(GLASS).verticalScroll(rememberScrollState())) {
                        MenuRow(Icons.Default.Hd, "Qualité", res) { menu = "quality" }
                        MenuRow(Icons.Default.Speed, "Vitesse de lecture", "${speed}x") { menu = "speed" }
                        MenuRow(Icons.Default.Subtitles, "Sous-titres", sub) { menu = "sub" }
                        MenuRow(Icons.Default.PictureInPictureAlt, "Mode image dans l’image", sw = pipUi) { pipUi = !pipUi; pip = pipUi; if (pipUi) { menu = ""; enterPip() } }
                        MenuRow(Icons.Default.Share, "Partager") { share() }
                        MenuRow(Icons.Default.Repeat, "Répéter", sw = loopUi) { loopUi = !loopUi; loop = loopUi }
                        MenuRow(Icons.Default.Info, "À propos") { menu = "about" }
                    }
                    // ── Barre du bas : progression, lecture, volume, liste ──
                    }
                    AnimatedVisibility(vis, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))).padding(16.dp, 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(hms(now), color = Color.White, fontSize = 18.sp)
                            Slider(pos, { pos = it; drag = true }, Modifier.weight(1f).padding(horizontal = 10.dp), onValueChangeFinished = { mp.setPosition(pos); drag = false },
                                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = BLUE, inactiveTrackColor = Color(0x44FFFFFF)))
                            Text(hms(len), color = Color.White, fontSize = 18.sp)
                            IconButton({ requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }) { Icon(Icons.Default.Fullscreen, "Plein écran", tint = Color.White, modifier = Modifier.size(32.dp)) }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Pill(Icons.Default.Shuffle, if (shuffle.value) BLUE else Color(0xFF1B1C22)) { shuffle.value = !shuffle.value }
                            Spacer(Modifier.weight(1f))
                            Round(Icons.Default.SkipPrevious, 56.dp, Color(0x66222226)) { prev() }
                            Spacer(Modifier.width(24.dp))
                            Round(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, 84.dp, BLUE, pulse = !playing) { if (mp.isPlaying) mp.pause() else mp.play() }
                            Spacer(Modifier.width(24.dp))
                            Round(Icons.Default.SkipNext, 56.dp, Color(0x66222226)) { next() }
                            Spacer(Modifier.weight(1f))
                            Icon(if (vol > 0) Icons.Default.VolumeUp else Icons.Default.VolumeOff, null, tint = Color.White, modifier = Modifier.size(30.dp))
                            Slider(vol, { vol = it; mp.setVolume(it.toInt()) }, Modifier.width(150.dp), valueRange = 0f..100f,
                                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = BLUE, inactiveTrackColor = Color(0x44FFFFFF)))
                            Spacer(Modifier.width(12.dp))
                            Pill(Icons.Default.PlaylistPlay, Color(0xFF1B1C22)) { menu = "list" }
                        }
                    } }
                }
            }
            val close = { menu = "" }
            when (menu) {
                "quality" -> { val t = mp.videoTracks?.toList() ?: emptyList(); Pick("Qualité", if (t.isEmpty()) listOf("Auto ($res)") else t.map { it.name }, { if (t.isNotEmpty()) mp.setVideoTrack(t[it].id) }, close) }
                "speed" -> { val v = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f); Pick("Vitesse de lecture", v.map { "${it}x" }, { mp.rate = v[it]; speed = v[it] }, close) }
                "sub" -> { val t = mp.spuTracks?.filter { it.id >= 0 } ?: emptyList()
                    Pick("Sous-titres", listOf("Désactivés") + t.map { it.name } + "Charger un fichier…", { i -> when { i == 0 -> { mp.setSpuTrack(-1); sub = "Désactivés" }; i <= t.size -> { mp.setSpuTrack(t[i - 1].id); sub = t[i - 1].name }; else -> subPick.launch(arrayOf("*/*")) } }, close) }
                "gear" -> Pick("Réglages", listOf("Piste audio", "Format de l’image", "Thème"), { menu = when (it) { 0 -> "audio"; 1 -> "ratio"; else -> "theme" } }, {})
                "theme" -> AlertDialog(close, confirmButton = { TextButton(close) { Text("OK") } }, title = { Text("Thème") }, text = {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(THEMES.size) { i ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { themeIdx.intValue = i; pr.put("theme", i); BLUE = Color(THEMES[i].acc) }) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(THEMES[i].acc)).border(if (themeIdx.intValue == i) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape))
                            Text(THEMES[i].name, style = MaterialTheme.typography.labelSmall) } } } })
                "audio" -> { val t = mp.audioTracks?.toList() ?: emptyList(); Pick("Piste audio", t.map { it.name }, { mp.setAudioTrack(t[it].id) }, close) }
                "ratio" -> Pick("Format de l’image", listOf("Auto", "16:9", "4:3", "Rempli"), { i -> when (i) { 1 -> mp.setAspectRatio("16:9"); 2 -> mp.setAspectRatio("4:3")
                    3 -> { mp.setAspectRatio(null); mp.videoScale = MediaPlayer.ScaleType.SURFACE_FILL }
                    else -> { mp.setAspectRatio(null); mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT } } }, close)
                "about" -> AboutDialog(close)
                "list" -> AlertDialog(close, confirmButton = { TextButton(close) { Text("Fermer") } }, title = { Text("Liste de lecture (${q.size})") }, text = {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) { itemsIndexed(q) { i, it -> TextButton({ load(i); menu = "" }) { Text(it.second, maxLines = 1, color = if (i == idx) BLUE else MaterialTheme.colorScheme.onSurface) } } } })
            }
        } }
    }
    override fun onUserLeaveHint() { if (pip) enterPip() }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) { super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig); inPip.value = isInPictureInPictureMode }
    override fun onPause() { super.onPause(); pr.put("pos_" + cur.first, if (mp.length - mp.time > 5000) mp.time else 0L); if (!inPip.value) mp.pause() }
    override fun onDestroy() { super.onDestroy(); intent.getStringExtra("tmp")?.let { java.io.File(it).delete() }; mp.stop(); mp.detachViews(); mp.release(); lib.release() }
}

@Composable fun MenuRow(icon: ImageVector, label: String, value: String? = null, sw: Boolean? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        Text(label, Modifier.weight(1f).padding(start = 16.dp), color = Color.White, fontSize = 16.sp)
        if (value != null) Text(value, color = Color.White, fontSize = 15.sp)
        if (sw != null) Switch(sw, { onClick() }, colors = SwitchDefaults.colors(checkedTrackColor = BLUE, checkedThumbColor = Color.White))
        else if (value != null) Icon(Icons.Default.ChevronRight, null, tint = Color.White)
    }
}
@Composable fun Round(icon: ImageVector, size: androidx.compose.ui.unit.Dp, bg: Color, pulse: Boolean = false, onClick: () -> Unit) {
    val sc by rememberInfiniteTransition(label = "pulse").animateFloat(1f, if (pulse) 1.12f else 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "s")
    Box(Modifier.size(size).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape).background(bg).clickable(onClick = onClick), Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * .5f)) }
}
@Composable fun Pill(icon: ImageVector, bg: Color, onClick: () -> Unit) {
    Surface(Modifier.size(84.dp, 58.dp).clickable(onClick = onClick), RoundedCornerShape(22.dp), bg, border = BorderStroke(1.dp, Color(0x44FFFFFF))) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp)) } }
}
@Composable fun Pick(title: String, items: List<String>, onPick: (Int) -> Unit, onClose: () -> Unit) {
    AlertDialog(onClose, confirmButton = {}, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) { items.forEachIndexed { i, s -> TextButton({ onPick(i); if (title != "Réglages") onClose() }) { Text(s) } } } })
}

@Composable fun AboutDialog(onClose: () -> Unit) {
    AlertDialog(onClose, confirmButton = { TextButton(onClose) { Text("Fermer") } },
        title = { Text("TIA PLAYER") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.tia_logo), "TIA PLAYER", Modifier.size(96.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(22.dp)))
            Text("Version $APP_VERSION", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text("Lecteur vidéo tous formats (MKV, AVI, MPEG, MP4…), qui fonctionne hors ligne, avec coffre privé chiffré AES-256.")
            Text("Vos vidéos restent sur votre téléphone : TIA PLAYER n’envoie aucune donnée.")
            Text("Moteur de lecture : libVLC © VideoLAN, distribué sous licence LGPL 2.1 ou ultérieure (videolan.org).", style = MaterialTheme.typography.bodySmall)
            Text("© 2026 TIA PLAYER. Tous droits réservés.", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        } })
}

@Composable fun Eq() {
    val t = rememberInfiniteTransition(label = "eq")
    Row(Modifier.padding(start = 8.dp).height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0..2) { val h by t.animateFloat(.2f, 1f, infiniteRepeatable(tween(500 + i * 150), RepeatMode.Reverse), label = "b$i")
            Box(Modifier.width(3.dp).fillMaxHeight(h).background(BLUE, RoundedCornerShape(2.dp))) }
    }
}
