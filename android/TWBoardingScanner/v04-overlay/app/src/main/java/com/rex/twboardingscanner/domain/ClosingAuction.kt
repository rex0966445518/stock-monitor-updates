package com.rex.twboardingscanner.domain

import java.util.Locale

enum class AuctionDirection { UP, DOWN, FLAT, UNKNOWN }
data class ClosingAuction(val date:String="",val time:String="13:30:00",val lots:Long?=null,
    val price:Double?=null,val beforePrice:Double?=null,val note:String="資料待查核") {
    val direction:AuctionDirection get() = when {
        lots==null || price==null || beforePrice==null -> AuctionDirection.UNKNOWN
        price>beforePrice -> AuctionDirection.UP
        price<beforePrice -> AuctionDirection.DOWN
        else -> AuctionDirection.FLAT
    }
    val display:String get() = lots?.let {
        (when(direction){AuctionDirection.UP->"＋";AuctionDirection.DOWN->"−";else->""})+String.format(Locale.TAIWAN,"%,d 張",it)
    } ?: "待查核"
}
