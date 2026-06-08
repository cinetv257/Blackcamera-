package com.example.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.db.LutEntity
import com.example.db.LutRepository
import com.example.lut.LUT3D
import com.example.lut.LUTParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LutRepository(application)

    // Manual controls options
    val isoList = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
    val shutterList = listOf("1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125", "1/60", "1/30")

    // State flows for manual camera controls
    val selectedIso = MutableStateFlow(400)
    val selectedShutter = MutableStateFlow("1/125")
    val selectedWb = MutableStateFlow(5600) // 2000K to 8000K
    val selectedFocus = MutableStateFlow(0.0f) // 0.0f to 1.0f (Infinity to Macro)
    val selectedEv = MutableStateFlow(0.0f) // -3.0f to +3.0f

    // Live preferences state flows from DataStore
    val lutEnabled = repository.lutEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val lutIntensity = repository.lutIntensity.stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)
    val highQualityMode = repository.highQualityInterpolation.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // LUT active parsing
    private val _activeLut = MutableStateFlow<LUT3D?>(null)
    val activeLut: StateFlow<LUT3D?> = _activeLut.asStateFlow()

    private val _activeLutName = MutableStateFlow<String?>(null)
    val activeLutName: StateFlow<String?> = _activeLutName.asStateFlow()

    // Database listing and history
    val allLuts: StateFlow<List<LutEntity>> = repository.allLuts.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val recentLuts: StateFlow<List<LutEntity>> = repository.recentLuts.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // UI and System Info Overlay States
    val batteryPct = MutableStateFlow(100)
    val currentTime = MutableStateFlow("")
    val currentResolution = MutableStateFlow("1920x1080")

    // Recording States
    val isRecording = MutableStateFlow(false)
    val recordingSeconds = MutableStateFlow(0)
    private var timerJob: Job? = null

    // Message events
    private val _eventToast = MutableSharedFlow<String>()
    val eventToast = _eventToast.asSharedFlow()

    // Animation flash state on capture
    val flashIndicator = MutableStateFlow(false)

    // Horizon stabilizer states
    val deviceRoll = MutableStateFlow(0f)
    val devicePitch = MutableStateFlow(0f)
    private var sensorManager: android.hardware.SensorManager? = null
    private var sensorListener: android.hardware.SensorEventListener? = null

    // Grid states: 0 = Off, 1 = 3x3, 2 = Target Crosshair, 3 = Golden Ratio
    val activeGridType = MutableStateFlow(1)

    // Gallery states
    val capturedMediaList = MutableStateFlow<List<CapturedMedia>>(emptyList())

    init {
        // Start live system clocks and battery tracking
        startClockAndBatteryTriggers()
        startHorizonSensor()

        // Load the last imported or active LUT from preferences
        viewModelScope.launch {
            repository.lastLutPath.collect { path ->
                if (path != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            val file = File(path)
                            if (file.exists()) {
                                val parsed = file.inputStream().use {
                                    LUTParser.parse(it, file.name.replace(".cube", ""))
                                }
                                _activeLut.value = parsed
                                _activeLutName.value = parsed.title ?: file.name.replace(".cube", "")
                            } else {
                                _activeLut.value = null
                                _activeLutName.value = null
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            _activeLut.value = null
                            _activeLutName.value = null
                        }
                    }
                } else {
                    _activeLut.value = null
                    _activeLutName.value = null
                }
            }
        }
    }

    private fun startClockAndBatteryTriggers() {
        // Clock tick
        viewModelScope.launch {
            val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            while (true) {
                currentTime.value = format.format(Date())
                delay(1000)
            }
        }

        // Battery setup
        getApplication<Application>().registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level != -1 && scale != -1) {
                        batteryPct.value = (level * 100 / scale.toFloat()).toInt()
                    }
                }
            }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    // Toggle states
    fun toggleLutEnabled() {
        viewModelScope.launch {
            repository.saveLutEnabled(!lutEnabled.value)
        }
    }

    fun setLutIntensity(intensity: Float) {
        viewModelScope.launch {
            repository.saveLutIntensity(intensity)
        }
    }

    fun setHighQuality(high: Boolean) {
        viewModelScope.launch {
            repository.saveHighQualityInterpolation(high)
        }
    }

    // Camera settings updates
    fun setIso(iso: Int) {
        selectedIso.value = iso
    }

    fun setShutter(shutter: String) {
        selectedShutter.value = shutter
    }

    fun setWb(wb: Int) {
        selectedWb.value = wb
    }

    fun setFocus(focus: Float) {
        selectedFocus.value = focus
    }

    fun setEv(ev: Float) {
        selectedEv.value = ev
    }

    // LUT management operations
    fun selectLut(lut: LutEntity) {
        viewModelScope.launch {
            try {
                if (File(lut.filePath).exists()) {
                    repository.saveLastLutPath(lut.filePath)
                    showToast("LUT '${lut.name}' activée")
                } else {
                    showToast("Le fichier de la LUT n'existe plus.")
                    repository.deleteLut(lut)
                }
            } catch (e: Exception) {
                showToast("Erreur de sélection de LUT")
            }
        }
    }

    fun deleteLut(lut: LutEntity) {
        viewModelScope.launch {
            repository.deleteLut(lut)
            // If deleting the current active one, reset
            repository.lastLutPath.firstOrNull()?.let { currentPath ->
                if (currentPath == lut.filePath) {
                    repository.saveLastLutPath(null)
                    _activeLut.value = null
                    _activeLutName.value = null
                }
            }
            showToast("LUT '${lut.name}' supprimée.")
        }
    }

    fun setDefaultLut(lut: LutEntity) {
        viewModelScope.launch {
            repository.setDefaultLut(lut.id)
            showToast("LUT '${lut.name}' définie par défaut.")
        }
    }

    // Parsing and copying imported .cube file
    fun importLutFromUri(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val contentResolver = getApplication<Application>().contentResolver
                val origName = getFileName(uri) ?: "lut_${System.currentTimeMillis()}.cube"
                
                val parsed = withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(uri) ?: throw Exception("Impossible d'ouvrir le fichier.")
                    stream.use { LUTParser.parse(it, origName.replace(".cube", "")) }
                }

                // Copy to local app luts folder
                val lutsDir = File(getApplication<Application>().getExternalFilesDir("luts"), "")
                if (!lutsDir.exists()) {
                    lutsDir.mkdirs()
                }

                val safeFileName = "lut_" + System.currentTimeMillis() + "_" + origName
                val destFile = File(lutsDir, safeFileName)

                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                // Add to Room DB
                val entity = LutEntity(
                    name = parsed.title ?: origName.replace(".cube", ""),
                    filePath = destFile.absolutePath,
                    size = parsed.size,
                    timestamp = System.currentTimeMillis()
                )
                repository.insertLut(entity)

                // Select current LUT automagically
                repository.saveLastLutPath(destFile.absolutePath)
                _activeLut.value = parsed
                _activeLutName.value = parsed.title ?: origName.replace(".cube", "")

                showToast("LUT '${parsed.title ?: origName}' importée successfully!")
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("Erreur d'importation : ${e.message}")
            }
        }
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) {
                        result = cursor.getString(idx)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result
    }

    // Timer recording loop
    fun startRecordingTimer() {
        isRecording.value = true
        recordingSeconds.value = 0
        timerJob = viewModelScope.launch {
            while (isRecording.value) {
                delay(1000)
                recordingSeconds.value++
            }
        }
    }

    fun stopRecordingTimer() {
        isRecording.value = false
        timerJob?.cancel()
        timerJob = null
    }

    // Software Application of a LUT to captured photo
    suspend fun applyLutToCapturedPhoto(inputBytes: ByteArray): Bitmap? = withContext(Dispatchers.Default) {
        val originalBitmap = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size) ?: return@withContext null
        val lut = _activeLut.value
        val enabled = lutEnabled.value

        if (!enabled || lut == null) {
            return@withContext originalBitmap
        }

        val lutData = lut.data
        val lutSize = lut.size
        val maxIndex = lutSize - 1

        val width = originalBitmap.width
        val height = originalBitmap.height
        val pixels = IntArray(width * height)
        originalBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val intensity = lutIntensity.value

        for (i in pixels.indices) {
            val color = pixels[i]
            val orgR = ((color shr 16) and 0xFF) / 255.0f
            val orgG = ((color shr 8) and 0xFF) / 255.0f
            val orgB = (color and 0xFF) / 255.0f

            // Trilinear interpolation on 3D LUT
            val rScale = orgR * maxIndex
            val gScale = orgG * maxIndex
            val bScale = orgB * maxIndex

            val r0 = rScale.toInt().coerceIn(0, maxIndex)
            val g0 = gScale.toInt().coerceIn(0, maxIndex)
            val b0 = bScale.toInt().coerceIn(0, maxIndex)

            val r1 = if (r0 < maxIndex) r0 + 1 else r0
            val g1 = if (g0 < maxIndex) g0 + 1 else g0
            val b1 = if (b0 < maxIndex) b0 + 1 else b0

            val dr = rScale - r0
            val dg = gScale - g0
            val db = bScale - b0

            // Faster index computations directly
            val i000 = (b0 * lutSize * lutSize + g0 * lutSize + r0) * 3
            val i100 = (b0 * lutSize * lutSize + g0 * lutSize + r1) * 3
            val i010 = (b0 * lutSize * lutSize + g1 * lutSize + r0) * 3
            val i110 = (b0 * lutSize * lutSize + g1 * lutSize + r1) * 3
            val i001 = (b1 * lutSize * lutSize + g0 * lutSize + r0) * 3
            val i101 = (b1 * lutSize * lutSize + g0 * lutSize + r1) * 3
            val i011 = (b1 * lutSize * lutSize + g1 * lutSize + r0) * 3
            val i111 = (b1 * lutSize * lutSize + g1 * lutSize + r1) * 3

            var finalR = orgR
            var finalG = orgG
            var finalB = orgB

            // Red Channel (Index + 0)
            val r_c000 = lutData[i000]
            val r_c100 = lutData[i100]
            val r_c010 = lutData[i010]
            val r_c110 = lutData[i110]
            val r_c001 = lutData[i001]
            val r_c101 = lutData[i101]
            val r_c011 = lutData[i011]
            val r_c111 = lutData[i111]

            val r_c00 = r_c000 * (1.0f - dr) + r_c100 * dr
            val r_c10 = r_c010 * (1.0f - dr) + r_c110 * dr
            val r_c01 = r_c001 * (1.0f - dr) + r_c101 * dr
            val r_c11 = r_c011 * (1.0f - dr) + r_c111 * dr

            val r_c0 = r_c00 * (1.0f - dg) + r_c10 * dg
            val r_c1 = r_c01 * (1.0f - dg) + r_c11 * dg

            finalR = (r_c0 * (1.0f - db) + r_c1 * db)

            // Green Channel (Index + 1)
            val g_c000 = lutData[i000 + 1]
            val g_c100 = lutData[i100 + 1]
            val g_c010 = lutData[i010 + 1]
            val g_c110 = lutData[i110 + 1]
            val g_c001 = lutData[i001 + 1]
            val g_c101 = lutData[i101 + 1]
            val g_c011 = lutData[i011 + 1]
            val g_c111 = lutData[i111 + 1]

            val g_c00 = g_c000 * (1.0f - dr) + g_c100 * dr
            val g_c10 = g_c010 * (1.0f - dr) + g_c110 * dr
            val g_c01 = g_c001 * (1.0f - dr) + g_c101 * dr
            val g_c11 = g_c011 * (1.0f - dr) + g_c111 * dr

            val g_c0 = g_c00 * (1.0f - dg) + g_c10 * dg
            val g_c1 = g_c01 * (1.0f - dg) + g_c11 * dg

            finalG = (g_c0 * (1.0f - db) + g_c1 * db)

            // Blue Channel (Index + 2)
            val b_c000 = lutData[i000 + 2]
            val b_c100 = lutData[i100 + 2]
            val b_c010 = lutData[i010 + 2]
            val b_c110 = lutData[i110 + 2]
            val b_c001 = lutData[i001 + 2]
            val b_c101 = lutData[i101 + 2]
            val b_c011 = lutData[i011 + 2]
            val b_c111 = lutData[i111 + 2]

            val b_c00 = b_c000 * (1.0f - dr) + b_c100 * dr
            val b_c10 = b_c010 * (1.0f - dr) + b_c110 * dr
            val b_c01 = b_c001 * (1.0f - dr) + b_c101 * dr
            val b_c11 = b_c011 * (1.0f - dr) + b_c111 * dr

            val b_c0 = b_c00 * (1.0f - dg) + b_c10 * dg
            val b_c1 = b_c01 * (1.0f - dg) + b_c11 * dg

            finalB = (b_c0 * (1.0f - db) + b_c1 * db)

            // Interpolate dry/wet mix
            val mixedR = (orgR * (1.0f - intensity) + finalR * intensity).coerceIn(0.0f, 1.0f)
            val mixedG = (orgG * (1.0f - intensity) + finalG * intensity).coerceIn(0.0f, 1.0f)
            val mixedB = (orgB * (1.0f - intensity) + finalB * intensity).coerceIn(0.0f, 1.0f)

            val outR = (mixedR * 255.0f).toInt().coerceIn(0, 255)
            val outG = (mixedG * 255.0f).toInt().coerceIn(0, 255)
            val outB = (mixedB * 255.0f).toInt().coerceIn(0, 255)

            pixels[i] = (color and -0x1000000) or (outR shl 16) or (outG shl 8) or outB
        }

        val outputBitmap = Bitmap.createBitmap(width, height, originalBitmap.config ?: Bitmap.Config.ARGB_8888)
        outputBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        outputBitmap
    }

    private fun showToast(msg: String) {
        viewModelScope.launch {
            _eventToast.emit(msg)
        }
    }

    // --- SENSORS & HORIZON STABILIZATION ---
    fun startHorizonSensor() {
        val app = getApplication<Application>()
        sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val accel = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        if (accel != null) {
            sensorListener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                    if (event == null) return
                    val ax = event.values[0]
                    val ay = event.values[1]
                    val az = event.values[2]
                    
                    val calculatedRoll = Math.toDegrees(Math.atan2(-ax.toDouble(), ay.toDouble())).toFloat()
                    val calculatedPitch = Math.toDegrees(Math.atan2(az.toDouble(), Math.sqrt((ax * ax + ay * ay).toDouble()))).toFloat()
                    
                    deviceRoll.value = calculatedRoll
                    devicePitch.value = calculatedPitch
                }

                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }
            sensorManager?.registerListener(sensorListener, accel, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopHorizonSensor() {
        sensorListener?.let {
            sensorManager?.unregisterListener(it)
        }
    }

    fun setGridType(type: Int) {
        activeGridType.value = type
    }

    override fun onCleared() {
        super.onCleared()
        stopHorizonSensor()
    }

    // --- REFRESH AND QUERY SAVED PICTURES / VIDEOS DIRECTLY IN MEDIASTORE ---
    fun refreshCapturedMedia(context: Context) {
        viewModelScope.launch {
            val list = mutableListOf<CapturedMedia>()
            withContext(Dispatchers.IO) {
                try {
                    // Query images
                    val imageProjection = arrayOf(
                        android.provider.MediaStore.Images.Media._ID,
                        android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        android.provider.MediaStore.Images.Media.DATE_ADDED
                    )
                    
                    val imageSelection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                    } else {
                        "${android.provider.MediaStore.Images.Media.DATA} LIKE ?"
                    }
                    
                    val imageSelectionArgs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        arrayOf("%Pictures/BlackCamera%")
                    } else {
                        arrayOf("%/BlackCamera/%")
                    }
                    
                    context.contentResolver.query(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageProjection,
                        imageSelection,
                        imageSelectionArgs,
                        "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                        val dateCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATE_ADDED)
                        
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol)
                            val date = cursor.getLong(dateCol)
                            val uri = android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                            )
                            list.add(CapturedMedia(id, uri, name, isVideo = false, dateAdded = date))
                        }
                    }
                    
                    // Query videos
                    val videoProjection = arrayOf(
                        android.provider.MediaStore.Video.Media._ID,
                        android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                        android.provider.MediaStore.Video.Media.DATE_ADDED
                    )
                    
                    val videoSelection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        "${android.provider.MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                    } else {
                        "${android.provider.MediaStore.Video.Media.DATA} LIKE ?"
                    }
                    
                    val videoSelectionArgs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        arrayOf("%Movies/BlackCamera%")
                    } else {
                        arrayOf("%/BlackCamera/%")
                    }
                    
                    context.contentResolver.query(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        videoProjection,
                        videoSelection,
                        videoSelectionArgs,
                        "${android.provider.MediaStore.Video.Media.DATE_ADDED} DESC"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                        val dateCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATE_ADDED)
                        
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol)
                            val date = cursor.getLong(dateCol)
                            val uri = android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                            )
                            list.add(CapturedMedia(id, uri, name, isVideo = true, dateAdded = date))
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            capturedMediaList.value = list.sortedByDescending { it.dateAdded }
        }
    }

    fun deleteMedia(context: Context, media: CapturedMedia) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.delete(media.uri, null, null)
                refreshCapturedMedia(context)
                showToast("Fichier supprimé de la galerie")
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("Impossible de supprimer ce fichier.")
            }
        }
    }
}

data class CapturedMedia(
    val id: Long,
    val uri: android.net.Uri,
    val name: String,
    val isVideo: Boolean,
    val dateAdded: Long
)
