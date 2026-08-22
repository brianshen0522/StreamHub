package com.streamhub.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.streamhub.core.model.UserCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decoder, against a code drawn the way the television draws it.
 *
 * This is the half of scanning that fails silently. A luminance source built
 * with the wrong stride, or with the padding counted as image, decodes nothing
 * at all — and on a phone that is indistinguishable from holding the camera
 * badly, so it would ship looking like a hardware problem.
 */
class QrDecoderTest {

    private val link = "https://streamhub.gugulu.tw/link?code=ABCD2345"

    /**
     * A camera frame's Y plane: greyscale bytes, and — the part that matters —
     * rows padded out to a stride wider than the image, which is what a real
     * camera hands over and what the naive implementation gets wrong.
     */
    private fun luminanceOf(
        text: String,
        size: Int = 480,
        padding: Int = 32,
    ): Triple<ByteArray, Int, Int> {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
            ),
        )
        val width = matrix.width
        val height = matrix.height
        val stride = width + padding
        val bytes = ByteArray(stride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Dark modules are near-black, light ones near-white; a real
                // sensor never delivers absolute values.
                bytes[y * stride + x] = if (matrix[x, y]) 12.toByte() else 235.toByte()
            }
            // The padding is left as zeroes, exactly as a padded plane arrives.
        }
        return Triple(bytes, stride, width)
    }

    @Test
    fun `a code the television drew is read back`() {
        val (bytes, stride, width) = luminanceOf(link)
        assertEquals(link, QrDecoder.decode(bytes, stride, width, width))
    }

    /** The whole point of scanning: a frame in, a pairing code out. */
    @Test
    fun `and reduces to the pairing code`() {
        val (bytes, stride, width) = luminanceOf(link)
        assertEquals("ABCD2345", UserCode.fromScan(QrDecoder.decode(bytes, stride, width, width)))
    }

    @Test
    fun `an unpadded frame reads too`() {
        val (bytes, stride, width) = luminanceOf(link, padding = 0)
        assertEquals(link, QrDecoder.decode(bytes, stride, width, width))
    }

    @Test
    fun `a frame with no code in it decodes to nothing rather than throwing`() {
        val width = 200
        val bytes = ByteArray(width * width) { 200.toByte() }
        assertNull(QrDecoder.decode(bytes, width, width, width))
    }

    @Test
    fun `nonsense dimensions are refused rather than read out of bounds`() {
        val (bytes, stride, width) = luminanceOf(link)
        assertNull(QrDecoder.decode(bytes, stride, width, 0))
        // A stride narrower than the image is the mistake that would otherwise
        // walk off the end of the array.
        assertNull(QrDecoder.decode(bytes, width - 1, width, width))
        assertNull(QrDecoder.decode(ByteArray(10), stride, width, width))
    }
}
