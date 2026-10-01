package dev.forge.nativeprobe;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.*;

/** Minimal P0 UI. The actual sample compilation runs in the separate builder process. */
public final class ProbeActivity extends Activity {
    private Messenger builder;
    private TextView output;
    private Button run;
    private boolean bound;
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        String line = message.getData().getString("line", "");
        output.append(line + "\n");
        if (message.what == BuilderService.FINISHED) run.setEnabled(true);
        return true;
    }));
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            builder = new Messenger(binder);
            run.setEnabled(true);
            if (getIntent().getBooleanExtra("run", false)) {
                getIntent().removeExtra("run");
                startProbe();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            builder = null;
            run.setEnabled(false);
            output.append("Builder disconnected. Inspect result.json before retrying.\n");
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        run = new Button(this);
        run.setText("Run native Java/XML build");
        run.setEnabled(false);
        run.setOnClickListener(v -> startProbe());
        root.addView(run);
        Button cancel = new Button(this);
        cancel.setText("Cancel build");
        cancel.setOnClickListener(v -> {
            if (builder != null) try { builder.send(Message.obtain(null, BuilderService.CANCEL)); }
            catch (RemoteException error) { output.append(error.toString()); }
        });
        root.addView(cancel);
        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setText("P0 probe: ECJ / aapt2 / D8 / zipalign / apksig\nNo Termux, PRoot or Gradle in this APK.\n");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        root.addView(scroll);
        setContentView(root);
        bound = bindService(new Intent(this, BuilderService.class), connection, BIND_AUTO_CREATE);
    }
    private void startProbe() {
        if (builder == null) return;
        run.setEnabled(false);
        Message request = Message.obtain(null, BuilderService.RUN);
        Bundle data = new Bundle();
        data.putString("scenario", getIntent().getStringExtra("scenario"));
        request.setData(data);
        request.replyTo = replies;
        try { builder.send(request); }
        catch (RemoteException e) { output.append(e.toString()); run.setEnabled(true); }
    }
    @Override protected void onDestroy() {
        if (bound) unbindService(connection);
        super.onDestroy();
    }
}
