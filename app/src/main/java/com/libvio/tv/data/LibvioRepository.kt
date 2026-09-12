package com.libvio.tv.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.libvio.tv.Movie

class LibvioRepository(context:Context) {
    private val preferences=context.getSharedPreferences("sites",Context.MODE_PRIVATE)
    private val mutex=Mutex()
    private val client=OkHttpClient.Builder().connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).followRedirects(false).build()
    private val _host=MutableStateFlow(preferences.getString("host","").orEmpty())
    val host=_host.asStateFlow()
    private val _hosts=MutableStateFlow(preferences.getStringSet("hosts",emptySet()).orEmpty().toList())
    val hosts=_hosts.asStateFlow()
    var checkedAt=preferences.getLong("checkedAt",0L);private set

    private class Redirect(val location:String,val code:Int):Exception()
    private suspend fun request(url:String,form:FormBody?=null):String {
        var current=url;var body=form
        repeat(5){try{return requestOnce(current,body)}catch(r:Redirect){
            current=r.location;if(r.code==301||r.code==302||r.code==303)body=null
        }}
        throw SiteException("站点跳转过多，请重新检测")
    }
    private suspend fun requestOnce(url:String,form:FormBody?=null):String=suspendCancellableCoroutine{continuation->
        val req=Request.Builder().url(url).header("User-Agent","Mozilla/5.0 LIBVIO-TV/0.1").apply{if(form!=null)post(form)}.build()
        val call=client.newCall(req);continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(SiteException("站点连接失败，请重试或重新检测站点"))}
            override fun onResponse(call:Call,response:Response){response.use{r->
                try{
                    if(r.isRedirect){
                        val target=r.header("Location")?.let{req.url.resolve(it)}
                        if(target==null||LibvioParser.siteOrigin(target.toString())!=LibvioParser.siteOrigin(url))throw SiteException("站点地址已变化，请重新检测")
                        throw Redirect(target.toString(),r.code)
                    }
                    if(!r.isSuccessful)throw SiteException(if(r.code==403)"站点暂时限制访问，请换站或稍后重试"else"站点暂时不可用（${r.code}）")
                    val body=r.body?:throw SiteException("站点返回空内容")
                    if(!body.contentType().toString().contains("text/html"))throw SiteException("站点返回了无法识别的页面")
                    val buffer=java.io.ByteArrayOutputStream()
                    body.byteStream().use{stream->val block=ByteArray(8192);while(buffer.size()<=2_000_000){val n=stream.read(block);if(n<0)break;buffer.write(block,0,n)}}
                    val bytes=buffer.toByteArray()
                    if(bytes.size>2_000_000)throw SiteException("站点页面异常，请稍后重试")
                    if(continuation.isActive)continuation.resume(bytes.toString(Charsets.UTF_8))
                }catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
            }}
        })
    }
    suspend fun ensureHost(force:Boolean=false,exclude:String?=null):String=mutex.withLock{
        if(!force&&_host.value.isNotBlank()&&System.currentTimeMillis()-checkedAt<86_400_000)return@withLock _host.value
        val discovered=try{LibvioParser.hosts(request("https://libvio.lol/"))}catch(e:Exception){if(e is CancellationException)throw e;_hosts.value}
        val candidates=(listOf(_host.value,"https://www.libvio.lat")+discovered).filter{it!=exclude&&it.isNotBlank()&&LibvioParser.siteOrigin(it)==it&&(it in discovered||it in _hosts.value)}.distinct()
        for(candidate in candidates){try{
            LibvioParser.catalog(request("$candidate/"),candidate)
            _hosts.value=discovered.ifEmpty{candidates};_host.value=candidate;checkedAt=System.currentTimeMillis()
            preferences.edit().putString("host",candidate).putStringSet("hosts",_hosts.value.toSet()).putLong("checkedAt",checkedAt).apply()
            return@withLock candidate
        }catch(e:Exception){if(e is CancellationException)throw e}}
        throw SiteException("暂时没有可用站点，本机历史仍已保存，请重新检测")
    }
    suspend fun chooseHost(host:String)=mutex.withLock{
        if(host !in _hosts.value)throw SiteException("请选择发布入口列出的站点")
        LibvioParser.catalog(request("$host/"),host)
        _host.value=host;checkedAt=System.currentTimeMillis()
        preferences.edit().putString("host",host).putLong("checkedAt",checkedAt).apply()
    }
    private suspend fun <T> page(path:String,query:String?=null,parse:(String,String)->T):T{
        require(path.startsWith('/')&&!path.startsWith("//")&&!path.contains("..")&&!path.contains('\\'))
        val current=ensureHost();val form=query?.let{FormBody.Builder().add("wd",it).build()}
        try{return parse(request(current+path,form),current)}catch(e:Exception){
            if(e is CancellationException)throw e
            val replacement=ensureHost(force=true,exclude=current)
            if(replacement==current)throw e
            return parse(request(replacement+path,form),replacement)
        }
    }
    suspend fun home():HomeData=page("/",parse=LibvioParser::home)
    suspend fun catalog(path:String,category:Int=1,query:String?=null):CatalogPage=page(path,query){html,origin->LibvioParser.catalog(html,origin,if(query==null)category else 0,allowEmpty=true)}
    suspend fun detail(movie:Movie):Detail=page("/detail/${movie.id}.html"){html,origin->LibvioParser.detail(html,origin,movie)}
    suspend fun player(episode:Episode):PlayerConfig=page(episode.uri){html,origin->LibvioParser.player(html,origin+episode.uri)}
}

fun userError(e:Throwable):String=if(e is SiteException)e.message.orEmpty()else "暂时无法加载，请重试"
