package space.aliwasouf.readreplica;

public final class RoutingContext {

    private static final ThreadLocal<RoutingTarget> CURRENT = new ThreadLocal<>();

    private RoutingContext() {
    }

    public static void set(RoutingTarget target) {
        CURRENT.set(target);
    }

    public static RoutingTarget get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
