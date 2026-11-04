package app.majung.server.pickup.push;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
/** Delivers an empty web push; the notification text lives in the browser's service worker. */
public interface PushTransport {
    public enum Result { SENT, GONE, FAILED }

    public boolean enabled();

    /** URL-safe Base64 uncompressed P-256 point the browser needs for subscribe(); null when disabled. */
    public String publicKey();

    public Result send(String endpoint);
}
