package cn.shiying.local.scan

import kotlin.math.hypot

data class CaptureDecision(val capture:Boolean=false,val progress:Float=0f,val message:String="请把纸张四角放进取景框")
/** Monotonic-time gate: one capture per stable page, then require the page to leave the view. */
class AutoCaptureGate {
    private var previous:ScanQuad?=null
    private var steadySince:Long?=null
    private var blocked=false
    private var absentSince:Long?=null
    @Synchronized fun captured() { blocked=true;previous=null;steadySince=null;absentSince=null }
    @Synchronized fun retry() { blocked=false;previous=null;steadySince=null;absentSince=null }
    @Synchronized fun update(quad:ScanQuad?,sharp:Boolean,now:Long,allowCapture:Boolean=true):CaptureDecision {
        if(blocked) {
            if(quad==null) {
                val since=absentSince ?: now.also { absentSince=it }
                if(now-since>=600) retry()
            } else absentSince=null
            if(blocked) return CaptureDecision(message="已扫描，请移开上一页再放入下一页")
        }
        if(quad==null || !quad.valid()) { previous=null;steadySince=null;return CaptureDecision() }
        if(!sharp) { previous=null;steadySince=null;return CaptureDecision(message="请保持稳定，等待画面对焦清晰") }
        if(!allowCapture) { previous=quad;steadySince=null;return CaptureDecision(message="已找到纸张") }
        val last=previous
        if(steadySince==null || last==null || last.points.zip(quad.points).any { (a,b) -> hypot(a.x-b.x,a.y-b.y)>.018f }) { steadySince=now;previous=quad }
        val progress=((now-(steadySince ?: now))/1200f).coerceIn(0f,1f)
        if(progress>=1f) { captured();return CaptureDecision(true,1f,"正在自动拍摄…") }
        return CaptureDecision(progress=progress,message="已识别纸张，保持稳定即可自动扫描")
    }
}
