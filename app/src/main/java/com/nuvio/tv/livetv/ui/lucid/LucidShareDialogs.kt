package com.nuvio.tv.livetv.ui.lucid

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.livetv.lucid.LucidShareCodec
import com.nuvio.tv.livetv.lucid.LucidShareServer
import com.nuvio.tv.livetv.lucid.LucidSpecCodec
import com.nuvio.tv.livetv.lucid.LucidStoredSpec
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveFocusRow
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.livetv.ui.focusedTextColor
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Share the custom Lucid channels as a QR code, a link or a portable `.lucid` file. */
@Composable
fun LucidShareDialog(
    specs: List<LucidStoredSpec>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val packJson = remember(specs) { LucidSpecCodec.encodePack(specs) }
    val code = remember(packJson) { runCatching { LucidShareCodec.encode(packJson) }.getOrNull() }
    val link = code?.let { "nuvio://lucid?pack=$it" }.orEmpty()
    val qr = remember(code) {
        if (code == null || code.length > 800) null
        else runCatching { QrCodeGenerator.generate(code, size = 640) }.getOrNull()
    }
    var notice by remember { mutableStateOf<String?>(null) }
    val server = remember { LucidShareServer() }
    var phoneSession by remember { mutableStateOf<LucidShareServer.Session?>(null) }
    DisposableEffect(Unit) { onDispose { server.stop() } }
    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(packJson.toByteArray(Charsets.UTF_8))
                    } ?: error("No file")
                }.isSuccess
            }
            notice = if (ok) "Saved the channel pack." else "Couldn't save that file."
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    LiveDialog(onDismiss = onDismiss, width = 700.dp, background = NuvioTheme.colors.BackgroundElevated) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LiveText("Share Lucid TV channels", size = 22.sp, weight = FontWeight.Bold)
            LiveText(
                "${specs.size} channel${if (specs.size == 1) "" else "s"} · " +
                    "${(packJson.length + 1023) / 1024} KB pack",
                color = NuvioTheme.colors.TextSecondary,
                size = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            val phoneQr = remember(phoneSession?.url) {
                phoneSession?.let { runCatching { QrCodeGenerator.generate(it.url, size = 480) }.getOrNull() }
            }
            if (phoneQr != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        bitmap = phoneQr.asImageBitmap(),
                        contentDescription = "Download this pack on your phone",
                        modifier = Modifier.size(210.dp),
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        LiveText("Open this on your phone", size = 16.sp, weight = FontWeight.Bold)
                        LiveText(
                            "Scan the QR, then download the .lucid pack. From there you can send it to another TV or share it in a message.",
                            color = NuvioTheme.colors.TextSecondary,
                            size = 14.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        LiveText(phoneSession?.url.orEmpty(), color = NuvioTheme.colors.TextPrimary, size = 13.sp, maxLines = 2)
                    }
                }
            } else if (qr != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "Lucid TV share code",
                        modifier = Modifier.size(230.dp),
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        LiveText(
                            "Scan this with your phone camera. It contains the share code, ready to paste or send to another TV.",
                            color = NuvioTheme.colors.TextSecondary,
                            size = 14.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        LiveText(
                            "On another TV: Import → paste the code.",
                            color = NuvioTheme.colors.TextSecondary,
                            size = 13.sp,
                        )
                    }
                }
            } else {
                LiveText(
                    "This pack is too large for a QR code. Copy the link/code, save a .lucid file, or use Send to phone.",
                    color = NuvioTheme.colors.Warning,
                    size = 14.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveFocusRow(
                    modifier = Modifier.focusRequester(first),
                    onClick = {
                        code?.let { clipboard.setText(AnnotatedString(it)) }
                        notice = if (code == null) "Couldn't make a share code." else "Code copied."
                    },
                ) { f -> LiveText("Copy code", color = focusedTextColor(f)) }
                LiveFocusRow(onClick = {
                    if (link.isNotBlank()) clipboard.setText(AnnotatedString(link))
                    notice = if (link.isBlank()) "Couldn't make a link." else "Link copied."
                }) { f -> LiveText("Copy link", color = focusedTextColor(f)) }
                LiveFocusRow(onClick = {
                    phoneSession = server.startDownload(packJson)
                    notice = if (phoneSession == null) {
                        "Connect this TV to Wi-Fi, then try again."
                    } else {
                        "Open the QR on a phone on the same Wi-Fi to download the pack."
                    }
                }) { f -> LiveText("Send to phone", color = focusedTextColor(f)) }
                LiveFocusRow(onClick = {
                    val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                    runCatching { saveFile.launch("lucid-tv-$date.lucid") }
                }) { f -> LiveText("Save file", color = focusedTextColor(f)) }
                LiveFocusRow(onClick = onDismiss) { f -> LiveText("Close", color = focusedTextColor(f)) }
            }
            notice?.let {
                Spacer(Modifier.height(10.dp))
                LiveText(it, color = NuvioTheme.colors.TextSecondary, size = 13.sp)
            }
        }
    }
}

