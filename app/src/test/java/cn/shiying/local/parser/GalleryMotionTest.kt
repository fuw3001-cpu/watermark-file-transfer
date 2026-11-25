package cn.shiying.local.parser

import org.junit.Assert.*
import org.junit.Test

class GalleryMotionTest {
    @Test fun douyinKeepsPerImageMotionSeparateFromGalleryMusicAndPrefersAnimation() {
        val json="""{"aweme_list":[{"aweme_id":"7424432820954598707","video":{"play_addr":{"url_list":["https://example.com/music.mp4"]}},"images":[{"url_list":["https://example.com/a.jpg"],"video":{"play_addr_h264":{"url_list":["https://example.com/live.mp4","https://example.com/live-backup.mp4"]}}},{"url_list":["https://example.com/poster.jpg"],"animated_url":"https://example.com/move.gif"}]}]}"""
        val result=DouyinDocument.parseFeed(json,"7424432820954598707","")
        assertTrue(result.isGallery);assertEquals("",result.videoUrl)
        assertEquals("https://example.com/live.mp4",result.images[0].motionUrl)
        assertEquals(listOf("https://example.com/live-backup.mp4"),result.images[0].motionAlternatives)
        assertEquals("https://example.com/move.gif",result.images[1].previewUrl)
        assertTrue(result.images[1].animated);assertFalse(result.images[1].isLive)
        assertEquals(listOf("https://example.com/poster.jpg"), result.images[1].alternatives) // inspected only if the animation URL fails
    }
    private val id="64c8ae73000000001900b94d"
    private fun xhs(images:String)=XiaohongshuDocument.parse("""<script>window.__INITIAL_STATE__={"note":{"noteDetailMap":{"$id":{"note":{"noteId":"$id","imageList":$images}}}}};</script>""",id,"")
    @Test fun xhsPreservesLiveTrackAndDoesNotForceAnAnimatedOriginalToJpeg() {
        val result=xhs("""[{"fileId":"static-cover","urlDefault":"https://sns-img-qc.xhscdn.com/cover.webp","livePhoto":true,"stream":{"h264":[{"masterUrl":"https://sns-video-bd.xhscdn.com/live.mp4"}]}},{"fileId":"gif-poster","urlDefault":"https://sns-img-qc.xhscdn.com/animation.gif","isAnimated":true}]""")
        assertTrue(result.images[0].isLive)
        assertEquals("https://sns-video-bd.xhscdn.com/live.mp4",result.images[0].motionUrl)
        assertEquals("https://sns-img-qc.xhscdn.com/animation.gif",result.images[1].url)
        assertFalse(result.images[1].alternatives.any { it.contains("format/jpg") })
    }
    @Test fun mobileNoteKeepsPlainUrlAndVideoBackupsWithoutACover() {
        val result = xhs("""[{"url":"https://sns-img-qc.xhscdn.com/original"},{"livePhoto":true,"stream":{"h264":[{"masterUrl":"https://sns-video-bd.xhscdn.com/expired","backup_urls":["https://sns-video-bd.xhscdn.com/good"]}]}}]""")
        assertEquals(2, result.images.size)
        assertTrue(result.images[0].url.endsWith("/original"))
        assertEquals("", result.images[1].url)
        assertTrue(result.images[1].motionUrls.last().endsWith("/good"))
    }

    @Test fun nativeGifInInfoListWinsOverJpegAndDouyinReadsRenditionTracks() {
        val photo = xhs("""[{"urlDefault":"https://sns-img-qc.xhscdn.com/a.jpg","infoList":[{"url":"https://sns-img-qc.xhscdn.com/a.gif"}]}]""").images.single()
        assertTrue(photo.animated)
        assertTrue(photo.url.endsWith(".gif"))
        val live = DouyinDocument.parseFeed("""{"aweme_id":"123","images":[{"video":{"bitRate":[{"playAddr":{"urlList":["https://example.com/live.mp4"]}}]}}]}""", "123", "").images.single()
        assertEquals("https://example.com/live.mp4", live.motionUrl)
    }

    @Test fun perImageMp4AnimationIsSavedThroughTheLiveVideoPath() {
        val photo = xhs("""[{"animatedUrl":"//sns-video-bd.xhscdn.com/clip.mp4","urlDefault":"https://sns-img-qc.xhscdn.com/poster.jpg"}]""").images.single()
        assertTrue(photo.url.endsWith("poster.jpg"))
        assertEquals("https://sns-video-bd.xhscdn.com/clip.mp4", photo.motionUrl)
        val picture = DouyinDocument.parseFeed("""{"aweme_id":"123","images":[{"url_list":["https://example.com/cover.jpg","https://example.com/play?mime_type=video_mp4"]}]}""", "123", "").images.single()
        assertEquals("https://example.com/cover.jpg", picture.url)
        assertTrue(picture.motionUrl.contains("video_mp4"))
    }

    @Test fun markedLivePhotoCannotSilentlyBecomeStaticWhenTrackIsMissingOrInvalid() {
        for(video in listOf("","https://xhscdn.com.evil.test/live.mp4")) assertThrows(ParseException::class.java) {
            xhs("""[{"urlDefault":"https://sns-img-qc.xhscdn.com/a.jpg","livePhoto":true,"stream":{"h264":[{"masterUrl":"$video"}]}}]""")
        }
    }
}
