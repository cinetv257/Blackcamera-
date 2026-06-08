package com.example.lut

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class LutCameraRenderer(
    private val onViewCreated: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private val tags = "LutCameraRenderer"

    // Shaders
    private val vertexShaderCode = """
        #version 300 es
        in vec4 aPosition;
        in vec4 aTextureCoord;
        out vec2 vTextureCoord;
        uniform mat4 uSTMatrix;
        
        void main() {
            gl_Position = aPosition;
            vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        
        in vec2 vTextureCoord;
        out vec4 fragColor;
        
        uniform samplerExternalOES sTexture;
        uniform sampler3D uLutTexture;
        uniform float uLutEnabled;
        uniform float uLutIntensity;
        
        void main() {
            vec4 cameraColor = texture(sTexture, vTextureCoord);
            if (uLutEnabled > 0.5) {
                // In .cube files, RGB matches coordinates mapping directly
                vec3 lutColor = texture(uLutTexture, cameraColor.rgb).rgb;
                fragColor = vec4(mix(cameraColor.rgb, lutColor, uLutIntensity), cameraColor.a);
            } else {
                fragColor = cameraColor;
            }
        }
    """.trimIndent()

    // Geometry data
    private val squareCoords = floatArrayOf(
        -1.0f, -1.0f, 0.0f,
         1.0f, -1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f,
         1.0f,  1.0f, 0.0f
    )

    private val textureCoords = floatArrayOf(
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    )

    private var vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(squareCoords.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(squareCoords)
            position(0)
        }

    private var textureBuffer: FloatBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(textureCoords)
            position(0)
        }

    // GL variables
    private var program = 0
    private var cameraTexId = -1
    private var lutTexId = -1

    var surfaceTexture: SurfaceTexture? = null
        private set

    private val stMatrix = FloatArray(16)

    // LUT State (Safely synchronized or read on GL thread)
    private var activeLut: LUT3D? = null
    private var lutUpdated = false
    private var lutEnabled = false
    private var lutIntensity = 1.0f
    private var highQuality = true
    private var qualityUpdated = false

    private val lock = Any()

    init {
        Matrix.setIdentityM(stMatrix, 0)
    }

    // Exposed configuration methods
    fun setLut(lut: LUT3D?) {
        synchronized(lock) {
            activeLut = lut
            lutUpdated = true
        }
    }

    fun setLutEnabled(enabled: Boolean) {
        synchronized(lock) {
            lutEnabled = enabled
        }
    }

    fun setLutIntensity(intensity: Float) {
        synchronized(lock) {
            lutIntensity = intensity
        }
    }

    fun setHighQuality(high: Boolean) {
        synchronized(lock) {
            if (highQuality != high) {
                highQuality = high
                qualityUpdated = true
            }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Compile vertex shader
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES30.GL_TRUE) {
            Log.e(tags, "Could not link program: " + GLES30.glGetProgramInfoLog(program))
            GLES30.glDeleteProgram(program)
            program = 0
        }

        // Generate camera texture
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        cameraTexId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Create surface texture
        surfaceTexture = SurfaceTexture(cameraTexId)
        surfaceTexture?.setOnFrameAvailableListener(this)

        onViewCreated(surfaceTexture!!)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)

        if (program == 0) return

        GLES30.glUseProgram(program)

        // Handle updates on LUT or Quality
        var glLutEnabled: Boolean
        var glLutIntensity: Float
        var localLut: LUT3D?
        var updateTex = false
        var updateQuality = false

        synchronized(lock) {
            glLutEnabled = lutEnabled
            glLutIntensity = lutIntensity
            localLut = activeLut
            if (lutUpdated) {
                updateTex = true
                lutUpdated = false
            }
            if (qualityUpdated) {
                updateQuality = true
                qualityUpdated = false
            }
        }

        // Apply Quality Filtering mode if changed
        if (lutTexId != -1 && (updateQuality || updateTex)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
            val filtering = if (highQuality) GLES30.GL_LINEAR else GLES30.GL_NEAREST
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, filtering)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, filtering)
        }

        // Upload LUT texture data to GPU if updated
        if (updateTex) {
            if (lutTexId != -1) {
                GLES30.glDeleteTextures(1, intArrayOf(lutTexId), 0)
                lutTexId = -1
            }

            if (localLut != null) {
                val lut = localLut!!
                val textures = IntArray(1)
                GLES30.glGenTextures(1, textures, 0)
                lutTexId = textures[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)

                val filtering = if (highQuality) GLES30.GL_LINEAR else GLES30.GL_NEAREST
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, filtering)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, filtering)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

                // Fill buffer
                val floatBuffer = ByteBuffer.allocateDirect(lut.data.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                floatBuffer.put(lut.data)
                floatBuffer.position(0)

                GLES30.glTexImage3D(
                    GLES30.GL_TEXTURE_3D,
                    0,
                    GLES30.GL_RGB16F, // 16-bit float internal format
                    lut.size,
                    lut.size,
                    lut.size,
                    0,
                    GLES30.GL_RGB,
                    GLES30.GL_FLOAT,
                    floatBuffer
                )
                Log.d(tags, "Uploaded new 3D LUT of size: ${lut.size} to texture ID: $lutTexId")
            }
        }

        // Bind attributes & uniforms
        val positionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, vertexBuffer)

        val texCoordHandle = GLES30.glGetAttribLocation(program, "aTextureCoord")
        GLES30.glEnableVertexAttribArray(texCoordHandle)
        GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, 0, textureBuffer)

        // uSTMatrix
        val stMatrixHandle = GLES30.glGetUniformLocation(program, "uSTMatrix")
        GLES30.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)

        // Bind camera texture to Unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        val sTextureHandle = GLES30.glGetUniformLocation(program, "sTexture")
        GLES30.glUniform1i(sTextureHandle, 0)

        // Bind 3D LUT texture to Unit 1 if enabled
        val lutEnabledHandle = GLES30.glGetUniformLocation(program, "uLutEnabled")
        val lutIntensityHandle = GLES30.glGetUniformLocation(program, "uLutIntensity")

        if (glLutEnabled && lutTexId != -1) {
            GLES30.glUniform1f(lutEnabledHandle, 1.0f)
            GLES30.glUniform1f(lutIntensityHandle, glLutIntensity)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
            val lutTextureHandle = GLES30.glGetUniformLocation(program, "uLutTexture")
            GLES30.glUniform1i(lutTextureHandle, 1)
        } else {
            GLES30.glUniform1f(lutEnabledHandle, 0.0f)
            GLES30.glUniform1f(lutIntensityHandle, 0.0f)
        }

        // Draw quad
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(positionHandle)
        GLES30.glDisableVertexAttribArray(texCoordHandle)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        // Redraw frame when incoming buffer is ready
        surfaceTexture?.let {
            // Signal a draw request
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(tags, "Could not compile shader $type: " + GLES30.glGetShaderInfoLog(shader))
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
