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
import android.os.SystemClock;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.VideoView;

import java.io.File;

/**
 * This is the activity for playing the retail demo video. This will also try to keep
 * the screen on.
 *
 * This will check for the demo video in {@link Environment#getDataPreloadsDemoDirectory()} or
 * {@link Context#getObbDir()}. If the demo video is not present, it will run a task to download it
 * from the specified url.
 */
public class DemoPlayer extends Activity implements DownloadVideoTask.ResultListener {

    private static final String TAG = "DemoPlayer";
    private static final boolean DEBUG = false;

    private static final String VIDEO_FILE_NAME = "retail_demo.mp4";
    static final String PRELOADED_VIDEO_FILE = Environment.getDataPreloadsDemoDirectory()
            + File.separator + VIDEO_FILE_NAME;

    /**
     * We save the real elapsed time to serve as an indication for downloading the demo video
     * for the next device boot. The device could boot fast at times and could result in
     * skipping the download during the next boot sessions. To be safe from cases like this, we
     * add this offset to the real elapsed time.
     */
    private static final long REAL_ELAPSED_TIME_OFFSET_MS = 60 * 1000; // 1 min

    private PowerManager mPowerManager;

    private VideoView mVideoView;
    private int mVideoPosition;
    private String mDownloadPath;
    private boolean mUsingDownloadedVideo;

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

        mDownloadPath = getObbDir().getPath() + File.separator + VIDEO_FILE_NAME;
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

        mVideoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (mUsingDownloadedVideo && new File(PRELOADED_VIDEO_FILE).exists()) {
                    if (DEBUG) Log.d(TAG, "Error using the downloaded video, "
                            + "falling back to the preloaded video at " + PRELOADED_VIDEO_FILE);
                    mUsingDownloadedVideo = false;
                    setVideoPath(PRELOADED_VIDEO_FILE);
                    // And delete the downloaded video so that we don't try to use it
                    // again next time.
                    new File(mDownloadPath).delete();
                } else {
                    displayFallbackView();
                }
                return true;
            }
        });

        loadVideo();
    }

    private void displayFallbackView() {
        if (DEBUG) Log.d(TAG, "Showing the fallback view");
        findViewById(R.id.fallback_layout).setVisibility(View.VISIBLE);
        mVideoView.setVisibility(View.GONE);
    }

    private void displayVideoView() {
        mVideoView.setVisibility(View.VISIBLE);
        findViewById(R.id.fallback_layout).setVisibility(View.GONE);
    }

    private void loadVideo() {
        // If the video is already downloaded, then use that and check for an update.
        // Otherwise check if the video is preloaded, if not download the video from the
        // specified url.
        boolean isVideoSet = false;
        if (new File(mDownloadPath).exists()) {
            if (DEBUG) Log.d(TAG, "Using the already existing video at " + mDownloadPath);
            setVideoPath(mDownloadPath);
            isVideoSet = true;
        } else if (new File(PRELOADED_VIDEO_FILE).exists()) {
            if (DEBUG) Log.d(TAG, "Using the preloaded video at " + PRELOADED_VIDEO_FILE);
            setVideoPath(PRELOADED_VIDEO_FILE);
            isVideoSet = true;
        }

        final String downloadUrl = getString(R.string.retail_demo_video_download_url);
        // If the download url is empty, then no need to start the download task.
        if (TextUtils.isEmpty(downloadUrl)) {
            if (!isVideoSet) {
                displayFallbackView();
            }
            return;
        }
        if (!checkIfDownloadingAllowed()) {
            if (DEBUG) Log.d(TAG, "Downloading not allowed, neither starting download nor checking"
                    + " for an update.");
            if (!isVideoSet) {
                displayFallbackView();
            }
            return;
        }
        new DownloadVideoTask(this, mDownloadPath, this).run();
    }

    private boolean checkIfDownloadingAllowed() {
        final long lastRealElapsedTime = DataReaderWriter.getElapsedRealTime(this);
        final long realElapsedTime = SystemClock.elapsedRealtime();
        // We need to download the video atmost once after every boot.
        if (lastRealElapsedTime == 0 || realElapsedTime < lastRealElapsedTime) {
            DataReaderWriter.setElapsedRealTime(this,
                    realElapsedTime + REAL_ELAPSED_TIME_OFFSET_MS);
            return true;
        }
        return false;
    }

    @Override
    public void onFileDownloaded(final String filePath) {
        mUsingDownloadedVideo = true;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setVideoPath(filePath);
            }
        });
    }

    @Override
    public void onError() {
        displayFallbackView();
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

    private void setVideoPath(String videoPath) {
        // Load the video from resource
        try {
            mVideoView.setVideoPath(videoPath);
            displayVideoView();
        } catch (Exception e) {
            Log.e(TAG, "Exception setting video uri! " + e.getMessage());
            displayFallbackView();
        }
    }

    private void forceTurnOnScreen() {
        final PowerManager.WakeLock wakeLock = mPowerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
        wakeLock.acquire();
        // Device waken up, release the wake-lock
        wakeLock.release();
    }
}
