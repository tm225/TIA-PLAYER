package com.tia.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.ThumbnailUtils
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class Vid(
    val id: Long,
    val name: String,
    val dur: Long,
    val size: Long,
    val folder: String,
    val added: Long,
    val path: String = ""
) {
    val uri get() = if (id >= 0) ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id) else Uri.fromFile(File(path))
}

class Prefs(c: Context) {
    val p = c.getSharedPreferences("tia", 0)

    fun b(k: String, d: Boolean) = p.getBoolean(k, d)
    fun i(k: String, d: Int) = p.getInt(k, d)

    fun put(k: String, v: Any) {
        val e = p.edit()
        when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is String -> e.putString(k, v)
            is Long -> e.putLong(k, v)
        }
        e.apply()
    }

    fun vault(): Set<String> = p.getStringSet("vault", emptySet())!!.toSet()
    fun setVault(s: Set<String>) = p.edit().putStringSet("vault", s).apply()

    private fun hash(pw: String, salt: ByteArray) =
        Base64.encodeToString(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(pw.toCharArray(), salt, 120000, 256))
                .encoded,
            Base64.NO_WRAP
        )

    fun hasPw() = p.contains("pwh")

    fun setPw(pw: String) {
        val s = ByteArray(16).also { SecureRandom().nextBytes(it) }
        put("salt", Base64.encodeToString(s, 0))
        put("pwh", hash(pw, s))
    }

    fun check(pw: String) =
        hash(pw, Base64.decode(p.getString("salt", ""), 0)) == p.getString("pwh", "")
}

class T(val name: String, val bg: Long, val surf: Long, val acc: Long, val dark: Boolean = true)

val THEMES = listOf(
    T("Minuit", 0xFF0B0C18, 0xFF151729, 0xFF7C5CFF),
    T("AMOLED", 0xFF000000, 0xFF0E0E0E, 0xFF22D3EE),
    T("Océan", 0xFF06141F, 0xFF0D2233, 0xFF38BDF8),
    T("Forêt", 0xFF08140D, 0xFF11261A, 0xFF4ADE80),
    T("Rubis", 0xFF160709, 0xFF2A0F14, 0xFFFB7185),
    T("Or", 0xFF14110A, 0xFF251F10, 0xFFF5B83D),
    T("Aurore", 0xFF0A0F1F, 0xFF16213D, 0xFF2DD4BF),
    T("Clair", 0xFFF3F2FA, 0xFFFFFFFF, 0xFF7C5CFF, false),
    T("Sakura", 0xFFFFF5F8, 0xFFFFFFFF, 0xFFE11D74, false)
)

@Composable
fun Themed(i: Int, content: @Composable () -> Unit) {
    val t = THEMES[i.coerceIn(0, THEMES.lastIndex)]
    val ink = if (t.dark) Color(0xFFEEF0FF) else Color(0xFF14152A)
    val cs = (if (t.dark) darkColorScheme() else lightColorScheme()).copy(
        background = Color(t.bg),
        surface = Color(t.surf),
        surfaceVariant = Color(t.surf),
        primary = Color(t.acc),
        onBackground = ink,
        onSurface = ink,
        onSurfaceVariant = ink.copy(alpha = .65f),
        onPrimary = Color.White
    )
    MaterialTheme(colorScheme = cs) {
        Surface(color = cs.background, content = content)
    }
}

val tick = mutableIntStateOf(0)

val EXT = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "3g2", "mpg", "mpeg", "mpe",
    "m4v", "ts", "m2ts", "mts", "vob", "divx", "ogv", "asf", "rm", "rmvb", "f4v", "mxf"
)

/**
 * 1) médiathèque Android (rapide, inclut la carte SD)
 * 2) parcours complet des dossiers si « accès à tous les fichiers » est accordé
 */