/** Import a pack from the clipboard, a file, or a phone on the same Wi-Fi. */
@Composable
fun LucidImportDialog(
    importText: suspend (String) -> Result<Int>,
    onImported: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val server = remember { LucidShareServer() }
    val first = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<LucidShareServer.Session?>(null) }
    var received by remember { mutableStateOf<String?>(null) }

    fun runImport(raw: String) {
        if (raw.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            importText(raw)
                .onSuccess { count -> onImported(count) }
                .onFailure { error = it.message ?: "That pack couldn't be imported." }
            busy = false
        }
    }

    DisposableEffect(Unit) { onDispose { server.stop() } }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            busy = false
            if (raw.isNullOrBlank()) error = "Couldn't read that file." else runImport(raw)
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    LiveDialog(onDismiss = onDismiss, width = 720.dp, background = NuvioTheme.colors.BackgroundElevated) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LiveText("Import Lucid TV channels", size = 22.sp, weight = FontWeight.Bold)
            LiveText(
                "Paste a share code or link, open a .lucid file, or send from a phone on the same Wi-Fi.",
                color = NuvioTheme.colors.TextSecondary,
                size = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            LiveTextField(
                value = text,
                onValueChange = { text = it; error = null },
                hint = "lucid1:… or nuvio://lucid?pack=…",
                focusRequester = first,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveFocusRow(onClick = {
                    clipboard.getText()?.text?.trim()?.let {
                        text = it
                        error = null
                        runImport(it)
                    } ?: run { error = "The clipboard is empty." }
                }) { f -> LiveText("Paste & import", color = focusedTextColor(f)) }
                LiveFocusRow(onClick = { runImport(text) }) { f ->
                    LiveText(if (busy) "Importing…" else "Import", color = focusedTextColor(f))
                }
                LiveFocusRow(onClick = {
                    runCatching { openFile.launch(arrayOf("application/json", "text/plain", "*/*")) }
                }) { f -> LiveText("Open file", color = focusedTextColor(f)) }
                LiveFocusRow(onClick = {
                    session = server.start { body ->
                        Handler(Looper.getMainLooper()).post {
                            received = body
                            text = body
                            runImport(body)
                        }
                    }
                    if (session == null) error = "Connect this TV to Wi-Fi, then try again."
                }) { f -> LiveText("From phone", color = focusedTextColor(f)) }
                LiveFocusRow(onClick = onDismiss) { f -> LiveText("Close", color = focusedTextColor(f)) }
            }
            session?.let { s ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    runCatching { QrCodeGenerator.generate(s.url, size = 320) }.getOrNull()?.let { qr ->
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "Send from phone",
                            modifier = Modifier.size(180.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        LiveText("Send from your phone", size = 16.sp, weight = FontWeight.Bold)
                        LiveText("Scan the QR, or open this address on a phone on the same Wi-Fi:", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                        LiveText(s.url, color = NuvioTheme.colors.TextPrimary, size = 14.sp, maxLines = 2)
                        LiveText("PIN ${s.pin} · waiting for a pack…", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
                    }
                }
            }
            if (received != null) {
                Spacer(Modifier.height(6.dp))
                LiveText("Pack received. Importing…", color = NuvioTheme.colors.Success, size = 13.sp)
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                LiveText(it, color = NuvioTheme.colors.Warning, size = 13.sp)
            }
        }
    }
}
