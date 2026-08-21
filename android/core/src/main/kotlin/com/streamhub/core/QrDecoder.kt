package com.streamhub.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Finding a QR code in a frame of camera luminance.
 *
 * Lives here, away from the camera, because this is the part that can be wrong
 * without anything saying so: a luminance source built with the wrong stride
 * decodes nothing at all, and on a phone that is indistinguishable from holding
 * the camera badly. Kept free of Android types so it can be tested against a
 * code this same library encoded.
 */
object QrDecoder {

    /**
     * @param luminance the Y plane of a YUV_420_888 frame — already a greyscale
     *   image, so nothing is converted.
     * @param rowStride bytes per row, which is **not** always the width: camera
     *   planes are padded, and treating the stride as the width shears the
     *   image into noise.
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        if (luminance.size < rowStride * height) return null

        val source = PlanarYUVLuminanceSource(
            luminance,
            rowStride,
            height,
            0,
            0,
            width,
            height,
            false,
        )

        val reader = QRCodeReader()
        return try {
            reader.decode(
                BinaryBitmap(HybridBinarizer(source)),
                mapOf(DecodeHintType.TRY_HARDER to true),
            ).text
        } catch (error: Exception) {
            // NotFoundException on most frames, which simply means no code is in
            // view. Nothing here is worth reporting to a caller pointing a
            // camera around a room.
            null
        } finally {
            reader.reset()
        }
    }
}
