package de.jarvis.w204
import de.jarvis.w204.obd.*
import org.junit.Assert.*
import org.junit.Test
class DiagnosticEvidenceTest {
 @Test fun pendingDoesNotHideLaterMatchingPositive() {
  assertEquals("62200705FC", DiagnosticAnswer.select("7F2278\r6220110134\r62200705FC", "222007").pdu)
  assertEquals(DiagnosticAnswer.Kind.PENDING, DiagnosticAnswer.select("7F2278", "222007").kind)
  assertEquals(DiagnosticAnswer.Kind.NEGATIVE, DiagnosticAnswer.select("7F2278\r7F2231", "222007").kind)
  assertEquals(DiagnosticAnswer.Kind.UNEXPECTED, DiagnosticAnswer.select("6220110134", "222007").kind)
 }
 @Test fun separatePendingPromptsWaitForFinalWithoutResending() {
  val b=ElmReplyBuffer("222007")
  for(c in "7E8 03 7F 22 78\r>") assertNull(b.accept(c))
  assertNotNull(b.pendingAtDeadline())
  var final:String?=null
  for(c in "7E8 05 62 20 07 05 FC\r>") final=b.accept(c)?:final
  assertNotNull(final)
  assertTrue(final!!.contains("7F 22 78"))
  assertNull(b.pendingAtDeadline())
 }
 @Test fun dlcAndHeaderlessPendingAreRecognised() {
  assertTrue(DiagnosticAnswer.pendingInRaw("7E8 8 03 7F 22 78 00 00 00 00", "222007"))
  assertTrue(DiagnosticAnswer.pendingInRaw("7F2278", "222007"))
  assertFalse(DiagnosticAnswer.pendingInRaw("OK", "ATH1"))
  assertFalse(DiagnosticAnswer.pendingInRaw("7F2178", "222007"))
 }
 @Test fun traceRetainsWrongSourceEvenWhenValueRejected() {
  val trace=mutableListOf<String>()
  val e=SafeElm {c,_->if(c.startsWith("AT")) "OK" else "7E80762F10002110E01"}
  e.trace={trace+=it};e.target("7E1")
  assertEquals("NO DATA",e.request("22F100"))
  assertTrue(trace.any{it.contains("7E80762F10002110E01")&&it.contains("RX=7E9")})
 }
 @Test fun rejectedFilterBlocksQueriesAndRestoresHeader() {
  val commands=mutableListOf<String>()
  val e=SafeElm{c,_->commands+=c;if(c=="ATCRA7E9") "?" else "OK"}
  try{e.target("7E1");fail()}catch(_:IllegalStateException){}
  try{e.request("2130");fail()}catch(_:IllegalStateException){}
  assertFalse(commands.contains("2130"));e.restore()
  assertEquals(listOf("ATH0","ATCRA","ATSH7DF"),commands.takeLast(3))
 }
 @Test fun probeBracketsReferencesAndDoesNotCertifyDpf() {
  val commands=mutableListOf<String>();var time=0L
  val lines=Crd3Probe.run(setOf(0x0C,0x42),{c,_->commands+=c;when(c){
   "010C"->"410C0BB8";"225017"->"7F2278\r6250170BB8";"0142"->"41422EE0";"222007"->"6220070600";"22604B"->"62604B14B0";else->"NO DATA"
  }},{time+=100;time})
  val rpm=commands.indexOf("225017");val volts=commands.indexOf("222007")
  assertEquals(listOf("010C","225017","010C"),commands.subList(rpm-1,rpm+2))
  assertEquals(listOf("0142","222007","0142"),commands.subList(volts-1,volts+2))
  assertEquals(1,commands.count{it=="22604B"})
  assertTrue(lines.any{it.contains("rpm: REFERENZ_PASST")})
  assertTrue(lines.any{it.contains("Keine unabhaengige Referenz fuer dpfSootG")})
  assertTrue(commands.all{ReadOnly.isAllowed(it)})
 }
}
