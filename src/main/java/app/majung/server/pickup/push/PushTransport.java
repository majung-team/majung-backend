package app.majung.server.pickup.push;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
/** Delivers Web Push; endpoint-only subscriptions retain the legacy approaching notification. */
public interface PushTransport {
    public enum Result { SENT, GONE, FAILED }

    public boolean enabled();

    default boolean webPushEnabled() { return enabled(); }

    default boolean apnsEnabled() { return false; }

    /** URL-safe Base64 uncompressed P-256 point the browser needs for subscribe(); null when disabled. */
    public String publicKey();

    public Result send(String endpoint);

    default Result send(String endpoint, String p256dh, String auth, String payload) {
        return send(endpoint);
    }
}
