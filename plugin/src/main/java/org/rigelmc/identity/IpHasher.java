package org.rigelmc.identity;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jetbrains.annotations.NotNull;

/**
 * Produces a consistent, salted hash of a player's IP address for storage, instead of the
 * raw address. Every ban/IP-history lookup in RigelMCMod compares hashes, never plaintext
 * IPs - the salt makes the stored value non-reversible at rest while still allowing exact
 * equality comparisons (same IP always hashes to the same value for this install), which
 * is all the permban cascade and ban-lookup logic actually needs.
 *
 * <p>The salt is generated once on first run and persisted to {@code ip-salt.bin} in the
 * plugin's data folder - deliberately <b>not</b> config.yml, so it doesn't get copied
 * around or committed to version control alongside a shared config template.</p>
 */
public final class IpHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int SALT_BYTES = 32;
    private static final SecureRandom SALT_RANDOM = new SecureRandom();

    private final Mac mac;

    public IpHasher(@NotNull File dataFolder) {
        try {
            byte[] salt = loadOrCreateSalt(dataFolder);
            this.mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(salt, ALGORITHM));
        } catch (NoSuchAlgorithmException | InvalidKeyException | IOException e) {
            throw new IllegalStateException("Failed to initialize IP hasher", e);
        }
    }

    /**
     * @param ip the raw IP address (e.g. from {@code InetAddress#getHostAddress()})
     * @return a hex-encoded, install-specific salted hash of the IP - safe to store and
     *     compare, but not reversible back to the original address
     */
    @NotNull
    public synchronized String hash(@NotNull String ip) {
        byte[] result = mac.doFinal(ip.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(result);
    }

    private static byte[] loadOrCreateSalt(File dataFolder) throws IOException {
        File saltFile = new File(dataFolder, "ip-salt.bin");
        if (saltFile.exists()) {
            byte[] existing = Files.readAllBytes(saltFile.toPath());
            if (existing.length == SALT_BYTES) {
                return existing;
            }
        }
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("Could not create plugin data folder: " + dataFolder);
        }
        byte[] salt = new byte[SALT_BYTES];
        SALT_RANDOM.nextBytes(salt);
        Files.write(saltFile.toPath(), salt);
        return salt;
    }
}
