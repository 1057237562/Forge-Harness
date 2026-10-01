package dev.forge.compiler;

import dev.forge.build.*;
import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

/** Runtime-owned tools and project-owned signing identity; none are selected by shell text. */
public final class NativeToolchain {
    public final int sdk;
    public final File androidJar, aapt2, zipalign;
    public final Map<String, String> environment;
    public final PrivateKey signingKey;
    public final X509Certificate certificate;
    public final String compilerIdentity;
    public final KotlinBackend kotlin;
    public final File kotlinStdlib;
    public NativeToolchain(int sdk, File androidJar, File aapt2, File zipalign,
                           Map<String, String> environment, PrivateKey signingKey,
                           X509Certificate certificate, String compilerIdentity) {
        this(sdk, androidJar, aapt2, zipalign, environment, signingKey, certificate, compilerIdentity, null, null);
    }
    public NativeToolchain(int sdk, File androidJar, File aapt2, File zipalign,
                           Map<String, String> environment, PrivateKey signingKey,
                           X509Certificate certificate, String compilerIdentity, KotlinBackend kotlin, File kotlinStdlib) {
        this.sdk = sdk; this.androidJar = androidJar; this.aapt2 = aapt2; this.zipalign = zipalign;
        this.environment = Collections.unmodifiableMap(new HashMap<>(environment));
        this.signingKey = signingKey; this.certificate = certificate; this.compilerIdentity = compilerIdentity;
        this.kotlin = kotlin; this.kotlinStdlib = kotlinStdlib;
    }
    public String fingerprint() throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        hashes.put("android.jar", WorkspaceFiles.sha256(androidJar));
        hashes.put("aapt2", WorkspaceFiles.sha256(aapt2));
        hashes.put("zipalign", WorkspaceFiles.sha256(zipalign));
        hashes.put("sdk", String.valueOf(sdk));
        hashes.put("compiler", compilerIdentity);
        if (kotlin != null) { hashes.put("kotlin", kotlin.identity()); hashes.put("kotlin-stdlib", WorkspaceFiles.sha256(kotlinStdlib)); }
        hashes.put("certificate", WorkspaceFiles.hex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
        String libs = environment.get("LD_LIBRARY_PATH");
        if (libs != null) {
            File cpp = new File(libs, "libc++.so");
            if (cpp.isFile()) hashes.put("libc++", WorkspaceFiles.sha256(cpp));
        }
        return InputSnapshot.digest(hashes);
    }
}
