package app.majung.server.pickup.service;

import app.majung.server.pickup.repository.RegisteredVehicleRepository;
import java.security.SecureRandom;
import java.util.Locale;

/** Generates and normalizes short human-enterable vehicle codes such as VEH-7K2M9A. */
public final class VehicleCodeGenerator {
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private VehicleCodeGenerator() { }

    public static String nextUnique(RegisteredVehicleRepository vehicles) {
        for (int attempt = 0; attempt < 32; attempt++) {
            String candidate = randomCode();
            if (!vehicles.existsByVehicleCode(candidate)) return candidate;
        }
        throw new IllegalStateException("Unable to allocate unique vehicle code");
    }

    public static String randomCode() {
        StringBuilder out = new StringBuilder("VEH-");
        for (int i = 0; i < 6; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    public static String normalizeLookupCode(String raw) {
        if (raw == null) return null;
        String cleaned = raw.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "");
        if (cleaned.startsWith("VEH")) cleaned = cleaned.substring(3);
        if (!cleaned.matches("[A-Z0-9]{6,12}")) return null;
        return "VEH-" + cleaned;
    }
}
