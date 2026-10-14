package com.rex.twboardingscanner.backtest

import com.rex.twboardingscanner.domain.*
import com.rex.twboardingscanner.ui.BtRobotActivity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.time.LocalDate
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BtRobotSingleOptionTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val c=RadarType.C_LONG_RED_VOLUME
    private fun mask(items:Set<Pair<RadarType,String>>)=BtRobotSpace.key(RadarType.entries.associateWith{t->items.filter{it.first==t}.map{it.second}.toSet()})
    @Test fun everyBlankAcrossSixRadarsIsTestedExactlyOnceWithoutAccumulating() {
        val enabled=RadarType.entries.toSet();val required=mask(setOf(c to "price"));val forbidden=mask(setOf(c to "gain"))
        val keys=BtRobotSingleOption.keys(required,forbidden,enabled)
        assertEquals(BtRobotSpace.allSlots.size-2,keys.size);assertEquals(keys.size,keys.toSet().size)
        var cursor=BigInteger.ZERO;val seen=mutableSetOf<String>()
        for(expected in keys){
            val candidate=BtRobotSingleOption.next(required,forbidden,enabled,cursor){it in seen}!!
            assertEquals(expected,candidate.key)
            assertEquals(1,BigInteger(candidate.key,16).andNot(BigInteger(required,16)).bitCount())
            assertTrue(BtRobotSpace.includes(candidate.key,required,forbidden,enabled))
            assertNotNull(BtRobotSingleOption.option(required,candidate.key,enabled))
            seen+=candidate.key;cursor=candidate.cursor
        }
        assertNull(BtRobotSingleOption.next(required,forbidden,enabled,cursor){it in seen})
        val cOnly=BtRobotSingleOption.keys(required,forbidden,setOf(c))
        assertTrue(cOnly.all{key->BtRobotSpace.rules(key).filterKeys{it!=c}.values.all{it.isEmpty()}})
        val allC=mask(BtRobotSpace.allSlots.filter{it.first==c}.toSet())
        assertEquals(listOf(allC),BtRobotSingleOption.keys(allC,"0",setOf(c)))
        assertTrue(BtRobotSingleOption.keys("0",allC,setOf(c)).isEmpty())
    }
    @Test fun checkpointResumeCardsAndCleanedResultsPreserveOneOptionSequence() {
        runCatching{androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build())}
        val store=BtRobotStore(app)
        val required=mask(setOf(c to "price"))
        val free=setOf(c to "volume",c to "gain")
        val forbidden=mask(BtRobotSpace.allSlots.filter{it.first==c&&it!=(c to "price")&&it !in free}.toSet())
        val settings=BtRobotSpace.settings().copy(enabledRadars=setOf(c))
        val id=store.createSingle(settings,required,forbidden);val token=store.resume(id)
        assertEquals(BigInteger.valueOf(2),store.remaining(id))
        val first=store.next(id)!!
        val trial=settings.copy(rules=BtRobotSpace.rules(first.key))
        val run=BtRun(trial.capital,cash=trial.capital,strategyVersion=4).apply{curve+=BtDay(trial.end,equity)}
        store.commit(id,token,first,BtResult(trial,run,1,1,emptyList(),"fixture"),1)
        assertEquals("volume",store.trials(id,20,0).single().getJSONObject("testedOption").getString("id"))
        assertEquals("volume",store.report(id,first.key)!!.getJSONObject("testedOption").getString("id"))
        val activity=Robolectric.buildActivity(BtRobotActivity::class.java).setup()
        fun find(v:View):TextView? {
            if(v.tag=="robot-tested-option")return v as TextView
            if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
            return null
        }
        assertTrue(find(activity.get().window.decorView)!!.text.contains("本輪新增條件"))
        activity.pause().stop().destroy()
        val day=LocalDate.of(2026,8,5)
        val bars=(0..85).map{i->DailyBar(day.minusDays((85-i).toLong()).atStartOfDay(RuleMetrics.TAIPEI).toInstant().toEpochMilli(),100.0,101.0,99.0,100.0,1000000)}
        BtRobotDataset.write(store.dataset(id),BacktestData.Loaded(listOf(BtSeries("2330","測試",Market.TWSE,bars)),1,emptyList()))
        store.coverage(id,token,1,1,BtRobotCheckpoint.digest(store.dataset(id)))
        store.pause(id)
        val zip=File(app.cacheDir,"single-option-checkpoint.zip")
        BtRobotCheckpoint.export(app,id,zip)
        val restored=zip.inputStream().use{BtRobotCheckpoint.restore(app,it)}
        val copy=BtRobotStore(app)
        assertTrue(BtRobotSingleOption.active(copy.session(restored)))
        assertEquals(BigInteger.ONE,copy.remaining(restored))
        assertEquals(store.next(id)!!.key,copy.next(restored)!!.key)
        assertEquals("gain",BtRobotSingleOption.option(required,copy.next(restored)!!.key,setOf(c))!!.getString("id"))
        val newToken=copy.resume(restored)
        assertEquals(1,copy.clearZero(restored))
        val next=copy.next(restored)!!
        val nextSettings=settings.copy(rules=BtRobotSpace.rules(next.key))
        val nextRun=BtRun(nextSettings.capital,cash=nextSettings.capital+10,strategyVersion=4).apply{curve+=BtDay(nextSettings.end,equity)}
        copy.commit(restored,newToken,next,BtResult(nextSettings,nextRun,1,1,emptyList(),"fixture"),2)
        assertEquals(BigInteger.ZERO,copy.remaining(restored));assertNull(copy.next(restored))
        zip.delete()
    }
}
