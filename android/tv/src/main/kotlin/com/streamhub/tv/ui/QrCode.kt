package com.streamhub.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code, drawn rather than fetched.
 *
 * Deliberately not an image from the server: the sign-in screen is the one
 * screen that runs before there is a session, and making it depend on a second
 * request would mean a television that cannot sign in because an image failed
 * to load — while the code underneath it was perfectly usable.
 *
 * Rendered on a **white tile** even though every other surface in this app is
 * near-black. That is not a lapse in the palette: scanners expect dark modules
 * on a light field, and inverting it costs reliability on exactly the phones
 * that are hardest to help — old cameras, low light, at television distance.
 * The tile is rounded and inset so it reads as a deliberate object on the dark
 * ground rather than a hole in it.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
) {
    // Encoding is pure and not cheap enough to redo on every recomposition,
    // and the content only changes when a new code is issued.
    val matrix = remember(content) { encode(content) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(12.dp),
    ) {
        if (matrix == null) return@Box
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Whole pixels per module, or the renderer antialiases module edges
            // into grey and scanners start missing it. Any remainder goes into
            // centring rather than into a fractional module.
            val modules = matrix.width
            val module = kotlin.math.floor(minOf(size.width, size.height) / modules)
            if (module < 1f) return@Canvas
            val drawn = module * modules
            val originX = (size.width - drawn) / 2f
            val originY = (size.height - drawn) / 2f

            for (y in 0 until modules) {
                for (x in 0 until modules) {
                    if (!matrix[x, y]) continue
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(originX + x * module, originY + y * module),
                        size = Size(module, module),
                    )
                }
            }
        }
    }
}

/**
 * Correction level M, not the maximum.
 *
 * H would survive a dirtier screen but needs more modules for the same payload,
 * and more modules on a fixed-size tile means smaller ones — which is the thing
 * that actually stops a phone reading a television across a room. A screen is a
 * clean, self-lit surface; it is not the case H exists for.
 */
private fun encode(content: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        // Asking for the natural size and scaling it here keeps the modules
        // square; asking for pixels would let the writer pad unevenly.
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            // One module of quiet zone from the encoder; the white tile's own
            // padding supplies the rest of the margin a scanner wants.
            EncodeHintType.MARGIN to 1,
            // No character-set hint on purpose. Asking for UTF-8 makes the
            // encoder prepend an ECI header, which costs bytes and which some
            // readers handle badly — one of them complained about it here. The
            // payload is an ASCII URL, so the default encoding is both smaller
            // and more widely readable.
        ),
    )
}.getOrNull()
