package cn.shiying.local.parser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Shared snapshot for both automatic loading and the explicit verification page. No JS bridge. */
object BrowserPage {
    val extractScript = """(function(){try {
        var key = window.__INITIAL_STATE__ ? '__INITIAL_STATE__' : (window._ROUTER_DATA ? '_ROUTER_DATA' : (window._SSR_DATA ? '_SSR_DATA' : (window.__INIT_PROPS__ ? '__INIT_PROPS__' : null)));
        if(key && !window.__pace_f) return '<script>window.'+key+'='+JSON.stringify(window[key]).replace(/</g,'\\u003c')+';</script>';
        if(window.__pace_f) return Array.from(document.scripts).filter(function(s) {
            return s.id==='RENDER_DATA' || /(?:self|window)\.__pace_f\.push\s*\(/.test(s.textContent);
        }).map(function(s) { return s.outerHTML; }).join('\n');
    } catch(e) {} return document.documentElement ? document.documentElement.outerHTML : '';})()"""

    val snapshotScript = """(function(){
        var html=$extractScript;
        var text=(document.title+' '+(document.body ? document.body.innerText.slice(0,1500) : ''));
        return JSON.stringify({url:location.href,html:html.length<=4194304 ? html : '',
            verification:!!document.querySelector('#captcha_container,#captcha-verify-image,iframe[src*="captcha"]') ||
                /完成验证|请先验证|拖动滑块|安全验证|登录后查看|扫码登录后/.test(text)});
    })()"""

    fun parseDouyin(html:String, initialUrl:String, pageUrl:String):MediaInfo {
        val initial=initialUrl.toHttpUrlOrNull()?.takeIf(ShareLinks::isDouyin)
            ?: throw ParseException("作品链接无效")
        val page=pageUrl.toHttpUrlOrNull()?.takeIf(ShareLinks::isDouyin)
            ?: throw ParseException("作品网页已离开抖音")
        val expected=ShareLinks.videoId(initial)
        val actual=ShareLinks.videoId(page)
        if(expected!=null && actual!=null && expected!=actual) throw ParseException("当前网页不是原分享作品")
        val id=expected ?: actual ?: throw ParseException("尚未加载到具体作品")
        return DouyinDocument.parse(html,id,page.toString())
    }
}
