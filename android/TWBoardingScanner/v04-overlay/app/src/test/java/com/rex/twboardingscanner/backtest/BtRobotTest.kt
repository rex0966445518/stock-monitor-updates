package com.rex.twboardingscanner.backtest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.math.BigInteger
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rTW-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BtRobotTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun clean(){
        listOf("scanner_filters","backtest_status","backtest_robot").forEach{app.getSharedPreferences(it,0).edit().clear().commit()}
        File(app.filesDir,"backtests").deleteRecursively();File(app.filesDir,"backtest-journal").deleteRecursively()
    }
    private fun bar(day:LocalDate,close:Double,high:Double=close+1)=DailyBar(day.atTime(9,0).atZone(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),close-0.2,high,close-1,close,1200000)
    private fun series(count:Int=3):List<BtSeries>{
        val end=LocalDate.of(2026,8,8)
        return (0 until count).map{i->BtSeries("${2000+i}","測試公司$i",Market.TWSE,(0..85).map{j->
            val price=70.0+i*20+j*.3+kotlin.math.sin(j.toDouble())*2
            bar(end.minusDays((85-j).toLong()),price).copy(volumeShares=(600000+j*12000L))
        },mapOf(end.minusDays(2) to 1.0))}
    }
    private fun empty()=RadarType.entries.associateWith{emptySet<String>()}
    private fun report(settings:BtSettings,pnl:Double):BtResult {
        // Isolated accounting fixture for transaction/restart tests; no network or market claims.
        val run=BtRun(settings.capital,cash=settings.capital+pnl,strategyVersion=4)
        run.curve+=BtDay(settings.end,run.equity)
        return BtResult(settings,run,1,1,emptyList(),"test fixture")
    }
    @Test fun snapshotApplyUpdatesNextDraftAndScannerButNeverFrozenRun(){
        val old=BtSettings(LocalDate.of(2025,8,1),LocalDate.of(2025,8,31))
        val store=BacktestStore(app);store.begin("frozen-apply",old)
        val settings=old.copy(capital=5000000.0,maxHoldingStocks=19,targetNetPct=4.0,codes="2330",rules=empty()+mapOf(RadarType.B_DEEP_REVERSAL to setOf("price","floor")),sectors=setOf(StockSector.entries.first()))
        BtRuleApply.apply(app,BtSettingsCodec.encode(settings))
        assertEquals(settings,BtSettingsCodec.decode(store.configuration()!!))
        assertEquals(old,BtSettingsCodec.decode(store.log("frozen-apply")!!.getJSONObject("settings")))
        assertEquals("RUNNING",store.state())
        val scanner=BtSettingsCodec.capture(app,old.start,old.end,1000000.0,"")
        assertEquals(settings.rules,scanner.rules);assertEquals(settings.sectors,scanner.sectors)
        val before=store.configuration().toString()
        val bad=BtSettingsCodec.encode(settings).put("maxHoldingStocks",0)
        assertThrows(Exception::class.java){BtRuleApply.apply(app,bad)}
        assertEquals(before,store.configuration().toString());assertEquals(settings.rules,BtSettingsCodec.capture(app,old.start,old.end,1.0,"").rules)
        val wrong=BtSettingsCodec.encode(settings).put("rulesVersion","old")
        assertThrows(Exception::class.java){BtRuleApply.apply(app,wrong)}
    }
    @Test fun sixtyFiveIndependentSwitchesRoundTripAndSearchNeverRepeats(){
        assertEquals(65,BtRobotSpace.slots.size)
        assertEquals(BigInteger("36893488147419103232"),BtRobotSpace.total)
        BtRobotSpace.slots.forEachIndexed{i,slot->
            val rules=BtRobotSpace.rules(BigInteger.ONE.shiftLeft(i).toString(16))
            assertEquals(setOf(slot.second),rules[slot.first]);assertEquals(1,rules.values.sumOf{it.size})
            assertEquals(BigInteger.ONE.shiftLeft(i).toString(16),BtRobotSpace.key(rules))
        }
        val seen=mutableSetOf<String>();var cursor=BigInteger.ZERO
        val base=BtRobotSpace.key(defaultBtRules())
        repeat(250){
            val candidate=BtRobotSpace.next(base,base,cursor,BigInteger("123456789")){it in seen}!!
            assertTrue(seen.add(candidate.key));cursor=candidate.cursor
        }
        assertEquals(250,seen.size)
        for(i in BtRobotSpace.slots.indices)assertTrue(BigInteger(base,16).flipBit(i).toString(16) in seen)
        val fixed=BtRobotSpace.settings();assertEquals(5000000.0,fixed.capital,0.0);assertEquals(25,fixed.maxHoldingStocks);assertEquals(4.0,fixed.targetNetPct,0.0)
        listOf(fixed.copy(capital=5000001.0),fixed.copy(maxHoldingStocks=26),fixed.copy(targetNetPct=3.0),fixed.copy(codes="2330")).forEach{assertThrows(Exception::class.java){BtRobotSpace.validate(it)}}
    }
    @Test fun precomputedConditionsMatchOrdinaryScannerForEveryRadarAndMissingData(){
        val data=series();val base=BtRobotSpace.settings().copy(end=LocalDate.of(2026,8,8))
        val index=BtConditionIndex.build(data,base)
        val random=java.util.Random(144)
        val selections=listOf(defaultBtRules(),empty(),BtRobotSpace.rules(BtRobotSpace.total.subtract(BigInteger.ONE).toString(16)))+
            List(20){RadarType.entries.associateWith{type->ScanConditions.forRadar(type).filter{random.nextBoolean()}.map{it.id}.toSet()}}+
            RadarType.entries.flatMap{type->listOf(empty()+mapOf(type to setOf("price")),empty()+mapOf(type to setOf("price","fin_ttm")))}
        selections.forEach{rules->
            val s=base.copy(rules=rules);val reference=BacktestEngine.prepare(data,s);val fast=index.prepare(s)
            assertEquals(reference.signals,fast.signals);assertEquals(reference.pendingChecks,fast.pendingChecks)
            val a=BacktestEngine.run(reference,s);val b=BacktestEngine.run(fast,s,execution=BacktestEngine.executionIndex(data))
            assertEquals(a.trades,b.trades);assertEquals(a.profit,b.profit,.000001)
        }
        val truncated=data.map{s->s.copy(bars=s.bars.filter{RuleMetrics.tradingDate(it.time)<=base.end})}
        assertEquals(index.prepare(base).signals,BtConditionIndex.build(truncated,base).prepare(base).signals)
    }
    @Test fun fixedCashUniqueCapAndFourPercentTargetAreEnforcedBySharedEngine(){
        val dates=listOf(LocalDate.of(2026,8,3),LocalDate.of(2026,8,4),LocalDate.of(2026,8,5))
        val data=(0..30).map{i->BtSeries("${2000+i}","公司$i",Market.TWSE,dates.mapIndexed{j,d->bar(d,100.0,if(j==1)110.0 else 101.0)})}
        val p=BtPrepared(data,dates.associateWith{data.map{BtSignal(it.code,"A")}},dates)
        val s=BtRobotSpace.settings();val r=BacktestEngine.run(p,s)
        assertTrue(r.curve.all{it.cash!!>=0});assertTrue(r.holdings.map{it.code}.distinct().size<=25)
        assertEquals(25,r.curve.first().buys);assertEquals(6,r.curve.first().skipped)
        r.closed.forEach{sell->val buy=r.trades.single{it.side=="BUY"&&it.lotId==sell.lotId};assertTrue(sell.pnl>=(buy.price*1000+buy.fee)*.04-1e-8)}
        assertEquals(r.realized+r.unrealized+r.dividendAccrued+r.rebateAccrued,r.profit,.00001)
        assertTrue(r.trades.all{it.shares==1000})
    }
    @Test fun completedTrialsCheckpointAtomicallyAndPausedWorkersCannotOverwrite(){
        val store=BtRobotStore(app);val s=BtRobotSpace.settings();val id=store.create(s);val token=store.resume(id)
        val first=store.next(id)!!;val settings=s.copy(rules=BtRobotSpace.rules(first.key))
        assertTrue(store.commit(id,token,first,report(settings,-20000.0),123))
        assertFalse(store.commit(id,token,first,report(settings,999999.0),124))
        assertEquals(1,store.session(id)!!.getInt("tested"));assertEquals(-20000.0,store.session(id)!!.getDouble("bestProfit"),0.0)
        val second=store.next(id)!!;store.pause(id)
        assertFalse(store.commit(id,token,second,report(s.copy(rules=BtRobotSpace.rules(second.key)),999999.0),124))
        val reopened=BtRobotStore(app);val newToken=reopened.resume(id);assertNotEquals(token,newToken)
        assertEquals(second.key,reopened.next(id)!!.key)
        assertTrue(reopened.commit(id,newToken,second,report(s.copy(rules=BtRobotSpace.rules(second.key)),5000.0),125))
        assertEquals(2L,reopened.session(id)!!.getLong("tested"));assertEquals(BtRobotSpace.total-BigInteger.valueOf(2),reopened.remaining(id))
        assertEquals(5000.0,reopened.session(id)!!.getDouble("bestProfit"),0.0)
        val restored=reopened.report(id,first.key)!!
        assertEquals(settings,BtSettingsCodec.decode(restored.getJSONObject("settings")));assertEquals(-20000.0,restored.getJSONObject("run").getDouble("profit"),0.0)
        assertEquals(1,reopened.trials(id,1,1).size)
        val newId=store.create(s);assertNotEquals(newId,id);assertEquals(2L,store.session(id)!!.getLong("tested"));assertEquals(0L,store.session(newId)!!.getLong("tested"))
    }
    @Test fun historicalDatasetSurvivesResumeWithoutNetworkOrDateChanges(){
        val data=BacktestData.Loaded(series(),4,listOf("9999：缺資料"));val file=File(app.filesDir,"dataset-test.gz")
        BtRobotDataset.write(file,data);assertEquals(data,BtRobotDataset.read(file))
        file.writeBytes(byteArrayOf(1,2,3));assertThrows(Exception::class.java){BtRobotDataset.read(file)}
    }
    @Test fun snapshotButtonAppliesAndRefreshesAnAlreadyOpenBacktest(){
        val ctl=Robolectric.buildActivity(BacktestActivity::class.java).setup();val a=ctl.get()
        val settings=BtSettings(LocalDate.of(2025,8,1),LocalDate.of(2025,8,31),5000000.0,maxHoldingStocks=22,targetNetPct=4.0)
        BtSnapshotDialog.show(a,BtSettingsCodec.encode(settings))
        val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals("套用規則",dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ctl.pause().resume()
        assertEquals("22",(find(a.window.decorView){it.tag=="max-holding-stocks"} as EditText).text.toString())
        assertEquals("4",(find(a.window.decorView){it.tag=="target-net-pct"} as EditText).text.toString())
        assertNotEquals("RUNNING",BacktestStore(app).state())
        ctl.pause().stop().destroy()
        val main=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(find(main.get().window.decorView){it is TextView&&it.text.toString()=="尚未開始掃描"})
        main.pause().stop().destroy()
    }
    @Test fun robotDashboardFitsPhoneAndBestAlertIsAcknowledgedOnce(){
        val store=BtRobotStore(app);val s=BtRobotSpace.settings();val id=store.create(s);val token=store.resume(id)
        val first=store.next(id)!!;store.commit(id,token,first,report(s.copy(rules=BtRobotSpace.rules(first.key)),12345.0),System.currentTimeMillis())
        store.pause(id)
        val ctl=Robolectric.buildActivity(BtRobotActivity::class.java).setup();val a=ctl.get();a.render()
        val dialog=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(store.session(id)!!.getLong("ack")>0)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        a.render();assertFalse(dialog.isShowing)
        capture(a.window.decorView.findViewById(android.R.id.content),"robot-dashboard-360.png",1600)
        BtSnapshotDialog.show(a,BtSettingsCodec.encode(s),"目前已測最佳 · 條件快照")
        val snapshot=ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        capture(snapshot.window!!.decorView,"snapshot-apply-360.png",1450)
        snapshot.dismiss();ctl.pause().stop().destroy()
    }
    private fun find(v:View,p:(View)->Boolean):View?{if(p(v))return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),p)?.let{return it};return null}
    private fun capture(v:View,name:String,height:Int){
        v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));v.layout(0,0,720,height)
        val image=Bitmap.createBitmap(720,height,Bitmap.Config.ARGB_8888);v.draw(Canvas(image));val f=File("build/ui-previews/$name");f.parentFile!!.mkdirs();f.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
}