fun scan(c: Context): List<Vid> {
    val out = ArrayList<Vid>()
    val M = MediaStore.Video.Media
    val col = if (Build.VERSION.SDK_INT >= 29) M.getContentUri(MediaStore.VOLUME_EXTERNAL) else M.EXTERNAL_CONTENT_URI

    c.contentResolver.query(
        col,
        arrayOf(
            M._ID,
            M.DISPLAY_NAME,
            M.DURATION,
            M.SIZE,
            M.BUCKET_DISPLAY_NAME,
            M.DATE_ADDED,
            MediaStore.MediaColumns.DATA
        ),
        null,
        null,
        null
    )?.use {
        while (it.moveToNext()) {
            out += Vid(
                it.getLong(0),
                it.getString(1) ?: "?",
                it.getLong(2),
                it.getLong(3),
                it.getString(4) ?: "Stockage",
                it.getLong(5),
                it.getString(6) ?: ""
            )
        }
    }

    if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
        val known = out.map { it.path }.toHashSet()
        val roots = listOf(Environment.getExternalStorageDirectory()) +
            (File("/storage").listFiles()?.filter { it.name != "emulated" && it.name != "self" } ?: emptyList())

        for (r in roots) {
            runCatching {
                r.walkTopDown()
                    .onEnter { d ->
                        !d.path.endsWith("/Android/data") &&
                            !d.path.endsWith("/Android/obb") &&
                            d.name != ".thumbnails"
                    }
                    .filter { it.isFile && it.extension.lowercase() in EXT && it.length() > 100_000 && it.path !in known }
                    .forEach { f ->
                        known += f.path
                        out += Vid(
                            -(Math.abs(f.path.hashCode().toLong()) + 1),
                            f.name,
                            0,
                            f.length(),
                            f.parentFile?.name ?: "Stockage",
                            f.lastModified() / 1000,
                            f.path
                        )
                    }
            }
        }
    }

    return out
}

fun fmt(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = t % 3600 / 60
    val s = t % 60
    return (if (h > 0) "$h:%02d".format(m) else "$m") + ":%02d".format(s)
}

