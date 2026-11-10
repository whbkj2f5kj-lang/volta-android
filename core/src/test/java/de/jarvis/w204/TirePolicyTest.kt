package de.jarvis.w204
import de.jarvis.w204.tpms.*
import org.junit.Assert.*
import org.junit.Test
class TirePolicyTest {
 private fun t(kpa:Int=240,at:Long=1000,noSignal:Boolean=false,leak:Boolean=false)=TireState(Wheel.FL,kpa,20,noSignal,leak,false,at)
 @Test fun missingDisconnectedAndStaleAreUnknown(){assertFalse(TirePolicy.valid(null,true,1000));assertFalse(TirePolicy.valid(t(),false,1000));assertFalse(TirePolicy.valid(t(),true,302000));assertFalse(TirePolicy.valid(t(noSignal=true),true,1000))}
 @Test fun noWarningsFromUnknown(){assertEquals("",TirePolicy.warning(t(leak=true),false,1000,2.4));assertEquals("",TirePolicy.warning(t(leak=true,noSignal=true),true,1000,2.4))}
 @Test fun leakAndFlatAreCriticalWithoutCalibration(){assertEquals("leak",TirePolicy.warning(t(leak=true),true,1000,null));assertEquals("critical_pressure",TirePolicy.warning(t(0),true,1000,null))}
 @Test fun pressureNeedsUserTargets(){assertEquals("",TirePolicy.warning(t(180),true,1000,null));assertEquals("low_pressure",TirePolicy.warning(t(200),true,1000,2.4))}
 @Test fun assignmentSwapsAndNeverDuplicates(){assertEquals(listOf(3,1,2,0),TirePolicy.assign(listOf(0,1,2,3),0,3))}
}
