package cn.shiying.local.conversion

import cn.shiying.local.storage.MotionPhotoWriter
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MotionPhotoTest {
    @Test fun motionPhotoPreservesJpegPixelsAndAppendsTheExactVideoWithMatchingOffset() {
        val dir=kotlin.io.path.createTempDirectory("motion-test-").toFile()
        try {
            val jpeg=File(dir,"cover.jpg").apply { writeBytes(this@MotionPhotoTest.javaClass.getResourceAsStream("/scan/portrait.jpg")!!.readBytes()) }
            val video=File(dir,"motion.mp4").apply { writeBytes(this@MotionPhotoTest.javaClass.getResourceAsStream("/media/motion.mp4")!!.readBytes()) }
            val original=jpeg.readBytes();val output=File(dir,"result.jpg")
            MotionPhotoWriter.write(jpeg,video,output)
            val data=output.readBytes();val movie=video.readBytes()
            assertArrayEquals(movie,data.copyOfRange(data.size-movie.size,data.size))
            val header=data.copyOfRange(0,data.size-movie.size).toString(Charsets.ISO_8859_1)
            assertTrue(header.contains("GCamera:MicroVideoOffset=\"${movie.size}\""))
            assertTrue(header.contains("Item:Semantic=\"MotionPhoto\" Item:Length=\"${movie.size}\""))
            assertArrayEquals(original,jpeg.readBytes())
            val app1Length=((data[4].toInt() and 255) shl 8) or (data[5].toInt() and 255)
            val originalJpeg=data.copyOfRange(0,2)+data.copyOfRange(4+app1Length,data.size-movie.size)
            assertArrayEquals("All original JPEG segments and scan bytes must survive",original,originalJpeg)
            System.getenv("SHIYING_MOTION_QA_DIR")?.let { path -> File(path).mkdirs();output.copyTo(File(path,"motion.jpg"),overwrite=true) }
            val updated=File(dir,"updated.jpg");MotionPhotoWriter.write(output,video,updated)
            assertArrayEquals("Old XMP and video trailer must not accumulate",data,updated.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun malformedCoverAndCancellationFailBeforePublishing() {
        val dir=kotlin.io.path.createTempDirectory("motion-invalid-").toFile()
        try {
            val jpeg=File(dir,"cover.jpg").apply { writeBytes(byteArrayOf(-1,-40,-1,-38,0,8,1)) }
            val video=File(dir,"video.mp4").apply { writeBytes(this@MotionPhotoTest.javaClass.getResourceAsStream("/media/motion.mp4")!!.readBytes()) }
            assertThrows(IllegalArgumentException::class.java) { MotionPhotoWriter.write(jpeg,video,File(dir,"bad.jpg")) }
            jpeg.writeBytes(this@MotionPhotoTest.javaClass.getResourceAsStream("/scan/portrait.jpg")!!.readBytes())
            assertThrows(InterruptedException::class.java) { MotionPhotoWriter.write(jpeg,video,File(dir,"cancel.jpg")) { throw InterruptedException() } }
        } finally { dir.deleteRecursively() }
    }
}
