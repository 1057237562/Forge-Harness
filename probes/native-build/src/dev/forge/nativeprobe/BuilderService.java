package dev.forge.nativeprobe;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.forge.compiler.Cancellation;

/** Non-exported bound service; no shell RPC or arbitrary paths accepted by the P0 harness. */
public final class BuilderService extends Service {
    public static final int RUN = 1, LOG = 2, FINISHED = 3, CANCEL = 4;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Cancellation cancellation;
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what == CANCEL) {
            if (running.get() && cancellation != null) cancellation.cancel();
            return true;
        }
        if (message.what != RUN) return false;
        Messenger client = message.replyTo;
        if (!running.compareAndSet(false, true)) {
            send(client, FINISHED, "BUSY: another native build is running");
            return true;
        }
        String scenario = message.getData().getString("scenario", "baseline");
        cancellation = new Cancellation();
        new Thread(() -> build(client, scenario == null ? "baseline" : scenario), "native-build").start();
        return true;
    }));
    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }

    private void build(Messenger client, String scenario) {
        long started = SystemClock.elapsedRealtime();
        String buildId = UUID.randomUUID().toString();
        File runDir = new File(getFilesDir(), "runs/" + buildId);
        JSONObject result = new JSONObject();
        NativeBuildEngine engine = null;
        try {
            if (!runDir.mkdirs()) throw new IOException("Cannot create build directory");
            result.put("buildId", buildId).put("scenario", scenario).put("state", "RUNNING")
                .put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS[0])
                .put("pid", android.os.Process.myPid()).put("backend", "android-native")
                .put("termux", false).put("proot", false).put("gradle", false);
            saveResult(result, runDir);
            try (PrintWriter log = new PrintWriter(new File(runDir, "build.log"), "UTF-8")) {
                engine = new NativeBuildEngine(this, runDir, line -> {
                    log.println(line); log.flush();
                    android.util.Log.i("ForgeNativeProbe", line);
                    send(client, LOG, line);
                }, cancellation);
                File artifact = engine.build(scenario);
                result.put("state", "SUCCEEDED").put("artifact", artifact.getAbsolutePath())
                    .put("sha256", NativeBuildEngine.sha256(artifact))
                    .put("stagesMs", engine.timings()).put("signatureVerified", true)
                    .put("cacheHit", engine.cacheHit()).put("sourceSnapshotId", engine.snapshotId());
            }
        } catch (Throwable error) {
            try {
                result.put("state", cancellation.isCancelled() ? "CANCELLED" : "FAILED").put("error", android.util.Log.getStackTraceString(error));
            } catch (Exception ignored) { }
            send(client, LOG, android.util.Log.getStackTraceString(error));
            android.util.Log.e("ForgeNativeProbe", "Build failed", error);
        } finally {
            try {
                result.put("durationMs", SystemClock.elapsedRealtime() - started);
                if (engine != null) result.put("stagesMs", engine.timings());
                if (engine != null) result.put("diagnostics", engine.diagnostics());
                saveResult(result, runDir);
            } catch (Exception e) { send(client, LOG, "Result persistence failed: " + e); }
            running.set(false);
            send(client, FINISHED, result.toString());
        }
    }
    private void saveResult(JSONObject result, File runDir) throws Exception {
        byte[] bytes = result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(new File(runDir, "result.json").toPath(), bytes);
        File temp = new File(getFilesDir(), "result.json.tmp");
        java.nio.file.Files.write(temp.toPath(), bytes);
        java.nio.file.Files.move(temp.toPath(), new File(getFilesDir(), "result.json").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
    private static void send(Messenger client, int what, String line) {
        if (client == null) return;
        Message event = Message.obtain(null, what);
        Bundle data = new Bundle(); data.putString("line", line); event.setData(data);
        try { client.send(event); } catch (RemoteException ignored) { }
    }
}
