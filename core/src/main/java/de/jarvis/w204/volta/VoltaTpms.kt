package de.jarvis.w204.volta

import android.content.Context
import android.os.SystemClock
import de.jarvis.w204.tpms.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

object VoltaTpms {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private var manager: TpmsManager?=null
    @Volatile private var enabled=false
    @Volatile private var targets: Pair<Double,Double>?=null
    @Volatile private var mapping=listOf(0,1,2,3)
    private val wheels=listOf(Wheel.FL,Wheel.FR,Wheel.RL,Wheel.RR)
    @Synchronized @JvmStatic fun configure(ctx:Context,on:Boolean,front:Double,rear:Double,map:String) {
        enabled=on
        targets=if(front in 1.5..4.0 && rear in 1.5..4.0) front to rear else null
        mapping=map.split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.sorted()==listOf(0,1,2,3) } ?: listOf(0,1,2,3)
        if(manager==null && on)manager=TpmsManager(ctx.applicationContext,scope)
        manager?.setEnabled(on)
    }
    @JvmStatic fun snapshot():String {
        val m=manager;val s=m?.status?.value
        val connected=enabled && s?.link==TpmsLink.CONNECTED
        val now=SystemClock.elapsedRealtime();val values=m?.tires?.value ?: emptyMap()
        val selected=mapping;val pressure=targets
        val out=JSONArray()
        for(i in 0..3) {
            val t=values[wheels[selected[i]]]
            val valid=TirePolicy.valid(t,connected,now)
            val target=if(i<2)pressure?.first else pressure?.second
            out.put(JSONObject().put("wheel",wheels[i].name).put("channel",selected[i]).put("valid",valid)
                .put("pressure",if(valid)t!!.bar else -1).put("temperature",if(valid)t!!.tempC else -999)
                .put("warning",TirePolicy.warning(t,connected,now,target)))
        }
        return JSONObject().put("connected",connected).put("message",if(!enabled)"TPMS ausgeschaltet" else s?.message ?: "Kein TPMS-Empfänger gefunden")
            .put("configured",pressure!=null).put("wheels",out).toString()
    }
}
