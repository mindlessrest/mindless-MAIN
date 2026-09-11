package mindless.lag.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum EnumLagDirection {
    INBOUND,
    OUTBOUND;

    public static final Set<EnumLagDirection> ONLY_INBOUND = Collections.unmodifiableSet(EnumSet.of(INBOUND));
    public static final Set<EnumLagDirection> ONLY_OUTBOUND = Collections.unmodifiableSet(EnumSet.of(OUTBOUND));
    public static final Set<EnumLagDirection> BIDIRECTIONAL = Collections.unmodifiableSet(EnumSet.allOf(EnumLagDirection.class));
}
