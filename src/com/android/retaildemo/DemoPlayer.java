/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.retaildemo;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.UserManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.VideoView;

import java.io.File;

/**
 * This is the prototyping activity for playing the retail demo video. This will also try to keep
 * the screen on.
 *
 * Note: this activity checks for the file retail_video.mp4 in the path specified by the system
 * property "ro.retaildemo.video_path". To run this, push your test video to that path.
 */
public class DemoPlayer extends Activity {

    private static final String TAG = "DemoPlayer";
    private static final String PRELOADED_VIDEO_FILE = Environment.getDataPreloadsDemoDirectory()
            + File.separator + "retail_demo.mp4";

    private VideoView mVideoView;
    private PowerManager mPowerManager;
    private int mVideoPosition;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        // Make view full screen
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.STATUS_BAR_DISABLE_BACK
        );
        setContentView(R.layout.retail_video);

        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        mVideoView = (VideoView) findViewById(R.id.video_content);

        // Start playing the video when it is ready
        mVideoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mediaPlayer) {
                mediaPlayer.setLooping(true);
                mVideoView.start();
                // if we pause before screen off, try to resume
                if (mVideoPosition > 0) {
                    mVideoView.seekTo(mVideoPosition);
                }
            }
        });

        loadVideo();
    }

    private void loadVideo() {
        // Load the video from resource
        try {
            mVideoView.setVideoPath(PRELOADED_VIDEO_FILE);
        } catch (Exception e) {
            Log.e(TAG, "Exception setting video uri! " + e.getMessage());
            // If video cannot be load, reset retail mode
            finish();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (getSystemService(UserManager.class).isDemoUser()) {
            disableSelf();
        }
        return true;
    }

    private void disableSelf() {
        getPackageManager().setComponentEnabledSetting(getComponentName(),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
    }

    @Override
    public void onPause() {
        // Save video position
        if (mVideoView != null) {
            mVideoPosition = mVideoView.getCurrentPosition();
            mVideoView.pause();
        }
        // If power key is pressed to turn screen off, turn screen back on
        if (!mPowerManager.isInteractive()) {
            forceTurnOnScreen();
        }
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Resume video playing
        if (mVideoView != null) {
            mVideoView.resume();
        }
    }

    @Override
    protected void onStop() {
        // Stop video
        if (mVideoView != null) {
            mVideoView.stopPlayback();
        }
        super.onStop();
    }

    private void forceTurnOnScreen() {
        final PowerManager.WakeLock wakeLock = mPowerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
        wakeLock.acquire();
        // Device waken up, release the wake-lock
        wakeLock.release();
    }
}
