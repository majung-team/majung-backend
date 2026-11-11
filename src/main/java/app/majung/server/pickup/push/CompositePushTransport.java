package app.majung.server.pickup.push;

import org.springframework.stereotype.Component;

/** Routes Web Push (HTTPS) subscriptions to VAPID and iOS `apns://` tokens to APNs. */
@Component
public class CompositePushTransport implements PushTransport {
    private final VapidPushTransport vapid;
    private final ApnsPushTransport apns;

    public CompositePushTransport(VapidPushTransport vapid, ApnsPushTransport apns) {
        this.vapid = vapid;
        this.apns = apns;
    }

    @Override
    public boolean enabled() {
        return vapid.enabled() || apns.enabled();
    }

    @Override
    public boolean webPushEnabled() {
        return vapid.enabled();
    }

    @Override
    public boolean apnsEnabled() {
        return apns.enabled();
    }

    @Override
    public String publicKey() {
        return vapid.publicKey();
    }

    @Override
    public Result send(String endpoint) {
        if (endpoint != null && endpoint.startsWith("apns://")) {
            return apns.send(endpoint, null);
        }
        return vapid.send(endpoint);
    }

    @Override
    public Result send(String endpoint, String p256dh, String auth, String payload) {
        if (endpoint != null && endpoint.startsWith("apns://")) {
            return apns.send(endpoint, payload);
        }
        return vapid.send(endpoint, p256dh, auth, payload);
    }
}