@Composable
fun Thumb(v: Vid, m: Modifier) {
    val c = LocalContext.current
    val bmp by produceState<Bitmap?>(null, v.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (v.id < 0) {
                    ThumbnailUtils.createVideoThumbnail(File(v.path), Size(320, 180), null)
                } else {
                    c.contentResolver.loadThumbnail(v.uri, Size(320, 180), null)
                }
            }.getOrNull()
        }
    }
    bmp?.let {
        Image(it.asImageBitmap(), null, m, contentScale = ContentScale.Crop)
    } ?: Box(m.background(Color(0x33888888)))
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val pr = Prefs(this)
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    tick.intValue++
                }
            }
        )
        setContent { App(pr) }
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoList(list: List<Vid>, onLong: (Vid) -> Unit) {
    val c = LocalContext.current
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("Aucune vidéo", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.id }) { v ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .combinedClickable(
                        onLongClick = { onLong(v) }
                    ) {
                        Queue.items = list.map { it.uri.toString() to it.name }
                        c.startActivity(
                            Intent(c, PlayerActivity::class.java)
                                .putExtra("uri", v.uri.toString())
                                .putExtra("name", v.name)
                                .putExtra("idx", list.indexOf(v))
                        )
                    }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Thumb(v, Modifier.size(120.dp, 68.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        v.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        (if (v.dur > 0) fmt(v.dur) + " · " else "") + "${v.size / 1048576} Mo · ${v.folder}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ToggleRow(pr: Prefs, k: String, label: String, d: Boolean) {
    var v by remember { mutableStateOf(pr.b(k, d)) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(v, { v = it; pr.put(k, it) })
    }
}

@Composable
fun ChoiceRow(pr: Prefs, k: String, label: String, opts: List<String>, d: Int, onChange: (Int) -> Unit = {}) {
    var v by remember { mutableIntStateOf(pr.i(k, d)) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(opts.size) { i ->
                FilterChip(
                    selected = v == i,
                    onClick = { v = i; pr.put(k, i); onChange(i) },
                    label = { Text(opts[i]) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun App(pr: Prefs) {
    val c = LocalContext.current
    var theme by remember { mutableIntStateOf(pr.i("theme", 0)) }
    var sort by remember { mutableIntStateOf(pr.i("sort", 1)) }
    var vids by remember { mutableStateOf(listOf<Vid>()) }
    var tab by remember { mutableIntStateOf(0) }
    var unlocked by remember { mutableStateOf(false) }
    var vitems by remember { mutableStateOf(listOf<VItem>()) }
    var vsel by remember { mutableStateOf<VItem?>(null) }
    var busy by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            vids = withContext(Dispatchers.IO) { scan(c) }
        }
    }

    val del = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload() }
    LaunchedEffect(unlocked) {
        if (unlocked) {
            vitems = withContext(Dispatchers.IO) { Vault.list(c) }
        }
    }

    var folder by remember { mutableStateOf<String?>(null) }
    var sel by remember { mutableStateOf<Vid?>(null) }
    var query by remember { mutableStateOf("") }
    var about by remember { mutableStateOf(false) }

    val perm = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) reload() }
    LaunchedEffect(Unit) { ask.launch(perm) }
    LaunchedEffect(tick.intValue) {
        delay(700)
        if (c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) reload()
    }

    val allFiles = remember(tick.intValue) {
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
    }

    fun sorted(l: List<Vid>) = when (sort) {
        0 -> l.sortedBy { it.name.lowercase() }
        1 -> l.sortedByDescending { it.added }
        2 -> l.sortedByDescending { it.size }
        else -> l.sortedByDescending { it.dur }
    }

    val pub = vids

    Themed(theme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(
                        "Vidéos" to Icons.Default.PlayArrow,
                        "Coffre" to Icons.Default.Lock,
                        "Réglages" to Icons.Default.Settings
                    ).forEachIndexed { i, (n, ic) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = {
                                tab = i
                                if (i != 1) unlocked = false
                            },
                            icon = { Icon(ic, n) },
                            label = { Text(n) }
                        )
                    }
                }
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> {
                        Row(
                            Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                painterResource(R.drawable.tia_logo),
                                contentDescription = null,
                                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                            )
                            Text(
                                "TIA PLAYER",
                                Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (!allFiles) {
                            Row(
                                Modifier
                                    .padding(12.dp, 4.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Autorisez l’accès à tous les fichiers pour afficher toutes vos vidéos (MKV, AVI, carte SD, dossiers cachés).",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton({
                                    c.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:${c.packageName}")
                                        )
                                    )
                                }) {
                                    Text("Autoriser")
                                }
                            }
                        }

                        OutlinedTextField(
                            query,
                            { query = it },
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            placeholder = { Text("Rechercher") },
                            singleLine = true
                        )

                        LazyRow(
                            contentPadding = PaddingValues(12.dp, 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                FilterChip(
                                    selected = folder == null,
                                    onClick = { folder = null },
                                    label = { Text("Tous (${pub.size})") }
                                )
                            }
                            items(pub.map { it.folder }.distinct().sorted()) { f ->
                                FilterChip(
                                    selected = folder == f,
                                    onClick = { folder = f },
                                    label = { Text(f) }
                                )
                            }
                        }

                        VideoList(
                            sorted(pub.filter { (folder == null || it.folder == folder) && it.name.contains(query, true) })
                        ) { sel = it }
                    }

                    1 -> if (!unlocked) {
                        Gate(pr) { unlocked = true }
                    } else {
                        Column {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Coffre chiffré", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                                TextButton({ Vault.lock(c); unlocked = false; tab = 0 }) {
                                    Text("Verrouiller")
                                }
                            }

                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                            if (vitems.isEmpty()) {
                                Text(
                                    "Coffre vide. Appui long sur une vidéo pour la chiffrer ici.",
                                    Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(vitems, key = { it.file }) { vi ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .combinedClickable(
                                                onLongClick = { vsel = vi }
                                            ) {
                                                scope.launch {
                                                    busy = true
                                                    val f = withContext(Dispatchers.IO) { Vault.open(c, vi) }
                                                    busy = false
                                                    c.startActivity(
                                                        Intent(c, PlayerActivity::class.java)
                                                            .putExtra("uri", Uri.fromFile(f).toString())
                                                            .putExtra("name", vi.name)
                                                            .putExtra("tmp", f.path)
                                                    )
                                                }
                                            }
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
                                        Column(Modifier.padding(start = 12.dp)) {
                                            Text(
                                                vi.name,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                "${vi.size / 1048576} Mo · AES-256",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                        item {
                            Text("Réglages", style = MaterialTheme.typography.headlineSmall)
                            Text("Thème", Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                items(THEMES.size) { i ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable {
                                            theme = i
                                            pr.put("theme", i)
                                        }
                                    ) {
                                        Box(
                                            Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(Color(THEMES[i].acc))
                                                .border(
                                                    if (theme == i) 3.dp else 0.dp,
                                                    MaterialTheme.colorScheme.onBackground,
                                                    1.dp
                                                )
                                        )
                                        Text(THEMES[i].name, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            Text("Bibliothèque", Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
                            ChoiceRow(pr, "sort", "Tri", listOf("Nom", "Date", "Taille", "Durée"), 1) { sort = it }

                            Button({ reload() }) { Text("Rescanner le téléphone") }

                            Text("Lecture", Modifier.padding(top = 16.dp), fontWeight = FontWeight.SemiBold)
                            ToggleRow(pr, "resume", "Reprendre là où je me suis arrêté", true)
                            ToggleRow(pr, "next", "Lire la vidéo suivante automatiquement", true)
                            ToggleRow(pr, "hw", "Décodage matériel", true)
                            ToggleRow(pr, "gest", "Gestes (double-tap, glisser)", true)
                            ToggleRow(pr, "keep", "Garder l’écran allumé", true)
                            ToggleRow(pr, "land", "Forcer le mode paysage", true)
                            ChoiceRow(pr, "step", "Saut du double-tap", listOf("5 s", "10 s", "15 s", "30 s"), 1)
                            ChoiceRow(pr, "speed", "Vitesse par défaut", listOf("0,5×", "0,75×", "1×", "1,25×", "1,5×", "2×"), 2)
                            ChoiceRow(pr, "ratio", "Format de l’image", listOf("Auto", "16:9", "4:3", "Rempli"), 0)
                            ChoiceRow(pr, "subsize", "Taille des sous-titres", listOf("Petit", "Moyen", "Grand"), 1)

                            Text("Coffre privé", Modifier.padding(top = 16.dp), fontWeight = FontWeight.SemiBold)
                            Text(
                                "Appui long sur une vidéo pour l’ajouter au coffre ou la retirer.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )

                            Text("À propos", Modifier.padding(top = 16.dp), fontWeight = FontWeight.SemiBold)
                            TextButton({ about = true }) { Text("TIA PLAYER · version $APP_VERSION") }
                            Text(
                                "© 2026 TIA PLAYER. Tous droits réservés.",
                                Modifier.padding(top = 4.dp, bottom = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        sel?.let { v ->
            AlertDialog(
                { sel = null },
                title = { Text(v.name, maxLines = 2) },
                text = {
                    Text(
                        if (Vault.key == null) {
                            "Ouvrez d’abord le coffre (onglet Coffre) pour pouvoir y chiffrer une vidéo."
                        } else {
                            "La vidéo sera chiffrée (AES-256) dans le coffre, puis déplacée hors du stockage principal."
                        }
                    )
                },
                confirmButton = {
                    if (Vault.key != null) {
                        TextButton({
                            sel = null
                            scope.launch {
                                busy = true
                                val ok = withContext(Dispatchers.IO) { Vault.add(c, v) }
                                busy = false
                                if (ok) {
                                    vitems = withContext(Dispatchers.IO) { Vault.list(c) }
                                    if (v.id < 0) {
                                        runCatching { File(v.path).delete() }
                                        reload()
                                    } else if (Build.VERSION.SDK_INT >= 30) {
                                        del.launch(
                                            IntentSenderRequest.Builder(
                                                MediaStore.createDeleteRequest(
                                                    c.contentResolver,
                                                    listOf(v.uri)
                                                )
                                            ).build()
                                        )
                                    } else {
                                        runCatching { c.contentResolver.delete(v.uri, null, null) }
                                        reload()
                                    }
                                }
                            }
                        }) {
                            Text("Chiffrer et déplacer")
                        }
                    }
                },
                dismissButton = { TextButton({ sel = null }) { Text("Annuler") } }
            )
        }

        if (about) AboutDialog { about = false }

        vsel?.let { x ->
            AlertDialog(
                { vsel = null },
                title = { Text(x.name, maxLines = 2) },
                text = { Text("Déchiffrer et restaurer dans la galerie ?") },
                confirmButton = {
                    TextButton({
                        vsel = null
                        scope.launch {
                            withContext(Dispatchers.IO) { Vault.restore(c, x) }
                            vitems = withContext(Dispatchers.IO) { Vault.list(c) }
                            reload()
                        }
                    })
                    { Text("Restaurer") }
                },
                dismissButton = { TextButton({ vsel = null }) { Text("Annuler") } }
            )
        }
    }
}

@Composable
fun Gate(pr: Prefs, ok: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    val create = !pr.hasPw()

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        Arrangement.Center,
        Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            if (create) "Créez votre mot de passe" else "Coffre verrouillé",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleLarge
        )

        OutlinedTextField(
            pw,
            { pw = it; err = "" },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            label = { Text("Mot de passe") },
            isError = err.isNotEmpty(),
            supportingText = { Text(err) }
        )

        Button(
            {
                if (create) {
                    if (pw.length < 4) {
                        err = "4 caractères minimum"
                    } else {
                        pr.setPw(pw)
                        Vault.key = Vault.derive(pr, pw)
                        ok()
                    }
                } else if (pr.check(pw)) {
                    Vault.key = Vault.derive(pr, pw)
                    ok()
                } else {
                    err = "Mot de passe incorrect"
                }
            },
            Modifier.padding(top = 12.dp)
        ) {
            Text(if (create) "Créer" else "Déverrouiller")
        }
    }
}
