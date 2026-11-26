package app.passman.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.passman.core.AuthenticatorExport
import app.passman.core.Limits
import app.passman.core.QrDecoder
import app.passman.core.Totp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * Full-screen scanner for a 2FA setup QR code, from the camera or from an image (a screenshot,
 * for when the code is on this phone's own screen). [onScanned] gets a value that fits the Code
 * field; QR codes that aren't TOTP setup codes are reported and scanning goes on.
 */
@Composable
fun QrScanDialog(onDismiss: () -> Unit, onScanned: (setupKey: String, fromImage: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCamera by remember { mutableStateOf(context.hasCameraPermission()) }
    var asked by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // A Google Authenticator export with several accounts, waiting for a pick.
    var export by remember { mutableStateOf<Pair<AuthenticatorExport, Boolean>?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }

    // A message about a wrong QR code clears itself so the hint comes back.
    LaunchedEffect(message) {
        if (message != null) {
            delay(3_000)
            message = null
        }
    }

    fun accept(text: String, fromImage: Boolean): Boolean {
        if (AuthenticatorExport.isExport(text)) {
            val parsed = AuthenticatorExport.parse(text)
            when {
                parsed == null -> message = "Couldn't read this Google Authenticator export"
                parsed.accounts.isEmpty() -> message = "None of the accounts in this export use time-based codes"
                parsed.accounts.size == 1 && parsed.batchSize == 1 -> {
                    onScanned(parsed.accounts[0].setupKey, fromImage)
                    return true
                }
                else -> {
                    export = parsed to fromImage
                    return true
                }
            }
            return false
        }
        val key = Totp.fitSetupKey(text, Limits.CODE)
        if (key == null) {
            message = "That QR code isn't a 2FA setup code"
            return false
        }
        onScanned(key, fromImage)
        return true
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.Default) { runCatching { decodeImage(context, uri) }.getOrNull() }
            busy = false
            if (text == null) message = "No QR code found in that image" else accept(text, fromImage = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                if (hasCamera && export == null) {
                    CameraScanner(onText = { accept(it, fromImage = false) }, modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(260.dp)
                            .border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(24.dp)),
                    )
                } else if (!hasCamera) {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (asked) "Camera access is off. Allow it to scan with the camera, or pick a screenshot of the QR code below. If nothing happens, turn on Camera for PassMan in the phone's Settings."
                            else "PassMan needs the camera to scan a QR code.",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    }
                }
                export?.let { (parsed, fromImage) ->
                    AccountPicker(
                        export = parsed,
                        onPick = { onScanned(it.setupKey, fromImage) },
                        onCancel = { export = null },
                    )
                }

                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    IconButton(onClick = onDismiss, modifier = Modifier.padding(8.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (message != null) MaterialTheme.colorScheme.errorContainer else Color.Black.copy(alpha = 0.6f),
                        ) {
                            Text(
                                message ?: if (busy) "Reading image…" else "Point the camera at the 2FA QR code",
                                color = if (message != null) MaterialTheme.colorScheme.onErrorContainer else Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                        FilledTonalButton(
                            enabled = !busy,
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        ) {
                            Icon(Icons.Outlined.Image, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Scan from image")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraScanner(onText: (String) -> Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val done = AtomicBoolean(false)
        // A wrong QR code stays in view for many frames; report it once, not every frame.
        var lastRejected: String? = null
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { image ->
            val text = image.use { if (done.get()) null else it.decodeQr() }
            if (text != null && text != lastRejected) {
                mainExecutor.execute {
                    if (!done.get()) {
                        if (currentOnText(text)) done.set(true) else lastRejected = text
                    }
                }
            }
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            provider = providerFuture.get().also {
                runCatching { it.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
            }
        }, mainExecutor)

        onDispose {
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** The Y (brightness) plane is all a QR reader needs. */
private fun ImageProxy.decodeQr(): String? {
    val plane = planes[0]
    val buffer = plane.buffer
    val luma = ByteArray(width * height)
    for (row in 0 until height) {
        buffer.position(row * plane.rowStride)
        buffer.get(luma, row * width, width)
    }
    return QrDecoder.decodeLuminance(luma, width, height).also { luma.fill(0) }
}

private fun decodeImage(context: Context, uri: Uri): String? {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > MAX_IMAGE_SIDE) decoder.setTargetSampleSize(ceil(longest / MAX_IMAGE_SIDE.toDouble()).toInt())
    }
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    bitmap.recycle()
    return QrDecoder.decodeArgb(pixels, w, h).also { pixels.fill(0) }
}

private const val MAX_IMAGE_SIDE = 3_000

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun AccountPicker(
    export: AuthenticatorExport,
    onPick: (app.passman.core.ExportedAccount) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Choose an account") },
        text = {
            Column {
                val notes = buildList {
                    if (export.batchSize > 1) add("QR code ${export.batchIndex + 1} of ${export.batchSize}. If the account isn't here, scan another of the export's codes.")
                    if (export.skipped > 0) add("${export.skipped} not shown: PassMan only supports time-based codes.")
                }
                notes.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(export.accounts) { account ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(account) }.padding(vertical = 12.dp)) {
                            Text(account.issuer.ifEmpty { account.name.ifEmpty { "Unnamed account" } }, fontWeight = FontWeight.SemiBold)
                            if (account.issuer.isNotEmpty() && account.name.isNotEmpty()) {
                                Text(account.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
