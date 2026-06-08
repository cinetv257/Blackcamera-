package com.example

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.compose.foundation.BorderStroke
import com.example.db.LutEntity
import com.example.lut.LutCameraRenderer
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.CameraViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val tags = "BlackCameraActivity"

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private lateinit var renderer: LutCameraRenderer
    private var glSurfaceView: GLSurfaceView? = null

    // Permission tracking state flow
    private val hasPermissionsFlow = MutableStateFlow(false)

    // Trigger document picking for .cube files
    private val pickLutLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            val vm = ViewModelProvider(this@MainActivity)[CameraViewModel::class.java]
            vm.importLutFromUri(selectedUri)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (cameraGranted && audioGranted) {
            hasPermissionsFlow.value = true
        } else {
            Toast.makeText(
                this,
                "L'application a besoin des permissions Caméra et Audio pour fonctionner.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Setup OpenGL Renderer
        renderer = LutCameraRenderer { surfaceTexture ->
            runOnUiThread {
                startCamera(surfaceTexture)
            }
        }

        // Check and request permissions
        checkOrPermissions()

        setContent {
            MyApplicationTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0A0A0A)
                ) {
                    val cameraViewModel: CameraViewModel = viewModel()
                    val hasPermissions by hasPermissionsFlow.collectAsStateWithLifecycle()

                    // Collect notifications inside visual layer
                    LaunchedEffect(Unit) {
                        cameraViewModel.eventToast.collectLatest { msg ->
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }

                    if (hasPermissions) {
                        CameraControlsScreen(
                            viewModel = cameraViewModel,
                            renderer = renderer,
                            onGlSurfaceViewCreated = { glView ->
                                glSurfaceView = glView
                            },
                            onImportRequest = {
                                pickLutLauncher.launch("*/*") // Support custom MIME types mapping to .cube
                            },
                            onExportRequest = { lut ->
                                shareLutFile(lut)
                            },
                            onTakeClick = { mode ->
                                if (mode == CaptureMode.PHOTO) {
                                    takePhoto(cameraViewModel)
                                } else {
                                    toggleVideoRecording(cameraViewModel)
                                }
                            }
                        )
                    } else {
                        PermissionsFallbackScreen {
                            checkOrPermissions()
                        }
                    }
                }
            }
        }
    }

    private fun checkOrPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsNeeded.isEmpty()) {
            hasPermissionsFlow.value = true
        } else {
            requestPermissionLauncher.launch(permissionsNeeded.toTypedArray())
        }
    }

    private fun startCamera(surfaceTexture: android.graphics.SurfaceTexture) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Preview configuration
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()

            preview.setSurfaceProvider { request ->
                surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                val surface = android.view.Surface(surfaceTexture)
                request.provideSurface(surface, ContextCompat.getMainExecutor(this)) {
                    surface.release()
                }
            }

            // Image Capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()

            // Video Capture setup with H.264 MP4
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture,
                    videoCapture
                )

                // Wire manual camera control parameters on startup
                observeManualControls()

            } catch (e: Exception) {
                Log.e(tags, "Camera standard binding failing", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun observeManualControls() {
        lifecycleScope.launch {
            val vm = ViewModelProvider(this@MainActivity)[CameraViewModel::class.java]

            // Apply reactive updates of manual settings directly onto the physical camera object
            launch {
                vm.selectedEv.collectLatest { ev ->
                    camera?.let {
                        val index = (ev * 2f).toInt() // index mapping depending on device max compensations
                        try {
                            it.cameraControl.setExposureCompensationIndex(index)
                        } catch (e: Exception) {
                            Log.w(tags, "EV change failed: ${e.message}")
                        }
                    }
                }
            }

            launch {
                vm.activeLut.collectLatest { lut ->
                    renderer.setLut(lut)
                }
            }

            launch {
                vm.lutEnabled.collectLatest { enabled ->
                    renderer.setLutEnabled(enabled)
                }
            }

            launch {
                vm.lutIntensity.collectLatest { intensity ->
                    renderer.setLutIntensity(intensity)
                }
            }

            launch {
                vm.highQualityMode.collectLatest { highQuality ->
                    renderer.setHighQuality(highQuality)
                }
            }

            // Wire low-level manual controls using CameraX's Camera2Interop
            launch {
                combine(
                    vm.selectedIso,
                    vm.selectedShutter,
                    vm.selectedFocus,
                    vm.selectedWb
                ) { iso, shutter, focus, wb ->
                    applyCamera2ManualKeys(iso, shutter, focus, wb)
                }.collectLatest { }
            }
        }
    }

    // Auxiliary method to compute low-level Android Camera2 API properties via interop
    private fun applyCamera2ManualKeys(iso: Int, shutterStr: String, focus: Float, wb: Int) {
        val cam = camera ?: return

        // 1/125 -> (1_000_000_000L / 125) nanoseconds
        val denom = try {
            shutterStr.substringAfter("/").toLong()
        } catch (e: Exception) {
            125L
        }
        val nanosecondExposureTime = 1_000_000_000L / denom

        // Standard Lens minimum Focus Distance diopter scale (0.0 = Infinity, 1.0 = Macro (e.g. 10.0 diopters maximum))
        val minFieldDiopters = focus * 10f

        val optionsBuilder = CaptureRequestOptions.Builder()
            // Set AE Mode to manual to allow custom ISO / Shutter duration overrides
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, nanosecondExposureTime)

            // Manual Focus configurations
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, minFieldDiopters)

            // Manual White Balance configurations (Kelvins gains control approximation)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)

        // Crude White Balance gains mappings across kelvin estimates
        val wbGains = estimateWbGains(wb)
        optionsBuilder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        optionsBuilder.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, wbGains)

        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            camera2Control.captureRequestOptions = optionsBuilder.build()
        } catch (e: Exception) {
            Log.e(tags, "Failed applying Camera2 interop parameters error: ${e.message}")
        }
    }

    private fun estimateWbGains(kelvin: Int): android.hardware.camera2.params.RggbChannelVector {
        // Approximate color gains mapping kelvin ratios (higher temp = colder, lower temp = warmer)
        val kRatio = kelvin / 1000f
        val rGain = if (kRatio < 5.5f) 1.0f + (5.5f - kRatio) * 0.4f else 1.0f
        val bGain = if (kRatio > 5.5f) 1.0f + (kRatio - 5.5f) * 0.4f else 1.0f
        return android.hardware.camera2.params.RggbChannelVector(rGain, 1.0f, 1.0f, bGain)
    }

    // Photograph Capture
    private fun takePhoto(viewModel: CameraViewModel) {
        val imageCap = imageCapture ?: return

        // Flash simulation screen animation
        viewModel.flashIndicator.value = true
        lifecycleScope.launch {
            delay(80)
            viewModel.flashIndicator.value = false
        }

        imageCap.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.capacity())
                    buffer.get(bytes)
                    image.close()

                    lifecycleScope.launch(Dispatchers.Default) {
                        try {
                            // Process JPEG buffer with software trilinear LUT filter
                            val processedBmp = viewModel.applyLutToCapturedPhoto(bytes)
                            if (processedBmp != null) {
                                saveBitmapToGallery(processedBmp)
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Erreur d'application de la LUT", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Echec de prise de photo: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val fileName = "BC_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BlackCamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                lifecycleScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Photo enregistrée ! $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                resolver.delete(uri, null, null)
            }
        }
    }

    // Video recording operations
    private fun toggleVideoRecording(viewModel: CameraViewModel) {
        if (viewModel.isRecording.value) {
            stopRecordingVideo()
        } else {
            startRecordingVideo(viewModel)
        }
    }

    private fun startRecordingVideo(viewModel: CameraViewModel) {
        val videoCap = videoCapture ?: return

        val fileName = "BC_${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BlackCamera")
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        try {
            activeRecording = videoCap.output
                .prepareRecording(this, mediaStoreOutput)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            viewModel.startRecordingTimer()
                        }
                        is VideoRecordEvent.Finalize -> {
                            viewModel.stopRecordingTimer()
                            if (!event.hasError()) {
                                Toast.makeText(this, "Vidéo enregistrée : $fileName", Toast.LENGTH_LONG).show()
                            } else {
                                Log.e(tags, "Video finalized with error: ${event.error}")
                                activeRecording?.close()
                                activeRecording = null
                                Toast.makeText(this, "Erreur d'enregistrement vidéo", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permissions audio manquantes", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRecordingVideo() {
        activeRecording?.let {
            it.stop()
            activeRecording = null
        }
    }

    // Sharing / Export LUT FileProvider trigger
    private fun shareLutFile(lut: LutEntity) {
        val file = File(lut.filePath)
        if (!file.exists()) {
            Toast.makeText(this, "Fichier d'origine introuvable.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "com.blackcamera.app.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Exporter la LUT .cube"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Impossible d'exporter la LUT : ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

// Visual Layout and widgets below

enum class CaptureMode { PHOTO, VIDEO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraControlsScreen(
    viewModel: CameraViewModel,
    renderer: LutCameraRenderer,
    onGlSurfaceViewCreated: (GLSurfaceView) -> Unit,
    onImportRequest: () -> Unit,
    onExportRequest: (LutEntity) -> Unit,
    onTakeClick: (CaptureMode) -> Unit
) {
    val context = LocalContext.current
    var captureMode by remember { mutableStateOf(CaptureMode.PHOTO) }
    var lateralExpanded by remember { mutableStateOf(false) }
    var showLutMenuSheet by remember { mutableStateOf(false) }

    // Collect States
    val batteryPct by viewModel.batteryPct.collectAsStateWithLifecycle()
    val clockTime by viewModel.currentTime.collectAsStateWithLifecycle()
    val activeLutName by viewModel.activeLutName.collectAsStateWithLifecycle()
    val lutEnabled by viewModel.lutEnabled.collectAsStateWithLifecycle()
    val lutIntensity by viewModel.lutIntensity.collectAsStateWithLifecycle()
    val highQuality by viewModel.highQualityMode.collectAsStateWithLifecycle()
    val recentLutsList by viewModel.recentLuts.collectAsStateWithLifecycle()
    val allLutsList by viewModel.allLuts.collectAsStateWithLifecycle()
    val flashFlash by viewModel.flashIndicator.collectAsStateWithLifecycle()

    // Recording States
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val recordingSeconds by viewModel.recordingSeconds.collectAsStateWithLifecycle()

    // Slide controls animations
    val density = androidx.compose.ui.platform.LocalDensity.current
    val systemTime = clockTime.ifEmpty { "00:00:00" }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020202))
    ) {
        // 1. OpenGL Camerax preview layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("camera_preview")
        ) {
            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(3)
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    }.also { glView ->
                        onGlSurfaceViewCreated(glView)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Dynamic grid overlays simulation
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stepW = size.width / 3
                val stepH = size.height / 3
                // Draw rules lines
                drawLine(Color(0x22FFFFFF), start = androidx.compose.ui.geometry.Offset(stepW, 0f), end = androidx.compose.ui.geometry.Offset(stepW, size.height), strokeWidth = 1f)
                drawLine(Color(0x22FFFFFF), start = androidx.compose.ui.geometry.Offset(stepW * 2, 0f), end = androidx.compose.ui.geometry.Offset(stepW * 2, size.height), strokeWidth = 1f)
                drawLine(Color(0x22FFFFFF), start = androidx.compose.ui.geometry.Offset(0f, stepH), end = androidx.compose.ui.geometry.Offset(size.width, stepH), strokeWidth = 1f)
                drawLine(Color(0x22FFFFFF), start = androidx.compose.ui.geometry.Offset(0f, stepH * 2), end = androidx.compose.ui.geometry.Offset(size.width, stepH * 2), strokeWidth = 1f)
            }
        }

        // Pulse record Overlay frame
        if (isRecording) {
            val infiniteTransition = rememberInfiniteTransition(label = "recordingPulse")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 0.8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "recPulse"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(4.dp, Color(0xFFFF3B30).copy(alpha = pulseAlpha))
            )
        }

        // Camera shutter Flash simulation feedback
        if (flashFlash) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
            )
        }

        // 2. HUD TOP METADATA BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color(0xD00A0A0A))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Clock & Recording Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRecording) {
                    val minutes = recordingSeconds / 60
                    val seconds = recordingSeconds % 60
                    val recStr = String.format("%02d:%02d", minutes, seconds)
                    
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "REC $recStr",
                        color = Color.Red,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Color.LightGray,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "STBY",
                        color = Color.Green,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = systemTime,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // LUT name and toggle pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF222222))
                    .clickable { viewModel.toggleLutEnabled() }
                    .border(
                        1.dp,
                        if (lutEnabled && activeLutName != null) Color(0xFFFF9F0A) else Color.Transparent,
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (lutEnabled && activeLutName != null) Color(0xFFFF9F0A) else Color.Gray)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (activeLutName != null) "LUT: $activeLutName" else "BYPASS (LOG)",
                    color = if (lutEnabled && activeLutName != null) Color(0xFFFF1A1) else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Battery and resolution settings
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "FHD 30",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(16.dp))
                Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = null,
                    tint = if (batteryPct < 20) Color.Red else Color.White,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "$batteryPct%",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 3. COLLAPSIBLE RIGHT MANUAL PANEL
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(top = 70.dp, bottom = 120.dp)
        ) {
            // Expansion trigger latch
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .background(Color(0xDF1E1E1E))
                    .clickable { lateralExpanded = !lateralExpanded }
                    .border(
                        1.dp,
                        if (lateralExpanded) Color(0xFFFF9F0A) else Color.Transparent,
                        RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (lateralExpanded) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                        contentDescription = "Toggle lateral",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "MAN",
                        color = Color(0xFFFF9F0A),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            AnimatedVisibility(
                visible = lateralExpanded,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(260.dp)
                        .background(Color(0xF2101010))
                        .border(1.dp, Color(0xFF262626))
                        .padding(16.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Text(
                                "MANUAL CAMERA CONTROLS",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        // ISO Setting
                        item {
                            val activeIso by viewModel.selectedIso.collectAsStateWithLifecycle()
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("ISO SENSITIVITY", color = Color.White, fontSize = 11.sp)
                                    Text(
                                        "ISO $activeIso",
                                        color = Color(0xFFFF9F0A),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(modifier = Modifier.height(48.dp)) {
                                    Slider(
                                        value = viewModel.isoList.indexOf(activeIso).toFloat(),
                                        onValueChange = { idx ->
                                            val accurateIdx = idx.roundToInt().coerceIn(0, viewModel.isoList.size -1)
                                            viewModel.setIso(viewModel.isoList[accurateIdx])
                                        },
                                        valueRange = 0f..(viewModel.isoList.size - 1).toFloat(),
                                        steps = viewModel.isoList.size - 2,
                                        colors = SliderDefaults.colors(
                                            activeTrackColor = Color(0xFFFF9F0A),
                                            thumbColor = Color(0xFFFF9F0A)
                                        )
                                    )
                                }
                            }
                        }

                        // Shutter Duration Setting
                        item {
                            val activeShutter by viewModel.selectedShutter.collectAsStateWithLifecycle()
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("SHUTTER SPEED", color = Color.White, fontSize = 11.sp)
                                    Text(
                                        activeShutter,
                                        color = Color(0xFFFF9F0A),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(modifier = Modifier.height(48.dp)) {
                                    Slider(
                                        value = viewModel.shutterList.indexOf(activeShutter).toFloat(),
                                        onValueChange = { idx ->
                                            val accurateIdx = idx.roundToInt().coerceIn(0, viewModel.shutterList.size -1)
                                            viewModel.setShutter(viewModel.shutterList[accurateIdx])
                                        },
                                        valueRange = 0f..(viewModel.shutterList.size - 1).toFloat(),
                                        steps = viewModel.shutterList.size - 2,
                                        colors = SliderDefaults.colors(
                                            activeTrackColor = Color(0xFFFF9F0A),
                                            thumbColor = Color(0xFFFF9F0A)
                                        )
                                    )
                                }
                            }
                        }

                        // Kelvin Balance Option
                        item {
                            val activeWb by viewModel.selectedWb.collectAsStateWithLifecycle()
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("COLOR BALANCE", color = Color.White, fontSize = 11.sp)
                                    Text(
                                        "${activeWb}K",
                                        color = Color(0xFFFF9F0A),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(modifier = Modifier.height(48.dp)) {
                                    Slider(
                                        value = activeWb.toFloat(),
                                        onValueChange = { viewModel.setWb(it.roundToInt()) },
                                        valueRange = 2000f..8000f,
                                        steps = 59, // 100K interval increments
                                        colors = SliderDefaults.colors(
                                            activeTrackColor = Color(0xFFFF9F0A),
                                            thumbColor = Color(0xFFFF9F0A)
                                        )
                                    )
                                }
                            }
                        }

                        // Focus settings
                        item {
                            val activeFocus by viewModel.selectedFocus.collectAsStateWithLifecycle()
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("FOCUS FOCUS", color = Color.White, fontSize = 11.sp)
                                    val focusText = when {
                                        activeFocus == 0.0f -> "0.0 (Infinity)"
                                        activeFocus == 1.0f -> "1.0 (Macro)"
                                        else -> String.format(Locale.US, "%.2f", activeFocus)
                                    }
                                    Text(
                                        focusText,
                                        color = Color(0xFFFF9F0A),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(modifier = Modifier.height(48.dp)) {
                                    Slider(
                                        value = activeFocus,
                                        onValueChange = { viewModel.setFocus(it) },
                                        valueRange = 0.0f..1.0f,
                                        colors = SliderDefaults.colors(
                                            activeTrackColor = Color(0xFFFF9F0A),
                                            thumbColor = Color(0xFFFF9F0A)
                                        )
                                    )
                                }
                            }
                        }

                        // Exposure setting EV
                        item {
                            val activeEv by viewModel.selectedEv.collectAsStateWithLifecycle()
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("EXPOSURE (EV)", color = Color.White, fontSize = 11.sp)
                                    val evText = if (activeEv > 0f) "+$activeEv" else "$activeEv"
                                    Text(
                                        evText,
                                        color = Color(0xFFFF9F0A),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(modifier = Modifier.height(48.dp)) {
                                    Slider(
                                        value = activeEv,
                                        onValueChange = { steps ->
                                            // Rounding to nearest 0.5 step
                                            val rounded = (steps * 2f).roundToInt() / 2f
                                            viewModel.setEv(rounded)
                                        },
                                        valueRange = -3.0f..3.0f,
                                        steps = 11, // Split into subdivisions of 0.5f keys
                                        colors = SliderDefaults.colors(
                                            activeTrackColor = Color(0xFFFF9F0A),
                                            thumbColor = Color(0xFFFF9F0A)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. BOTTOM ACTION CONTROL AREA
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xE0050505))
                .navigationBarsPadding()
                .padding(bottom = 12.dp, top = 8.dp)
        ) {
            // Live LUT intensity quick slider
            if (activeLutName != null && lutEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "LUT INTENSITY",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Slider(
                        value = lutIntensity,
                        onValueChange = { viewModel.setLutIntensity(it) },
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color(0xFFFF9F0A),
                            thumbColor = Color(0xFFFF9F0A)
                        )
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        "${(lutIntensity * 100).roundToInt()}%",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Standard capture trigger ring, mode switch toggles, and LUT hub manager menu button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: LUT catalog menu
                IconButton(
                    onClick = { showLutMenuSheet = true },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xEE222222), CircleShape)
                        .testTag("lut_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterFrames,
                        contentDescription = "LUT selection menu",
                        tint = Color.White
                    )
                }

                // Middle: Large Tactile trigger circular capture button
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .border(4.dp, Color.White, CircleShape)
                            .clickable { onTakeClick(captureMode) }
                            .padding(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(
                                    if (captureMode == CaptureMode.PHOTO) {
                                        Color.White
                                    } else {
                                        Color.Red
                                    }
                                )
                        )
                    }
                }

                // Right: Photo vs Video mode segment switch slider
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color(0xFF222222))
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(32.dp))
                            .background(if (captureMode == CaptureMode.PHOTO) Color(0xFFFF9F0A) else Color.Transparent)
                            .clickable { if (!isRecording) captureMode = CaptureMode.PHOTO }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "PHOTO",
                            color = if (captureMode == CaptureMode.PHOTO) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(32.dp))
                            .background(if (captureMode == CaptureMode.VIDEO) Color.Red else Color.Transparent)
                            .clickable { captureMode = CaptureMode.VIDEO }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "VIDEO",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 5. LUT CATALOG HUB MODAL SHEET
        if (showLutMenuSheet) {
            ModalBottomSheet(
                onDismissRequest = { showLutMenuSheet = false },
                containerColor = Color(0xFF0F0F0F),
                contentColor = Color.White,
                tonalElevation = 8.dp,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header title area
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "BlackCamera LUT Hub",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9F0A)
                        )
                        Button(
                            onClick = { onImportRequest() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF9F0A),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("import_lut_button")
                        ) {
                            Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("IMPORT LUT", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Quality controls Mode card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF181818)),
                        border = BorderStroke(1.dp, Color(0xFF262626))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("QUALITÉ DE FILTRAGE LUT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                Text(
                                    if (highQuality) "Trilinéaire (Meilleure Qualité)" else "Voisin le plus proche (Performance)",
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                            }
                            Switch(
                                checked = highQuality,
                                onCheckedChange = { viewModel.setHighQuality(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFFFF9F0A),
                                    checkedTrackColor = Color(0xFFFF9F0A).copy(alpha = 0.5f)
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "LUT CATALOG HISTORY",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (allLutsList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Aucune LUT importée.\nChargez des fichiers .cube pour commencer.",
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(allLutsList) { lut ->
                                val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(lut.timestamp))
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.selectLut(lut)
                                            showLutMenuSheet = false
                                        }
                                        .border(
                                            1.dp,
                                            if (activeLutName == lut.name) Color(0xFFFF9F0A) else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        ),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161616))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                lut.name,
                                                color = if (activeLutName == lut.name) Color(0xFFFF9F0A) else Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                "Dimensions : ${lut.size}³ | Date: $dateStr",
                                                color = Color.Gray,
                                                fontSize = 10.sp
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // Set default trigger
                                            IconButton(
                                                onClick = { viewModel.setDefaultLut(lut) }
                                            ) {
                                                Icon(
                                                    imageVector = if (lut.isDefault) Icons.Default.Star else Icons.Default.StarOutline,
                                                    contentDescription = "Set default",
                                                    tint = if (lut.isDefault) Color(0xFFFF9F0A) else Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            // Share trigger
                                            IconButton(
                                                onClick = { onExportRequest(lut) }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Share,
                                                    contentDescription = "Export LUT file",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            // Delete trigger
                                            IconButton(
                                                onClick = { viewModel.deleteLut(lut) }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Supprimer",
                                                    tint = Color.Red,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionsFallbackScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070707))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                tint = Color(0xFFFF9F0A),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "BlackCamera",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Veuillez autoriser l'accès à la caméra et au microphone pour démarrer le monitoring d'exposition professionnelle.",
                fontSize = 14.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9F0A), contentColor = Color.Black)
            ) {
                Text("ACCORDER LES PERMISSIONS", fontWeight = FontWeight.Bold)
            }
        }
    }
}
