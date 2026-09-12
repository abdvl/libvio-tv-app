package com.libvio.tv.playback

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import android.view.ViewGroup
import android.widget.FrameLayout
import com.libvio.tv.data.PlayerConfig
import com.libvio.tv.data.SiteException
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder
import java.util.Base64

class ResolvedMedia(val url:String,val referer:String,val userAgent:String,val internalId:Long) {
    override fun toString()="ResolvedMedia(internalId=$internalId)"
}

/** Runs only the site's known Artplayer entry; never exposes a native JS interface.
 * Media URLs are accepted from the actual video element once it has metadata.
 * The short-lived WebView is stopped and removed before handing control to Media3.
 */
class WebPlaybackResolver(private val context:Context,private val container:ViewGroup) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(config:PlayerConfig):ResolvedMedia=withContext(Dispatchers.Main.immediate){
        val decoded=when(config.encrypt){
            0->config.value
            1->URLDecoder.decode(config.value,"UTF-8")
            2->URLDecoder.decode(String(Base64.getDecoder().decode(config.value)),"UTF-8")
            else->null
        }
        if(decoded!=null&&decoded.toHttpUrlOrNull()?.isHttps==true){
            return@withContext ResolvedMedia(decoded,config.pageUrl,WebSettings.getDefaultUserAgent(context),config.internalId)
        }
        if(config.encrypt!=3||config.provider !in setOf("BBA","rrmj","NBY"))throw SiteException("此线路暂不支持原生播放，请选择其他线路")
        val page=config.pageUrl.toHttpUrl()
        val endpoint=page.newBuilder().encodedPath("/static/player/artplayer/").query(null).addQueryParameter("url",config.value).build().toString()
        val web=WebView(context)
        var pageError:String?=null
        try{
            web.settings.apply{
                javaScriptEnabled=true;domStorageEnabled=true;mediaPlaybackRequiresUserGesture=true
                allowFileAccess=false;allowContentAccess=false;setSupportMultipleWindows(false)
                mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            web.isFocusable=false;web.isFocusableInTouchMode=false;web.alpha=0f
            web.webChromeClient=object:WebChromeClient(){
                override fun onPermissionRequest(request:PermissionRequest){request.deny()}
                override fun onConsoleMessage(consoleMessage:ConsoleMessage)=true
            }
            web.webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                    val u=request.url.toString().toHttpUrlOrNull()
                    return u==null||!u.isHttps||(request.isForMainFrame&&(u.host!=page.host||u.encodedPath!="/static/player/artplayer/"))
                }
                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){if(request.isForMainFrame)pageError="线路解析页面无法打开，请换源"}
                override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,error:WebResourceResponse){if(request.isForMainFrame)pageError="线路解析暂时不可用，请换源"}
            }
            container.addView(web,FrameLayout.LayoutParams(320,180))
            web.loadUrl(endpoint,mapOf("Referer" to config.pageUrl))
            withTimeout(60_000){
                while(true){
                    pageError?.let{throw SiteException(it)}
                    val value=web.readVideo()
                    if(value!=null){
                        val url=value.optString("url");val u=url.toHttpUrlOrNull()
                        if(u?.isHttps==true&&value.optDouble("duration",0.0)>0&&value.optInt("ready")>=1){
                            return@withTimeout ResolvedMedia(url,endpoint,web.settings.userAgentString,config.internalId)
                        }
                    }
                    delay(250)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        }catch(e:TimeoutCancellationException){throw SiteException("线路加载超时，请重试或切换线路")}
        finally{
            web.stopLoading();web.loadUrl("about:blank");web.onPause();container.removeView(web);web.destroy()
        }
    }
    private suspend fun WebView.readVideo():JSONObject?=suspendCancellableCoroutine{continuation->
        evaluateJavascript("""(function(){const v=document.querySelector('video');if(!v)return null;v.muted=true;const x={url:v.currentSrc||v.src,duration:Number.isFinite(v.duration)?v.duration:0,ready:v.readyState};if(x.duration>0&&x.ready>=1)v.pause();return JSON.stringify(x);})()"""){raw->
            val value=runCatching{(JSONTokener(raw).nextValue() as? String)?.let{JSONObject(it)}}.getOrNull()
            if(continuation.isActive)continuation.resume(value){_,_,_->}
        }
    }
}
