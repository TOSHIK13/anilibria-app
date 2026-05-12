package ru.radiationx.player.tv;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;

import androidx.activity.ComponentActivity;
import androidx.media3.common.util.UnstableApi;

@UnstableApi
public class ReusableTvPlayerActivity extends ComponentActivity {

    private ReusableTvPlayerView playerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = createPlayerView();
        setContentView(playerView);
        playerView.play(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (playerView != null) {
            playerView.play(intent);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (playerView != null) {
            playerView.handleStart();
        }
    }

    @Override
    protected void onStop() {
        if (playerView != null) {
            playerView.handleStop();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (playerView != null) {
            playerView.release();
            playerView = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return playerView != null && playerView.handleKeyEvent(event)
                || super.dispatchKeyEvent(event);
    }

    protected ReusableTvPlayerView createPlayerView() {
        return new ReusableTvPlayerView(this);
    }
}
