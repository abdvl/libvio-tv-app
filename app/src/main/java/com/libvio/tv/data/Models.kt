package com.libvio.tv.data

import com.libvio.tv.Movie

data class Episode(val index:Int,val title:String,val uri:String)
data class PlaybackSource(val sid:Int,val name:String,val episodes:List<Episode>,val downloadable:Boolean=false)
data class Detail(val movie:Movie,val sources:List<PlaybackSource>,val description:String="",val director:String="",val actor:String="",val selectedSource:Int=0) {
    val episodes get()=sources.getOrNull(selectedSource)?.episodes.orEmpty()
}
data class FilterChoice(val label:String,val path:String,val selected:Boolean)
data class FilterGroup(val label:String,val options:List<FilterChoice>)
data class CatalogPage(val movies:List<Movie>,val nextPath:String?,val filters:List<FilterGroup> = emptyList())
data class HomeSection(val title:String,val category:Int,val movies:List<Movie>)
data class HomeData(val sections:List<HomeSection>)
data class PlayerConfig(val internalId:Long,val sid:Int,val nid:Int,val provider:String,val encrypt:Int,val value:String,val pageUrl:String) {
    override fun toString()="PlayerConfig(provider=$provider, sid=$sid, nid=$nid)"
}
class SiteException(message:String):Exception(message)

/** Match semantic episodes, not an index shared between unrelated source arrays. */
fun episodeNumber(title:String):Int?=Regex("^(?:第)?\\s*0*(\\d+)\\s*(?:集|期)?$").matchEntire(title.trim())?.groupValues?.get(1)?.toIntOrNull()
fun matchingEpisode(current:Episode,target:List<Episode>,sourceCount:Int):Episode? {
    val exact=target.filter{it.title==current.title}
    if(exact.size==1)return exact.single()
    val number=episodeNumber(current.title)
    if(number!=null)return target.singleOrNull{episodeNumber(it.title)==number}
    return if(sourceCount==1&&target.size==1)target.single()else null
}
