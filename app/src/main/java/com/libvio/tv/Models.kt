package com.libvio.tv

data class Movie(val id:Long,val title:String,val image:String="",val note:String="",val score:String="",
                 val category:Int=0,val year:String="",val area:String="",val internalId:Long?=null)
data class Hero(val id:Long,val title:String,val image:String,val note:String)
