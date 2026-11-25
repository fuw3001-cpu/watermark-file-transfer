package cn.shiying.local.scan

object ScanFilters {
    fun apply(source:IntArray,width:Int,height:Int,mode:ScanMode,checkCancelled:()->Unit={}):IntArray =
        applyInPlace(source.copyOf(),width,height,mode,checkCancelled)

    /** Sliding sums avoid a full-size integral image and its integer overflow on high-resolution pages. */
    fun applyInPlace(pixels:IntArray,width:Int,height:Int,mode:ScanMode,checkCancelled:()->Unit={}):IntArray {
        require(width>0 && height>0 && width.toLong()*height<=12_000_000 && pixels.size==width*height)
        if(mode==ScanMode.ORIGINAL) return pixels
        val histogram = if (mode == ScanMode.ENHANCE) null else IntArray(256)
        val gray = ByteArray(pixels.size)
        for (y in 0 until height) {
            checkCancelled()
            for (i in y * width until (y + 1) * width) {
                val c = pixels[i]
                val luma = ((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8
                gray[i] = luma.toByte()
                if (histogram != null) histogram[luma]++
            }
        }
        fun percentile(counts: IntArray, fraction: Double): Int {
            val target = (gray.size * fraction).toInt().coerceAtLeast(1)
            var n = 0
            for (i in counts.indices) {
                n += counts[i]
                if (n >= target) return i
            }
            return 255
        }
        if(mode==ScanMode.GRAY) {
            val low = percentile(requireNotNull(histogram), .01)
            val high = percentile(histogram, .99)
            for(i in gray.indices) { if(i%width==0)checkCancelled();val g=gray[i].toInt() and 255
                val value=if(high>low)(255*(g-low)/(high-low)).coerceIn(0,255) else g
                pixels[i]=0xff000000.toInt() or (value shl 16) or (value shl 8) or value
            }
            return pixels
        }
        // Inside thick lettering the local mean is as dark as the ink. Use the page's light tones
        // as a second reference there while retaining the local threshold for shadowed paper.
        val solidInk = if (mode == ScanMode.BLACK_WHITE) (percentile(requireNotNull(histogram), .90) * .38).toInt().coerceIn(8, 90) else 0
        val radius=(minOf(width,height)/30).coerceIn(8,50)
        val columns=IntArray(width)
        for(y in 0..minOf(radius,height-1)) for(x in 0 until width) columns[x]+=gray[y*width+x].toInt() and 255
        for(y in 0 until height) {
            checkCancelled()
            if(y>0) for(x in 0 until width) {
                if(y+radius<height)columns[x]+=gray[(y+radius)*width+x].toInt() and 255
                if(y-radius-1>=0)columns[x]-=gray[(y-radius-1)*width+x].toInt() and 255
            }
            val rows=minOf(height-1,y+radius)-maxOf(0,y-radius)+1
            var sum=0;for(x in 0..minOf(radius,width-1))sum+=columns[x]
            for(x in 0 until width) {
                if(x>0) { if(x+radius<width)sum+=columns[x+radius];if(x-radius-1>=0)sum-=columns[x-radius-1] }
                val span=minOf(width-1,x+radius)-maxOf(0,x-radius)+1;val mean=sum.toFloat()/(rows*span)
                val i=y*width+x;val g=gray[i].toInt() and 255
                pixels[i]=if(mode==ScanMode.BLACK_WHITE) { if(g<=solidInk || g<mean-12)0xff000000.toInt() else 0xffffffff.toInt() }
                else {
                    // Bounded detail enhancement preserves fine strokes without magnifying noise in flat paper.
                    val neighbours=(gray[y*width+maxOf(0,x-1)].toInt() and 255)+
                        (gray[y*width+minOf(width-1,x+1)].toInt() and 255)+
                        (gray[maxOf(0,y-1)*width+x].toInt() and 255)+
                        (gray[minOf(height-1,y+1)*width+x].toInt() and 255)
                    val edge=g-neighbours/4f
                    val detail=if(kotlin.math.abs(edge)>4f)(edge*.45f).coerceIn(-12f,12f) else 0f
                    val gain=(245f/(mean+1)).coerceAtMost(2.2f)
                    fun channel(value:Int)=((((value+detail).coerceIn(0f,255f)*gain-127.5f)*1.12f)+127.5f).toInt().coerceIn(0,255)
                    val c=pixels[i];0xff000000.toInt() or (channel(c shr 16 and 255) shl 16) or (channel(c shr 8 and 255) shl 8) or channel(c and 255)
                }
            }
        }
        return pixels
    }
}
