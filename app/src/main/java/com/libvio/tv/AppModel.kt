package com.libvio.tv

import androidx.compose.runtime.*
import com.libvio.tv.data.*
import com.libvio.tv.playback.PlaybackController
import kotlinx.coroutines.*

internal class AppModel(val repo:LibvioRepository,val library:LocalLibrary,val player:PlaybackController,val scope:CoroutineScope){
    var home by mutableStateOf<HomeData?>(null);private set
    var homeError by mutableStateOf<String?>(null);private set
    var loadingHome by mutableStateOf(false);private set
    private val feeds=mutableMapOf<String,Feed>()
    init{scope.launch{library.load()};loadHome()}
    fun loadHome(){if(loadingHome)return;loadingHome=true;homeError=null;scope.launch{try{home=repo.home()}catch(e:Exception){if(e is CancellationException)throw e;homeError=userError(e)}finally{loadingHome=false}}}
    fun feed(path:String,category:Int=1,query:String?=null):Feed=feeds.getOrPut("$path|$category|$query"){Feed(repo,scope,path,category,query)}
    fun favorite(movie:Movie){scope.launch{library.favorite(movie)}}
}
internal class Feed(private val repo:LibvioRepository,private val scope:CoroutineScope,val initialPath:String,val category:Int,val query:String?){
    var items by mutableStateOf<List<Movie>>(emptyList());private set
    var filters by mutableStateOf<List<FilterGroup>>(emptyList());private set
    var loading by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null);private set
    var finished by mutableStateOf(false);private set
    private var next=initialPath
    private val visited=mutableSetOf<String>()
    private var job:Job?=null
    private var generation=0
    fun cancel(){generation++;job?.cancel();job=null;loading=false}
    fun load(){if(loading||finished)return;loading=true;error=null;val attempt=++generation
        job=scope.launch{try{
            val path=next;val page=repo.catalog(path,category,query);ensureActive();if(attempt!=generation)return@launch
            items=(items+page.movies).distinctBy{it.id};filters=page.filters;visited+=path
            val candidate=page.nextPath;finished=candidate==null||candidate in visited||page.movies.isEmpty()
            if(!finished)next=candidate!!
        }catch(e:Exception){if(e is CancellationException)throw e;if(attempt==generation)error=userError(e)}finally{if(attempt==generation)loading=false}}
    }
}
