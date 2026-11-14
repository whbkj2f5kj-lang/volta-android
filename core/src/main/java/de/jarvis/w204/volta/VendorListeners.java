package de.jarvis.w204.volta;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Consumer;

/** Read-only listener registration. Prefer live system services over uninitialised SDK wrappers. */
public final class VendorListeners {
    public interface Lookup { Object get(String name) throws Exception; }
    private VendorListeners() {}

    public static Throwable cause(Throwable error) {
        for (int i=0; i<8 && error instanceof InvocationTargetException; i++) {
            Throwable next=((InvocationTargetException)error).getTargetException();
            if (next==null || next==error) break;
            error=next;
        }
        return error;
    }

    public static String describe(Throwable error) {
        Throwable root=cause(error);
        String message=root.getMessage();
        return root.getClass().getName() + (message==null ? "" : ": "+message.replace('\n',' ').replace('\r',' '));
    }

    public static boolean subscribe(Class<?> manager, Method registration, Object listener,
                                    Object context, String[] services, Lookup lookup, Consumer<String> log) {
        Set<Object> tried=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        class Attempt {
            boolean denied;
            boolean register(Object target,String route) {
                if (denied || target==null || !manager.isInstance(target) || !tried.add(target)) return false;
                try {
                    Object result=registration.invoke(target,listener);
                    if (Boolean.FALSE.equals(result)) { log.accept(route+": registration declined"); return false; }
                    log.accept("listener registered via "+route); return true;
                } catch (Throwable error) {
                    denied=cause(error) instanceof SecurityException;
                    log.accept(route+": "+describe(error)); return false;
                }
            }
        }
        if (Modifier.isStatic(registration.getModifiers())) {
            try {
                boolean ok=!Boolean.FALSE.equals(registration.invoke(null,listener));
                log.accept(ok ? "listener registered via static API" : "static API: registration declined");
                return ok;
            } catch (Throwable error) { log.accept("static API: "+describe(error)); return false; }
        }
        Attempt attempt=new Attempt();
        for (String name:services) {
            try { if (attempt.register(lookup.get(name),"system service "+name)) return true; }
            catch (Throwable error) { log.accept("system service "+name+": "+describe(error)); }
            if (attempt.denied) return false;
        }
        for (Method factory:manager.getMethods()) {
            if (!Modifier.isStatic(factory.getModifiers()) || !manager.isAssignableFrom(factory.getReturnType())) continue;
            Class<?>[] args=factory.getParameterTypes();
            if (args.length>1 || (args.length==1 && (context==null || !args[0].isInstance(context)))) continue;
            try {
                Object target=factory.invoke(null,args.length==0 ? new Object[0] : new Object[]{context});
                if (attempt.register(target,"factory "+factory.getName())) return true;
            } catch (Throwable error) { log.accept("factory "+factory.getName()+": "+describe(error)); }
            if (attempt.denied) return false;
        }
        // Context constructors before the no-argument fallback; never stop at an unusable wrapper.
        for (int count:new int[]{1,0}) for (Constructor<?> constructor:manager.getConstructors()) {
            Class<?>[] args=constructor.getParameterTypes();
            if (args.length!=count || (count==1 && (context==null || !args[0].isInstance(context)))) continue;
            try {
                Object target=constructor.newInstance(count==0 ? new Object[0] : new Object[]{context});
                if (attempt.register(target,"constructor "+count)) return true;
            } catch (Throwable error) { log.accept("constructor "+count+": "+describe(error)); }
            if (attempt.denied) return false;
        }
        log.accept("listener unavailable; normal Android input remains active"); return false;
    }
}
