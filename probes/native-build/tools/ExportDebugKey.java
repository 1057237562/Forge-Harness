import java.io.*;
import java.nio.file.*;
import java.security.*;

/** Exports only this probe's generated development key, never an existing user signing key. */
public final class ExportDebugKey {
    public static void main(String[] args) throws Exception {
        KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream input = new FileInputStream(args[0])) { store.load(input, "android".toCharArray()); }
        Files.write(Paths.get(args[1], "debug.pk8"), store.getKey("forge-probe", "android".toCharArray()).getEncoded());
        Files.write(Paths.get(args[1], "debug.x509"), store.getCertificate("forge-probe").getEncoded());
    }
}
