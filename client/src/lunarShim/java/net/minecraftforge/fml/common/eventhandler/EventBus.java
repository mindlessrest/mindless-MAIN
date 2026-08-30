package net.minecraftforge.fml.common.eventhandler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loader-independent Forge 1.8.9 EventBus used by the Lunar MCP payload.
 *
 * Forge's stock EventBus asks Loader.instance() for an owning mod every time a
 * listener is registered.  A plain Lunar + OptiFine process has no initialized
 * FML loader, even though Mindless only needs the event contract.  This binary-
 * compatible implementation keeps that contract without starting a second mod
 * loader inside Lunar.
 */
public class EventBus implements IEventExceptionHandler {
    private final CopyOnWriteArrayList<Handler> handlers =
            new CopyOnWriteArrayList<Handler>();
    private final Map<Object, List<Handler>> owners =
            Collections.synchronizedMap(new IdentityHashMap<Object, List<Handler>>());
    private final IEventExceptionHandler exceptionHandler;

    public EventBus() {
        this.exceptionHandler = this;
    }

    public EventBus(IEventExceptionHandler exceptionHandler) {
        if (exceptionHandler == null) {
            throw new IllegalArgumentException("EventBus exception handler cannot be null");
        }
        this.exceptionHandler = exceptionHandler;
    }

    public void register(Object target) {
        if (target == null) return;

        synchronized (owners) {
            if (owners.containsKey(target)) return;

            List<Handler> discovered = new ArrayList<Handler>();
            for (Method method : target.getClass().getMethods()) {
                SubscribeEvent annotation = method.getAnnotation(SubscribeEvent.class);
                if (annotation == null || method.isBridge() || method.isSynthetic()) continue;

                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 1 || !Event.class.isAssignableFrom(parameters[0])) {
                    throw new IllegalArgumentException("Invalid @SubscribeEvent method " + method);
                }
                method.setAccessible(true);
                discovered.add(new Handler(target, method, parameters[0],
                        annotation.priority(), annotation.receiveCanceled()));
            }

            Collections.sort(discovered, new Comparator<Handler>() {
                @Override
                public int compare(Handler left, Handler right) {
                    return left.priority.ordinal() - right.priority.ordinal();
                }
            });
            owners.put(target, discovered);
            handlers.addAll(discovered);
            sortHandlers();
        }
    }

    public void unregister(Object target) {
        if (target == null) return;
        synchronized (owners) {
            List<Handler> registered = owners.remove(target);
            if (registered != null) handlers.removeAll(registered);
        }
    }

    public boolean post(Event event) {
        if (event == null) return false;

        int index = 0;
        for (Handler handler : handlers) {
            if (!handler.eventType.isAssignableFrom(event.getClass())) continue;
            if (event.isCancelable() && event.isCanceled() && !handler.receiveCanceled) continue;
            try {
                handler.method.invoke(handler.owner, event);
            } catch (Throwable failure) {
                Throwable cause = failure instanceof InvocationTargetException
                        && ((InvocationTargetException) failure).getCause() != null
                        ? ((InvocationTargetException) failure).getCause() : failure;
                exceptionHandler.handleException(this, event,
                        new IEventListener[0], index, cause);
            }
            ++index;
        }
        return event.isCancelable() && event.isCanceled();
    }

    @Override
    public void handleException(EventBus bus, Event event, IEventListener[] listeners,
                                int index, Throwable throwable) {
        System.err.println("[Pigeon/Lunar] Event handler failed for "
                + event.getClass().getName() + ": " + throwable);
        throwable.printStackTrace(System.err);
    }

    private void sortHandlers() {
        List<Handler> sorted = new ArrayList<Handler>(handlers);
        Collections.sort(sorted, new Comparator<Handler>() {
            @Override
            public int compare(Handler left, Handler right) {
                return left.priority.ordinal() - right.priority.ordinal();
            }
        });
        handlers.clear();
        handlers.addAll(sorted);
    }

    private static final class Handler {
        private final Object owner;
        private final Method method;
        private final Class<?> eventType;
        private final EventPriority priority;
        private final boolean receiveCanceled;

        private Handler(Object owner, Method method, Class<?> eventType,
                        EventPriority priority, boolean receiveCanceled) {
            this.owner = owner;
            this.method = method;
            this.eventType = eventType;
            this.priority = priority;
            this.receiveCanceled = receiveCanceled;
        }
    }
}
