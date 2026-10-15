package io.github.icebridge.fixture;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** Isolated target for proving that a real disabled/hidden package receives the intended URI. */
public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        display(getIntent());
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        display(intent);
    }

    private void display(Intent intent) {
        String uri = intent == null ? null : intent.getDataString();
        Log.i("IceBridgeFixture", "received_uri=" + uri + ";pid=" + android.os.Process.myPid());
        TextView view = new TextView(this);
        view.setText("IceBridge autonomous target\n\nReceived URI:\n" + uri);
        view.setTextSize(22);
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(Color.rgb(35, 35, 48));
        view.setPadding(32, 80, 32, 32);
        setContentView(view);
    }
}
