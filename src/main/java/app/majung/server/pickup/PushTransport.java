package app.majung.server.pickup;

/** Delivers an empty web push; the notification text lives in the browser's service worker. */
interface PushTransport {
    enum Result { SENT, GONE, FAILED }

    boolean enabled();

    /** URL-safe Base64 uncompressed P-256 point the browser needs for subscribe(); null when disabled. */
    String publicKey();

    Result send(String endpoint);
}
