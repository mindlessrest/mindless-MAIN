package mindless.lag.api;

import mindless.lag.service.PacketDelayService;

import java.util.concurrent.atomic.AtomicBoolean;

public final class DelayLease {
    private final PacketDelayService service;
    private final DelayRequest request;
    private final long id;
    private final SessionEpoch acquiredEpoch;
    private final AtomicBoolean active = new AtomicBoolean(true);

    public DelayLease(PacketDelayService service, DelayRequest request, long id) {
        this(service, request, id, service == null ? null : service.getCurrentEpoch());
    }

    public DelayLease(PacketDelayService service, DelayRequest request, long id, SessionEpoch acquiredEpoch) {
        if (service == null) throw new IllegalArgumentException("service");
        if (request == null) throw new IllegalArgumentException("request");
        if (acquiredEpoch == null) throw new IllegalArgumentException("acquiredEpoch");
        this.service = service;
        this.request = request;
        this.id = id;
        this.acquiredEpoch = acquiredEpoch;
    }

    public DelayRequest getRequest() {
        return request;
    }

    public long getId() {
        return id;
    }

    public SessionEpoch getAcquiredEpoch() {
        return acquiredEpoch;
    }

    public String getOwnerLabel() {
        return request.getOwnerLabel();
    }

    public boolean isActive() {
        return active.get();
    }

    public void release() {
        if (active.compareAndSet(true, false)) service.release(this);
    }

    public void releaseClaims() {
        service.releaseClaims(this);
    }

    public void releaseNext(EnumLagDirection direction) {
        service.releaseNext(this, direction);
    }

    public void releaseExpired(EnumLagDirection direction) {
        service.releaseExpired(this, direction);
    }

    public void deactivate() {
        active.set(false);
        service.release(this);
    }

    public void markInactive() {
        active.set(false);
    }
}
