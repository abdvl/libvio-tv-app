package com.libvio.tv

import com.libvio.tv.data.*
import com.libvio.tv.playback.resumePosition
import org.junit.Assert.*
import org.junit.Test

class LibvioParserTest {
    private val host="https://www.libvio.lat"
    @Test fun entryAcceptsOnlyCurrentHttpsOrigins(){
        val html="""<a href="https://www.libvio.old">old</a><h2>目前可用地址</h2><a href="https://www.libvio.new">new</a><a href="https://www.libvio.new/">duplicate</a><a href="https://www.libvio.lat.evil.test">fake</a><a href="http://www.libvio.bad">http</a><a href="https://u:p@www.libvio.lat">credential</a><h2>其他链接</h2><a href="https://www.libvio.other">other</a>"""
        assertEquals(listOf("https://www.libvio.new"),LibvioParser.hosts(html))
    }
    @Test(expected=SiteException::class) fun challengeIsNotAWorkingSite(){LibvioParser.catalog("<h1>Just a moment</h1>",host)}
    @Test fun homepageKeepsEachCategoryWithinItsOwnSiblingList(){
        val html="<nav class='stui-header__menu'></nav>"+(1..5).joinToString(""){id->"<div class='stui-vodlist__head'><h3><a href='/show/$id-----------.html'>分类$id</a></h3></div><ul class='stui-vodlist'><li><a class='stui-vodlist__thumb' href='/detail/$id.html' title='影片$id'></a></li></ul>"}
        val home=LibvioParser.home(html,host)
        assertEquals(5,home.sections.size)
        home.sections.forEach{assertEquals(it.category.toLong(),it.movies.single().id)}
    }
    @Test fun cardsDeduplicateAndRejectExternalLinks(){
        val card="""<a class="stui-vodlist__thumb" href="/detail/5811477.html" title="测试 &amp; 电影" data-original="https://images.test/poster.jpg"><span class="pic-tag">0.0</span></a>"""
        val page=LibvioParser.catalog("""<ul class="stui-header__menu"></ul>$card$card<a class="stui-vodlist__thumb" href="https://evil.test/detail/1.html" title="广告"></a><a href="/show/1--------2---.html">下一页</a>""",host)
        assertEquals(1,page.movies.size);assertEquals("测试 & 电影",page.movies.single().title);assertEquals("",page.movies.single().score)
        assertEquals("/show/1--------2---.html",page.nextPath)
    }
    @Test fun downloadsDoNotBecomeTheDefaultSource(){
        val detail=LibvioParser.detail("""<div class="stui-content__detail"><h1>样本</h1><p class="data">年份：2026 / 地区：日本</p></div><div class="stui-vodlist__head"><h3>视频下载 (夸克)</h3></div><ul class="stui-content__playlist"><a href="/play/5813548-1-1.html">合集</a></ul><div class="stui-vodlist__head"><h3>HD7播放</h3></div><ul class="stui-content__playlist"><a href="/play/5813548-4-1.html">第01集</a></ul>""",host,Movie(5813548,"原片名"))
        assertEquals(1,detail.sources.size);assertEquals(4,detail.sources.single().sid);assertEquals("2026",detail.movie.year)
    }
    @Test fun internalIdIsReadWithoutArithmetic(){
        val cfg=LibvioParser.player("""<script>var player_aaaa={"id":"3548","sid":4,"nid":1,"from":"BBA","encrypt":3,"url":"private-test-value"};</script>""","$host/play/5813548-4-1.html")
        assertEquals(3548L,cfg.internalId);assertEquals(3,cfg.encrypt);assertFalse(cfg.toString().contains("private-test-value"))
    }
    @Test fun sourceMatchingUsesEpisodeMeaning(){
        val current=Episode(1,"第01集","/old")
        val one=Episode(7,"1","/new")
        assertEquals(one,matchingEpisode(current,listOf(Episode(1,"2","/second"),one),10))
        assertNull(matchingEpisode(current,listOf(Episode(1,"2","/second")),10))
    }
    @Test fun ambiguousEpisodeMappingIsRejected(){assertNull(matchingEpisode(Episode(1,"第1集","/old"),listOf(Episode(1,"1","/a"),Episode(2,"01","/b")),2))}
    @Test fun movieResolutionLabelIsNotAnEpisodeNumber(){assertNull(episodeNumber("1080P"));assertEquals(Episode(1,"HD","/b"),matchingEpisode(Episode(1,"1080P","/a"),listOf(Episode(1,"HD","/b")),1))}
    @Test fun completedRecordDoesNotResumeAtTheLastFrame(){
        val record=WatchRecord(Movie(1,"测试"),"HD7",4,1,"第1集",96_000,100_000,1)
        assertEquals(0L,resumePosition(record));assertEquals(40_000L,resumePosition(record.copy(positionMs=40_000)))
    }
    @Test fun seekClampsToTimeline(){assertEquals(0L,jumpPosition(10_000,100_000,-30_000));assertEquals(100_000L,jumpPosition(90_000,100_000,30_000))}
}
