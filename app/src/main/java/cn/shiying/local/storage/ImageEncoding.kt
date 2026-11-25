package cn.shiying.local.storage

enum class ImageEncoding(val extension: String, val mime: String) {
    JPEG("jpg", "image/jpeg"), PNG("png", "image/png"), WEBP("webp", "image/webp"), GIF("gif", "image/gif");
    companion object {
        fun sniff(bytes: ByteArray): ImageEncoding? {
            if (bytes.size < 12) return null
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return JPEG
            if (bytes.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) return PNG
            val start = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
            if (start == "GIF87a" || start == "GIF89a") return GIF
            if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP") return WEBP
            return null
        }
    }
}
