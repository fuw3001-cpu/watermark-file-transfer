package cn.shiying.local.scan

import kotlin.math.*

data class ScanPoint(val x: Float, val y: Float)
data class ScanQuad(val points: List<ScanPoint> = listOf(ScanPoint(0f,0f), ScanPoint(1f,0f), ScanPoint(1f,1f), ScanPoint(0f,1f))) {
    val area: Float get() = if (points.size != 4) 0f else abs(points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i+1)%4]; (a.x*b.y-b.x*a.y).toDouble()
    }.toFloat())/2
    fun valid(): Boolean = points.size == 4 && points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f } &&
        area >= .025f && points.indices.all { i ->
            val a=points[i]; val b=points[(i+1)%4]; val c=points[(i+2)%4]
            (b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x) > .0005f && hypot(b.x-a.x,b.y-a.y) >= .035f
        }
    fun moved(index: Int, dx: Float, dy: Float): ScanQuad {
        if (index !in 0..3 || !dx.isFinite() || !dy.isFinite()) return this
        val next = copy(points = points.mapIndexed { i,p -> if (i==index) ScanPoint((p.x+dx).coerceIn(0f,1f),(p.y+dy).coerceIn(0f,1f)) else p })
        return if (next.valid()) next else this
    }
    fun outputSize(width: Int, height: Int, maxEdge: Int = 2000, maxPixels: Int = 4_000_000): Pair<Int,Int> {
        require(valid() && width>0 && height>0)
        fun distance(a: Int,b: Int): Float = hypot((points[a].x-points[b].x)*width,(points[a].y-points[b].y)*height)
        val w=max(distance(0,1),distance(3,2)); val h=max(distance(0,3),distance(1,2))
        val factor=minOf(1f,maxEdge.toFloat()/max(w,h),sqrt(maxPixels.toFloat()/(w*h)))
        return max(1,(w*factor).toInt()) to max(1,(h*factor).toInt())
    }
}

/** Conservative bright-paper detection. Ambiguous backgrounds return null for manual correction. */
object PaperEdges {
    fun detect(width: Int, height: Int, gray: IntArray): ScanQuad? {
        require(width>0 && height>0 && gray.size==width*height)
        val histogram=IntArray(256); gray.forEach { histogram[it.coerceIn(0,255)]++ }
        val total=gray.size; val sum=histogram.indices.sumOf { it.toDouble()*histogram[it] }
        var weight=0; var sumBefore=0.0; var best=0.0; var threshold=140
        for (t in 0..254) {
            weight+=histogram[t]; sumBefore+=t.toDouble()*histogram[t]
            if (weight==0 || weight==total) continue
            val score=weight.toDouble()*(total-weight)*(sumBefore/weight-(sum-sumBefore)/(total-weight)).pow(2)
            if (score>best) { best=score; threshold=t }
        }
        threshold=max(140,threshold)
        val seen=BooleanArray(total); val queue=IntArray(total)
        var largest=0; var selected: ScanQuad?=null
        for (seed in gray.indices) {
            if (seen[seed] || gray[seed]<=threshold) continue
            var head=0; var tail=1; queue[0]=seed; seen[seed]=true
            var tl=seed; var tr=seed; var br=seed; var bl=seed; var border=0
            fun x(i:Int)=i%width
            fun y(i:Int)=i/width
            while (head<tail) {
                val current=queue[head++]; val cx=x(current); val cy=y(current)
                if (cx+cy<x(tl)+y(tl)) tl=current
                if (cx-cy>x(tr)-y(tr)) tr=current
                if (cx+cy>x(br)+y(br)) br=current
                if (cx-cy<x(bl)-y(bl)) bl=current
                if(cx==0) border=border or 1
                if(cx==width-1) border=border or 2
                if(cy==0) border=border or 4
                if(cy==height-1) border=border or 8
                fun add(index:Int) { if(!seen[index] && gray[index]>threshold) { seen[index]=true; queue[tail++]=index } }
                if(cx>0) add(current-1); if(cx+1<width) add(current+1)
                if(cy>0) add(current-width); if(cy+1<height) add(current+width)
            }
            val quad=ScanQuad(listOf(tl,tr,br,bl).map { ScanPoint((x(it)+.5f)/width,(y(it)+.5f)/height) })
            if(tail>largest && tail>total*.12f && Integer.bitCount(border)<3 && quad.valid() && quad.area>.12f && tail/(total*quad.area)>.5f) {
                largest=tail; selected=quad
            }
        }
        return selected
    }
}

enum class ScanMode(val label:String) { ORIGINAL("原色"), ENHANCE("提亮增强"), GRAY("灰度"), BLACK_WHITE("黑白文档") }
data class ScanSettings(val quad: ScanQuad=ScanQuad(), val mode:ScanMode=ScanMode.ENHANCE, val turns:Int=0)

enum class ScanQuality(val label:String,val edge:Int,val pixels:Int) { STANDARD("标准 2000",2000,4_000_000), HIGH("高清 3508",3508,10_000_000), ULTRA("超清 4800",4800,12_000_000) }
