package app.passman.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Reads QR codes on the device with ZXing; nothing leaves the phone. */
object QrDecoder {
    private val fastHints = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")
    private val thoroughHints = fastHints + (DecodeHintType.TRY_HARDER to true)

    /** [luma] is one byte of brightness per pixel, row by row (a camera frame's Y plane). */
    fun decodeLuminance(luma: ByteArray, width: Int, height: Int): String? =
        decode(PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false), fastHints)

    /** [argb] is one ARGB pixel per int, row by row (as from Bitmap.getPixels). */
    fun decodeArgb(argb: IntArray, width: Int, height: Int): String? =
        decode(RGBLuminanceSource(width, height, argb), thoroughHints)

    // Also tries the inverted image: some sites draw light codes on dark backgrounds.
    private fun decode(source: LuminanceSource, hints: Map<DecodeHintType, Any>): String? {
        val reader = QRCodeReader()
        for (candidate in listOf(source, source.invert())) {
            try {
                return reader.decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text
            } catch (_: ReaderException) {
            } finally {
                reader.reset()
            }
        }
        return null
    }
}
