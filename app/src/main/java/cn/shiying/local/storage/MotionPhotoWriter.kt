package cn.shiying.local.storage

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Google/Android Motion Photo JPEG + MP4. No video re-encoding or recompression of JPEG pixels. */
object MotionPhotoWriter {
    private val xmpHeader="http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    fun write(jpeg:File,video:File,target:File,checkCancelled:()->Unit={}) {
        require(video.length() in 12..128L*1024*1024) { "实况视频大小无效" }
        video.inputStream().use { require(readExact(it,12).copyOfRange(4,8).toString(Charsets.US_ASCII)=="ftyp") { "实况视频不是 MP4" } }
        val length=video.length()
        val xml="""<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="-1" GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="$length" GCamera:MicroVideoPresentationTimestampUs="-1"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$length" Item:Padding="0"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>""".toByteArray(Charsets.UTF_8)
        val packet=xmpHeader+xml
        require(packet.size+2<=65535)
        target.outputStream().buffered().use { out ->
            jpeg.inputStream().buffered().use { input ->
                require(input.read()==255 && input.read()==216) { "实况封面不是 JPEG" }
                out.write(byteArrayOf(255.toByte(),216.toByte(),255.toByte(),225.toByte()))
                out.write((packet.size+2) shr 8);out.write((packet.size+2) and 255);out.write(packet)
                copyJpeg(input,out,checkCancelled)
            }
            video.inputStream().use { input -> val buffer=ByteArray(64*1024)
                while(true) { checkCancelled();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n) }
            }
        }
    }
    private fun readExact(input:InputStream,size:Int):ByteArray {
        val data=ByteArray(size);var offset=0
        while(offset<size) { val n=input.read(data,offset,size-offset);require(n>0) { "媒体文件不完整" };offset+=n }
        return data
    }
    private fun copyJpeg(input:InputStream,out:OutputStream,checkCancelled:()->Unit) {
        var pending:Int?=null
        while(true) {
            checkCancelled()
            val marker=pending ?: run { require(input.read()==255) { "JPEG 标记损坏" };var m=input.read();while(m==255)m=input.read();m }
            pending=null
            require(marker>=0) { "JPEG 不完整" }
            if(marker==217) { out.write(255);out.write(marker);return }
            require(marker !in listOf(0,216) && marker !in 208..215) { "JPEG 标记无效" }
            val high=input.read();val low=input.read();require(high>=0 && low>=0)
            val size=(high shl 8 or low)-2;require(size>=0)
            val data=readExact(input,size);require(data.size==size) { "JPEG 不完整" }
            // Keep EXIF and unrelated metadata. Replace old motion XMP rather than leaving conflicting offsets.
            val oldMotion=marker==225 && data.size>=xmpHeader.size && data.copyOfRange(0,xmpHeader.size).contentEquals(xmpHeader) &&
                data.toString(Charsets.UTF_8).let { it.contains("http://ns.google.com/photos/1.0/camera/") || it.contains("http://ns.google.com/photos/1.0/container/") }
            if(!oldMotion) { out.write(255);out.write(marker);out.write(high);out.write(low);out.write(data) }
            if(marker==218) {
                var count=0
                while(true) {
                    if(count++%65536==0)checkCancelled()
                    val b=input.read();require(b>=0) { "JPEG 扫描不完整" }
                    if(b!=255) { out.write(b);continue }
                    var next=input.read();while(next==255)next=input.read()
                    require(next>=0)
                    if(next==0 || next in 208..215) { out.write(255);out.write(next) }
                    else { pending=next;break }
                }
            }
        }
    }
}
