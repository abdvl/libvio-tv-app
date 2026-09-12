package com.libvio.tv.data

import com.libvio.tv.Movie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject

object LibvioParser {
    fun siteOrigin(value:String):String? {
        val u=value.toHttpUrlOrNull()?:return null
        return if(u.isHttps&&u.username.isEmpty()&&u.password.isEmpty()&&u.port==443)"https://${u.host}"else null
    }
    fun sitePath(href:String,host:String,pattern:Regex):String? {
        val u=host.toHttpUrlOrNull()?.resolve(href)?:return null
        return if(siteOrigin(u.toString())==siteOrigin(host)&&u.query==null&&u.fragment==null&&pattern.matches(u.encodedPath))u.encodedPath else null
    }
    fun hosts(html:String):List<String> {
        val doc=Jsoup.parse(html);var active=false
        val list=mutableListOf<String>()
        doc.select("h2,a[href]").forEach{e->
            if(e.tagName()=="h2")active=e.text().contains("目前可用地址")
            else if(active){val value=e.attr("href");val u=value.toHttpUrlOrNull();val o=siteOrigin(value)
                if(u!=null&&o!=null&&Regex("(?:www\\.)?libvio\\.[a-z]+").matches(u.host)&&u.host !in setOf("libvio.lol","www.libvio.lol")&&u.encodedPath=="/"&&u.query==null&&u.fragment==null)list+=o}
        }
        return list.distinct().ifEmpty{throw SiteException("发布入口暂时无法读取，请重试")}
    }
    private fun movie(a:Element,host:String,category:Int):Movie? {
        val path=sitePath(a.attr("href"),host,Regex("/detail/\\d+\\.html"))?:return null
        val title=a.attr("title").ifBlank{a.parent()?.selectFirst("h4")?.text().orEmpty()}
        if(title.isBlank())return null
        val img=a.attr("data-original").ifBlank{a.selectFirst("img")?.let{it.attr("data-original").ifBlank{it.attr("src")}}.orEmpty()}
        val image=host.toHttpUrlOrNull()?.resolve(img)?.toString()?.takeIf{it.startsWith("https://")}.orEmpty()
        val score=a.selectFirst(".pic-tag")?.text().orEmpty().takeUnless{it=="0.0"||it=="0"}.orEmpty()
        return Movie(path.substringAfterLast('/').substringBefore('.').toLong(),title,image,a.selectFirst(".pic-text")?.text().orEmpty(),score,category)
    }
    fun catalog(html:String,host:String,category:Int=1,allowEmpty:Boolean=false):CatalogPage {
        val doc=Jsoup.parse(html);if(doc.selectFirst(".stui-header__menu")==null)throw SiteException("站点页面暂不可用，请重新检测站点")
        val cards=doc.select("a.stui-vodlist__thumb").mapNotNull{movie(it,host,category)}.distinctBy{it.id}
        if(cards.isEmpty()&&!allowEmpty)throw SiteException("影片列表暂时无法读取")
        val next=doc.select("a[href]").firstOrNull{it.text()=="下一页"}?.let{sitePath(it.attr("href"),host,Regex("/(show|search)/[^/]+\\.html"))}
        val filters=doc.select(".stui-screen__list, #screenbox ul, .screenbox ul").mapNotNull{ul->
            val opts=ul.select("a[href]").mapNotNull{a->sitePath(a.attr("href"),host,Regex("/show/[^/]+\\.html"))?.let{FilterChoice(a.text(),it,a.parent()?.hasClass("active")==true)}}
            val label=ul.selectFirst("li")?.text()?.substringBefore('：').orEmpty()
            if(opts.isEmpty())null else FilterGroup(label,opts)
        }.distinctBy{it.label}
        return CatalogPage(cards,next,filters)
    }
    fun home(html:String,host:String):HomeData {
        val all=catalog(html,host).movies
        val doc=Jsoup.parse(html)
        val sections=(1..5).mapNotNull{id->
            val heading=doc.select("h3 a").firstOrNull{it.attr("href").startsWith("/show/$id-")}
            val block=heading?.closest(".stui-vodlist__head")?.nextElementSibling()?.takeIf{it.hasClass("stui-vodlist")}
            val movies=block?.select("a.stui-vodlist__thumb")?.mapNotNull{movie(it,host,id)}?.distinctBy{it.id}.orEmpty()
            if(movies.isEmpty())null else HomeSection(heading!!.text().trim(),id,movies)
        }
        return HomeData(if(sections.isEmpty())listOf(HomeSection("最近更新",1,all))else sections)
    }
    fun detail(html:String,host:String,original:Movie):Detail {
        val doc=Jsoup.parse(html);val info=doc.selectFirst(".stui-content__detail")?:throw SiteException("影片详情暂时无法读取")
        val title=info.selectFirst("h1")?.text().orEmpty();if(title.isBlank())throw SiteException("影片详情缺少片名")
        val thumb=doc.selectFirst(".stui-content__thumb img")
        val image=thumb?.attr("data-original")?.let{host.toHttpUrlOrNull()?.resolve(it)?.toString()}?.takeIf{it.startsWith("https://")}
        val data=info.select("p.data").joinToString("\n"){it.text()}
        fun field(name:String)=Regex("$name[：:]\\s*([^/\\n]+)").find(data)?.groupValues?.get(1)?.trim().orEmpty()
        val updated=original.copy(title=title,image=image?:original.image,year=field("年份"),area=field("地区"),score=info.selectFirst(".douban")?.text()?.takeUnless{it=="0.0"}.orEmpty())
        val sources=doc.select("ul.stui-content__playlist").mapNotNull{ul->
            val name=generateSequence(ul.previousElementSibling()){it.previousElementSibling()}.firstOrNull{it.hasClass("stui-vodlist__head")}?.selectFirst("h3")?.text().orEmpty()
            val pairs=ul.select("a[href]").mapNotNull{a->
                val path=sitePath(a.attr("href"),host,Regex("/play/\\d+-\\d+-\\d+\\.html"))?:return@mapNotNull null
                val nums=Regex("\\d+").findAll(path).map{it.value}.toList()
                nums[1].toInt() to Episode(nums[2].toInt(),a.text(),path)
            }
            if(name.isBlank()||pairs.isEmpty()||pairs.map{it.first}.distinct().size!=1)null
            else PlaybackSource(pairs.first().first,name,pairs.map{it.second},listOf("下载","网盘","夸克","百度","UC").any{name.contains(it)})
        }
        return Detail(updated,sources.filterNot{it.downloadable},info.selectFirst(".detail-content")?.text().orEmpty(),field("导演"),field("主演"))
    }
    fun player(html:String,pageUrl:String):PlayerConfig {
        val script=Jsoup.parse(html).select("script").map{it.data()}.firstOrNull{Regex("\\bvar\\s+player_\\w+\\s*=").containsMatchIn(it)}?:throw SiteException("此线路暂时无法解析，请切换线路")
        val start=script.indexOf('{');if(start<0)throw SiteException("播放配置格式已变化")
        val data=JSONObject(org.json.JSONTokener(script.substring(start)))
        return PlayerConfig(data.getLong("id"),data.getInt("sid"),data.getInt("nid"),data.optString("from"),data.optInt("encrypt"),data.getString("url"),pageUrl)
    }
}
