package de.jarvis.w204.volta;
import org.junit.Test;
import static org.junit.Assert.*;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public class VendorListenersTest {
    public static class Manager {
        public static int constructors;
        boolean ready;
        public Manager() { constructors++; }
        public void listen(Object listener) { if (!ready) throw new IllegalStateException("service not connected"); }
    }
    public static class FactoryManager {
        public FactoryManager() {}
        public static FactoryManager getInstance() { return new FactoryManager(); }
        public static FactoryManager getDefault() { FactoryManager m=new FactoryManager();m.ready=true;return m; }
        boolean ready;
        public void listen(Object l) { if (!ready) throw new IllegalStateException("not ready"); }
    }
    public static class DeniedManager {
        public static int constructions;
        public DeniedManager() { constructions++; }
        public void listen(Object l) { throw new SecurityException("permission required"); }
    }
    @Test public void liveSystemServiceWinsOverEmptyWrapper() throws Exception {
        Manager live=new Manager();live.ready=true;Manager.constructors=0;List<String> log=new ArrayList<>();
        assertTrue(VendorListeners.subscribe(Manager.class,Manager.class.getMethod("listen",Object.class),new Object(),new Object(),new String[]{"util_service"},n->live,log::add));
        assertEquals(0,Manager.constructors);
    }
    @Test public void failedFactoryDoesNotPreventTryingUsableFactory() throws Exception {
        List<String> log=new ArrayList<>();
        FactoryManager broken=new FactoryManager();
        assertTrue(VendorListeners.subscribe(FactoryManager.class,FactoryManager.class.getMethod("listen",Object.class),new Object(),new Object(),new String[]{"broken"},n->broken,log::add));
        assertTrue(log.stream().anyMatch(s->s.contains("system service broken: java.lang.IllegalStateException: not ready")));
    }
    @Test public void permissionFailureIsReportedAndDoesNotProbeConstructors() throws Exception {
        DeniedManager live=new DeniedManager();DeniedManager.constructions=0;List<String> log=new ArrayList<>();
        assertFalse(VendorListeners.subscribe(DeniedManager.class,DeniedManager.class.getMethod("listen",Object.class),new Object(),null,new String[]{"mcu_service"},n->live,log::add));
        assertEquals(0,DeniedManager.constructions);assertTrue(log.stream().anyMatch(s->s.contains("SecurityException: permission required")));
    }
    @Test public void reportsUnderlyingExceptionInsteadOfReflectionWrapper() {
        assertEquals("java.lang.IllegalStateException: offline",VendorListeners.describe(new InvocationTargetException(new InvocationTargetException(new IllegalStateException("offline")))));
    }
}
