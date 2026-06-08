package com.example.lut

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

data class LUT3D(
    val size: Int,
    val title: String,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    val data: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LUT3D
        if (size != other.size) return false
        if (title != other.title) return false
        if (!domainMin.contentEquals(other.domainMin)) return false
        if (!domainMax.contentEquals(other.domainMax)) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + title.hashCode()
        result = 31 * result + domainMin.contentHashCode()
        result = 31 * result + domainMax.contentHashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

object LUTParser {
    @Throws(Exception::class)
    fun parse(inputStream: InputStream, defaultTitle: String = "Untitled LUT"): LUT3D {
        val reader = BufferedReader(InputStreamReader(inputStream))
        var size = -1
        var title = defaultTitle
        val domainMin = floatArrayOf(0.0f, 0.0f, 0.0f)
        val domainMax = floatArrayOf(1.0f, 1.0f, 1.0f)
        val valuesList = ArrayList<Float>()

        var line: String?
        var lineNum = 0
        while (reader.readLine().also { line = it } != null) {
            lineNum++
            var trimmed = line!!.trim()
            if (trimmed.isEmpty()) continue

            // Skip comments starting with '#'
            if (trimmed.startsWith("#")) continue

            // Remove inline comments
            val commentIdx = trimmed.indexOf('#')
            if (commentIdx != -1) {
                trimmed = trimmed.substring(0, commentIdx).trim()
                if (trimmed.isEmpty()) continue
            }

            // Check if it's a header line
            val upper = trimmed.uppercase(Locale.US)
            when {
                upper.startsWith("LUT_3D_SIZE") -> {
                    val parts = trimmed.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        try {
                            size = parts[1].toInt()
                        } catch (e: NumberFormatException) {
                            throw IllegalArgumentException("Invalid LUT_3D_SIZE header at line $lineNum: '${parts[1]}'")
                        }
                    } else {
                        throw IllegalArgumentException("Malformed LUT_3D_SIZE header at line $lineNum")
                    }
                }
                upper.startsWith("TITLE") -> {
                    // Extract title, strip outer quotes if present
                    val rawTitle = trimmed.substring(5).trim()
                    title = if (rawTitle.startsWith("\"") && rawTitle.endsWith("\"")) {
                        rawTitle.substring(1, rawTitle.length - 1)
                    } else {
                        rawTitle
                    }
                }
                upper.startsWith("DOMAIN_MIN") -> {
                    val parts = trimmed.split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        try {
                            domainMin[0] = parseCoordinate(parts[1])
                            domainMin[1] = parseCoordinate(parts[2])
                            domainMin[2] = parseCoordinate(parts[3])
                        } catch (e: NumberFormatException) {
                            throw IllegalArgumentException("Invalid DOMAIN_MIN at line $lineNum")
                        }
                    }
                }
                upper.startsWith("DOMAIN_MAX") -> {
                    val parts = trimmed.split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        try {
                            domainMax[0] = parseCoordinate(parts[1])
                            domainMax[1] = parseCoordinate(parts[2])
                            domainMax[2] = parseCoordinate(parts[3])
                        } catch (e: NumberFormatException) {
                            throw IllegalArgumentException("Invalid DOMAIN_MAX at line $lineNum")
                        }
                    }
                }
                else -> {
                    // This is a data row containing floats
                    val tokens = trimmed.split(Regex("\\s+"))
                    if (tokens.size != 3) {
                        throw IllegalArgumentException("Expected exactly 3 RGB values at line $lineNum, but found ${tokens.size}: '$trimmed'")
                    }
                    try {
                        valuesList.add(parseCoordinate(tokens[0]))
                        valuesList.add(parseCoordinate(tokens[1]))
                        valuesList.add(parseCoordinate(tokens[2]))
                    } catch (e: NumberFormatException) {
                        throw IllegalArgumentException("Failed to parse RGB floats at line $lineNum in '$trimmed'")
                    }
                }
            }
        }

        if (size <= 0) {
            throw IllegalArgumentException("Missing or invalid LUT_3D_SIZE header in file")
        }

        val expectedFloats = size * size * size * 3
        if (valuesList.size != expectedFloats) {
            throw IllegalArgumentException("Data size mismatch: Extracted ${valuesList.size / 3} rows, but expected ${size * size * size} rows (Size: $size)")
        }

        val dataArray = FloatArray(valuesList.size)
        for (i in valuesList.indices) {
            dataArray[i] = valuesList[i]
        }

        return LUT3D(
            size = size,
            title = title,
            domainMin = domainMin,
            domainMax = domainMax,
            data = dataArray
        )
    }

    private fun parseCoordinate(str: String): Float {
        var s = str.trim()
        if (s.startsWith(".")) {
            s = "0$s"
        } else if (s.startsWith("-.")) {
            s = "-0" + s.substring(1)
        }
        return s.toFloat()
    }
}
