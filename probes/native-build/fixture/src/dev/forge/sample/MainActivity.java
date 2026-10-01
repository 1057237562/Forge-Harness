package dev.forge.sample;

public final class MainActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.main);
        android.widget.TextView message = findViewById(R.id.message);
        message.setContentDescription("native-build-ok");
        // Read the actual inflated view, so device tests can verify it even when the keyguard covers it.
        try (java.io.FileOutputStream out = openFileOutput("activity-result.txt", MODE_PRIVATE)) {
            String result = message.getText() + "\n" + message.getContentDescription();
            out.write(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot persist activity verification", e);
        }
    }
}
