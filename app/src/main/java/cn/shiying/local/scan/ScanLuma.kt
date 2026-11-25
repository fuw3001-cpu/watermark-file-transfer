package cn.shiying.local.scan

import java.nio.ByteBuffer

/** Copy the cropped Y plane, including cameras with padded rows or interleaved samples. */
internal fun copyScanLuma(source: ByteBuffer, left: Int, top: Int, width: Int, height: Int,
                          rowStride: Int, pixelStride: Int, output: ByteArray) {
    val input = source.duplicate()
    val start = input.position() + top * rowStride + left * pixelStride
    if (pixelStride == 1 && rowStride == width) {
        input.position(start)
        input.get(output, 0, width * height)
        return
    }
    for (y in 0 until height) {
        val row = start + y * rowStride
        if (pixelStride == 1) {
            input.position(row)
            input.get(output, y * width, width)
        } else {
            for (x in 0 until width) output[y * width + x] = input.get(row + x * pixelStride)
        }
    }
}
