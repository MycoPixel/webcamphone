package com.mycopixel.webcamphone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.mycopixel.webcamphone.ui.theme.WebcamPhoneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val webcamServer = WebcamServer(8080)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Log.w("MainActivity", "Camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            WebcamPhoneTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebcamMainScreen(
                        webcamServer = webcamServer,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webcamServer.stop()
    }
}

enum class CameraResolution(val label: String, val size: Size) {
    RES_480P("480p (640x480)", Size(640, 480)),
    RES_720P("720p (1280x720)", Size(1280, 720)),
    RES_1080P("1080p (1920x1080)", Size(1920, 1080))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebcamMainScreen(webcamServer: WebcamServer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var isRunning by remember { mutableStateOf(webcamServer.running) }
    var clientCount by remember { mutableStateOf(webcamServer.connectedClients) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var isTorchOn by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }

    // Advanced settings state
    var selectedResolution by remember { mutableStateOf(CameraResolution.RES_720P) }
    var brightness by remember { mutableStateOf(0f) } // -100 to 100
    var contrast by remember { mutableStateOf(1.0f) } // 0.5 to 2.0
    var isGrayscale by remember { mutableStateOf(false) }
    var isMirrored by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val ipAddress = remember { getLocalIpAddress(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        while (true) {
            isRunning = webcamServer.running
            clientCount = webcamServer.connectedClients
            delay(1000)
        }
    }

    // Re-bind camera when resolution or lensFacing changes
    LaunchedEffect(lensFacing, selectedResolution) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        selectedResolution.size,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                    )
                )
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (webcamServer.running) {
                    val jpegBytes = imageProxyToJpegBytes(
                        image = imageProxy,
                        brightness = brightness,
                        contrast = contrast,
                        grayscale = isGrayscale,
                        mirror = isMirrored
                    )
                    if (jpegBytes != null) {
                        webcamServer.updateFrame(jpegBytes)
                    }
                }
                imageProxy.close()
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                cameraControl = camera.cameraControl
                camera.cameraControl.enableTorch(isTorchOn)
            } catch (e: Exception) {
                Log.e("MainActivity", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Title & Settings Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "WebcamPhone",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = { showSettings = !showSettings }) {
                Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRunning) "Status: STREAMING" else "Status: STOPPED",
                        fontWeight = FontWeight.Bold,
                        color = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Badge(
                        containerColor = if (isRunning) Color.Green else Color.Gray
                    ) {
                        Text(text = if (isRunning) "LIVE" else "OFF", color = Color.White, modifier = Modifier.padding(4.dp))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Wi-Fi URL: http://$ipAddress:8080", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Connected Clients: $clientCount", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Advanced Settings Panel (Collapsible)
        AnimatedVisibility(visible = showSettings) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Camera & Image Settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Resolution Selector
                    Text(text = "Resolution", style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CameraResolution.values().forEach { res ->
                            FilterChip(
                                selected = selectedResolution == res,
                                onClick = { selectedResolution = res },
                                label = { Text(res.name) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    // Brightness Slider
                    Text(text = "Brightness: ${brightness.toInt()}", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = brightness,
                        onValueChange = { brightness = it },
                        valueRange = -100f..100f
                    )

                    // Contrast Slider
                    Text(text = "Contrast: %.1fx".format(contrast), style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = contrast,
                        onValueChange = { contrast = it },
                        valueRange = 0.5f..2.0f
                    )

                    // Toggles (Grayscale & Mirror)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isGrayscale, onCheckedChange = { isGrayscale = it })
                            Text(text = "Grayscale", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isMirrored, onCheckedChange = { isMirrored = it })
                            Text(text = "Mirror Horizontal", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Camera Preview Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Control Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    if (isRunning) {
                        webcamServer.stop()
                    } else {
                        webcamServer.start(scope)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = if (isRunning) "Stop Stream" else "Start Stream")
            }

            IconButton(
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Switch Camera")
            }

            IconButton(
                onClick = {
                    isTorchOn = !isTorchOn
                    cameraControl?.enableTorch(isTorchOn)
                },
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            ) {
                Icon(
                    imageVector = if (isTorchOn) Icons.Default.Star else Icons.Default.Info,
                    contentDescription = "Torch"
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Connection Instructions Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "PC Connection Instructions",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "1. USB Connection (Recommended, Zero Lag):\n" +
                            "   Run in your PC terminal:\n" +
                            "   `adb forward tcp:8080 tcp:8080`\n" +
                            "   Then open: http://localhost:8080",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "2. Wi-Fi Connection:\n" +
                            "   Ensure PC and phone are on the same Wi-Fi.\n" +
                            "   Open in Browser, VLC, or OBS: http://$ipAddress:8080",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

fun getLocalIpAddress(context: Context): String {
    try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ipAddress = wifiManager?.connectionInfo?.ipAddress ?: 0
        if (ipAddress != 0) {
            return String.format(
                "%d.%d.%d.%d",
                (ipAddress and 0xff),
                (ipAddress shr 8 and 0xff),
                (ipAddress shr 16 and 0xff),
                (ipAddress shr 24 and 0xff)
            )
        }
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (!addr.isLoopbackAddress && addr is InetAddress && addr.hostAddress?.indexOf(':') == -1) {
                    return addr.hostAddress ?: "127.0.0.1"
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "127.0.0.1"
}

fun imageProxyToJpegBytes(
    image: ImageProxy,
    brightness: Float,
    contrast: Float,
    grayscale: Boolean,
    mirror: Boolean
): ByteArray? {
    return try {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        val rowData = ByteArray(rowStride)
        val pixels = IntArray(width * height)
        var offset = 0
        for (r in 0 until height) {
            buffer.get(rowData, 0, rowStride)
            for (c in 0 until width) {
                val idx = c * pixelStride
                val rVal = rowData[idx].toInt() and 0xFF
                val gVal = rowData[idx + 1].toInt() and 0xFF
                val bVal = rowData[idx + 2].toInt() and 0xFF
                val aVal = rowData[idx + 3].toInt() and 0xFF
                pixels[offset++] = (aVal shl 24) or (rVal shl 16) or (gVal shl 8) or bVal
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        // Apply processing (brightness, contrast, grayscale, mirror)
        val processedBitmap = processBitmap(bitmap, brightness, contrast, grayscale, mirror)
        if (processedBitmap != bitmap) {
            bitmap.recycle()
        }

        val out = ByteArrayOutputStream()
        processedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
        processedBitmap.recycle()
        out.toByteArray()
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to convert and process image", e)
        null
    }
}

fun processBitmap(source: Bitmap, brightness: Float, contrast: Float, grayscale: Boolean, mirror: Boolean): Bitmap {
    val matrix = android.graphics.Matrix()
    if (mirror) {
        matrix.postScale(-1f, 1f, source.width / 2f, source.height / 2f)
    }

    val bmp = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint()

    val colorMatrix = ColorMatrix()

    // Contrast
    val scale = contrast
    val translate = (-0.5f * scale + 0.5f) * 255f
    val contrastMatrix = ColorMatrix(floatArrayOf(
        scale, 0f, 0f, 0f, translate,
        0f, scale, 0f, 0f, translate,
        0f, 0f, scale, 0f, translate,
        0f, 0f, 0f, 1f, 0f
    ))
    colorMatrix.postConcat(contrastMatrix)

    // Brightness
    if (brightness != 0f) {
        val brightnessMatrix = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, brightness,
            0f, 1f, 0f, 0f, brightness,
            0f, 0f, 1f, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))
        colorMatrix.postConcat(brightnessMatrix)
    }

    // Grayscale
    if (grayscale) {
        val grayMatrix = ColorMatrix()
        grayMatrix.setSaturation(0f)
        colorMatrix.postConcat(grayMatrix)
    }

    paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
    canvas.drawBitmap(source, matrix, paint)
    return bmp
}
