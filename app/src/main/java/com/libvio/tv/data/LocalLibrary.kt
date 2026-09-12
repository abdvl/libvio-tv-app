package com.libvio.tv.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.libvio.tv.Movie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class WatchRecord(val movie:Movie,val source:String,val sid:Int,val episode:Int,val label:String,val positionMs:Long,val durationMs:Long,val updatedAt:Long)
object MovieJson {
    fun encode(m:Movie)=JSONObject().put("id",m.id).put("title",m.title).put("image",m.image).put("note",m.note).put("score",m.score).put("category",m.category).put("year",m.year).put("area",m.area).put("internalId",m.internalId).toString()
    fun decode(s:String):Movie {val o=JSONObject(s);return Movie(o.getLong("id"),o.getString("title"),o.optString("image"),o.optString("note"),o.optString("score"),o.optInt("category",1),o.optString("year"),o.optString("area"),o.optLong("internalId").takeIf{it>0})}
}
class LocalLibrary(context:Context):SQLiteOpenHelper(context,"library.db",null,1) {
    private val writer=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val pending=Channel<WatchRecord>(Channel.UNLIMITED)
    private val preferences=context.getSharedPreferences("search",Context.MODE_PRIVATE)
    private val _searches=MutableStateFlow(runCatching{val a=org.json.JSONArray(preferences.getString("queries","[]"));List(a.length()){a.getString(it)}}.getOrDefault(emptyList()))
    val searches=_searches.asStateFlow()
    init{writer.launch{for(record in pending)save(record)}}
    fun enqueue(record:WatchRecord){pending.trySend(record)}
    fun rememberSearch(query:String){_searches.value=(listOf(query)+_searches.value).distinct().take(20);preferences.edit().putString("queries",org.json.JSONArray(_searches.value).toString()).apply()}
    private val _records=MutableStateFlow<List<WatchRecord>>(emptyList());val records=_records.asStateFlow()
    private val _favorites=MutableStateFlow<List<Movie>>(emptyList());val favorites=_favorites.asStateFlow()
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE watches(id INTEGER PRIMARY KEY, internal INTEGER, movie TEXT NOT NULL, source TEXT NOT NULL, sid INTEGER NOT NULL, episode INTEGER NOT NULL, label TEXT NOT NULL, position INTEGER NOT NULL, duration INTEGER NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE favorites(id INTEGER PRIMARY KEY, movie TEXT NOT NULL, updated INTEGER NOT NULL)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
    private fun publish(){
        val records=mutableListOf<WatchRecord>()
        readableDatabase.rawQuery("SELECT * FROM watches ORDER BY updated DESC",null).use{c->while(c.moveToNext()){
            runCatching{WatchRecord(MovieJson.decode(c.getString(2)),c.getString(3),c.getInt(4),c.getInt(5),c.getString(6),c.getLong(7),c.getLong(8),c.getLong(9))}.getOrNull()?.let{records+=it}
        }};_records.value=records
        val favorites=mutableListOf<Movie>()
        readableDatabase.rawQuery("SELECT movie FROM favorites ORDER BY updated DESC",null).use{c->while(c.moveToNext())runCatching{MovieJson.decode(c.getString(0))}.getOrNull()?.let{favorites+=it}}
        _favorites.value=favorites
    }
    suspend fun load()=withContext(Dispatchers.IO){synchronized(this@LocalLibrary){publish()}}
    suspend fun save(record:WatchRecord)=withContext(Dispatchers.IO){if(record.positionMs<1000)return@withContext
        synchronized(this@LocalLibrary){
            val db=writableDatabase;db.beginTransaction()
            try{
                record.movie.internalId?.let{db.delete("watches","internal=? AND id!=?",arrayOf(it.toString(),record.movie.id.toString()))}
                db.insertWithOnConflict("watches",null,ContentValues().apply{
                    put("id",record.movie.id);put("internal",record.movie.internalId);put("movie",MovieJson.encode(record.movie));put("source",record.source);put("sid",record.sid);put("episode",record.episode);put("label",record.label);put("position",record.positionMs);put("duration",record.durationMs);put("updated",record.updatedAt)
                },SQLiteDatabase.CONFLICT_REPLACE);db.setTransactionSuccessful()
            }finally{db.endTransaction()};publish()
        }
    }
    suspend fun remove(id:Long?)=withContext(Dispatchers.IO){synchronized(this@LocalLibrary){writableDatabase.delete("watches",if(id==null)null else "id=?",id?.let{arrayOf(it.toString())});publish()}}
    suspend fun favorite(movie:Movie)=withContext(Dispatchers.IO){synchronized(this@LocalLibrary){
        if(_favorites.value.any{it.id==movie.id})writableDatabase.delete("favorites","id=?",arrayOf(movie.id.toString()))
        else writableDatabase.insertWithOnConflict("favorites",null,ContentValues().apply{put("id",movie.id);put("movie",MovieJson.encode(movie));put("updated",System.currentTimeMillis())},SQLiteDatabase.CONFLICT_REPLACE)
        publish()
    }}
}
