package com.rex.twboardingscanner.ui

/** Only stock search and chart tabs are eligible; no order/account controls. */
internal class ChipKNavigation(private val code:String,private val name:String){
    data class Node(val key:Int,val label:String,val id:String="",val editable:Boolean=false,
        val clickable:Boolean=false,val password:Boolean=false,val selected:Boolean=false)
    data class Action(val key:Int,val text:String?=null,val next:Int)
    private var phase=0
    var complete=false;private set
    fun applied(action:Action){phase=action.next}
    fun next(nodes:List<Node>):Action?{
        if(nodes.any{it.password||Regex("委託價格|委託數量|交易密碼|確認下單",RegexOption.IGNORE_CASE).containsMatchIn(it.label)||
            Regex("order_input|order_price|trade_password",RegexOption.IGNORE_CASE).containsMatchIn(it.id)})return null
        val safe=nodes.filterNot{it.password||Regex("買入|賣出|下單|委託|交易密碼|登入|buy|sell|order",RegexOption.IGNORE_CASE).containsMatchIn(it.label+" "+it.id)}
        fun button(labels:Set<String>)=safe.firstOrNull{it.clickable&&it.label.trim() in labels}
        when(phase){
            0->{
                val field=safe.firstOrNull{it.editable&&(it.id.contains("search",true)||Regex("搜尋|搜索|股號|股票代碼").containsMatchIn(it.label))}
                if(field!=null)return Action(field.key,code,1)
                val search=safe.firstOrNull{it.clickable&&!it.editable&&(it.label.trim() in setOf("搜尋","搜索","搜尋股票","股票搜尋")||it.id.contains("search",true))}
                return search?.let{Action(it.key,next=5)}
            }
            1->{
                val exact=Regex("(?<![0-9])"+Regex.escape(code)+"(?![0-9])")
                val result=safe.firstOrNull{it.clickable&&!it.editable&&exact.containsMatchIn(it.label)&&
                    (name.isBlank()||it.label.contains(name))}
                return result?.let{Action(it.key,next=2)}
            }
            2->{
                val day=button(setOf("日K","日Ｋ","日線"))
                if(day!=null){if(day.selected){complete=true;return null};return Action(day.key,next=4)}
                return button(setOf("K線","Ｋ線","K線圖","技術分析","技術"))?.let{Action(it.key,next=3)}
            }
            3->{
                val day=button(setOf("日K","日Ｋ","日線","日"))?:return null
                if(day.selected){complete=true;return null}
                return Action(day.key,next=4)
            }
            5->{
                val field=safe.firstOrNull{it.editable&&(it.id.contains("search",true)||Regex("搜尋|搜索|股號|股票代碼").containsMatchIn(it.label))}
                return field?.let{Action(it.key,code,1)}
            }
            4->complete=true
        }
        return null
    }
}
