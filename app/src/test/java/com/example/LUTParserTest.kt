package com.example

import com.example.lut.LUTParser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class LUTParserTest {

    @Test
    fun testStandard16() {
        val header = """
            TITLE "Standard 16x16x16 LUT"
            LUT_3D_SIZE 16
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
        """.trimIndent()

        // Generate 16^3 = 4096 rows of RGB values
        val sb = StringBuilder()
        sb.append(header).append("\n")
        for (i in 0 until 4096) {
            sb.append("0.1 0.2 0.3\n")
        }

        val stream = ByteArrayInputStream(sb.toString().toByteArray())
        val lut = LUTParser.parse(stream)

        assertEquals(16, lut.size)
        assertEquals("Standard 16x16x16 LUT", lut.title)
        assertArrayEquals(floatArrayOf(0.0f, 0.0f, 0.0f), lut.domainMin, 0.001f)
        assertArrayEquals(floatArrayOf(1.0f, 1.0f, 1.0f), lut.domainMax, 0.001f)
        assertEquals(4096 * 3, lut.data.size)
        assertEquals(0.1f, lut.data[0], 0.001f)
        assertEquals(0.2f, lut.data[1], 0.001f)
        assertEquals(0.3f, lut.data[2], 0.001f)
    }

    @Test
    fun testCommentsAndSpaces() {
        val cubeContent = """
            # This is a leading comment
            TITLE      "Spaces and Comments"   
            
            LUT_3D_SIZE 2
            
            # Another comment line
            DOMAIN_MIN  0.0   0.0   0.0  # with inline comment
            DOMAIN_MAX  1.0   1.0   1.0
            
            0.100000 0.200000 0.300000
            0.400000 0.500000 0.600000	# Tab separated and inline comment
              0.700000   0.800000   0.900000  
            0.0 0.0 0.0
            
            1.0 1.0 1.0
            0.5 0.5 0.5
            0.2 0.3 0.4
            0.9 0.9 0.9
            
            # Trailing comment
        """.trimIndent()

        val stream = ByteArrayInputStream(cubeContent.toByteArray())
        val lut = LUTParser.parse(stream)

        assertEquals(2, lut.size)
        assertEquals("Spaces and Comments", lut.title)
        assertEquals(8 * 3, lut.data.size) // 2^3 = 8 rows
        assertEquals(0.1f, lut.data[0], 0.001f)
        assertEquals(0.5f, lut.data[4], 0.001f)
        assertEquals(0.9f, lut.data[6], 0.001f)
    }

    @Test
    fun testScientificNotation() {
        val cubeContent = """
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            1.23e-4  .456  2.1e-2
            0.5 0.5 0.5
            0.0 0.0 0.0
            0.1 0.1 0.1
            0.2 0.2 0.2
            0.3 0.3 0.3
            0.4 0.4 0.4
            0.5 0.5 0.5
        """.trimIndent()

        val stream = ByteArrayInputStream(cubeContent.toByteArray())
        val lut = LUTParser.parse(stream)

        assertEquals(2, lut.size)
        assertEquals(1.23e-4f, lut.data[0], 0.000001f)
        assertEquals(0.456f, lut.data[1], 0.001f)
        assertEquals(2.1e-2f, lut.data[2], 0.001f)
    }

    @Test
    fun testCorruptedFile() {
        val corruptedContent = """
            LUT_3D_SIZE 2
            0.1 oops 0.3
            0.4 0.5 0.6
            0.7 0.8 0.9
            0.0 0.0 0.0
            1.0 1.0 1.0
            0.5 0.5 0.5
            0.2 0.3 0.4
            0.9 0.9 0.9
        """.trimIndent()

        val stream = ByteArrayInputStream(corruptedContent.toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            LUTParser.parse(stream)
        }
    }

    @Test
    fun testIncorrectSize() {
        // Declared size 2 (needs 8 rows), but only contains 7 rows
        val shortContent = """
            LUT_3D_SIZE 2
            0.1 0.2 0.3
            0.4 0.5 0.6
            0.7 0.8 0.9
            0.0 0.0 0.0
            1.0 1.0 1.0
            0.5 0.5 0.5
            0.2 0.3 0.4
        """.trimIndent()

        val stream = ByteArrayInputStream(shortContent.toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            LUTParser.parse(stream)
        }
    }
}
