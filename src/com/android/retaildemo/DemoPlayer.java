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
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
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

    private static final String VIDEO_FILE_NAME = "retail_demo.mp4";
    private static final String PRELOADED_VIDEO_FILE = Environment.getDataPreloadsDemoDirectory()
            + File.separator + VIDEO_FILE_NAME;

    private PowerManager mPowerManager;
    private DownloadManager mDlm;

    private ProgressDialog mProgressDialog;

    private VideoView mVideoView;
    private int mVideoPosition;
    private long mVideoDownloadId;
    private String mDownloadedPath;

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
        mDlm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        mDownloadedPath = getObbDir().getPath() + File.separator + VIDEO_FILE_NAME;
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
        // If the video is preloaded, then use that. Otherwise download it from the specified url.
        if (new File(PRELOADED_VIDEO_FILE).exists()) {
            Log.i(TAG, "Using the preloaded video at " + PRELOADED_VIDEO_FILE);
            setVideoPath(PRELOADED_VIDEO_FILE);
        } else {
            downloadVideo();
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

    private void setVideoPath(String videoPath) {
        // Load the video from resource
        try {
            mVideoView.setVideoPath(videoPath);
        } catch (Exception e) {
            Log.e(TAG, "Exception setting video uri! " + e.getMessage());
            // If video cannot be load, reset retail mode
            finish();
        }
    }

    private void downloadVideo() {
        File downloadedFile = new File(mDownloadedPath);
        if (downloadedFile.exists()) {
            Log.i(TAG, "Using the alreaded downloaded video at " + mDownloadedPath);
            // File already exists, no need to download it again.
            setVideoPath(mDownloadedPath);
            return;
        }
        if (!isConnectedToNetwork()) {
            showErrorMsgDialog(R.string.no_network_connectivity);
            return;
        }

        showProgressDialog();
        final String location = getString(R.string.retail_demo_video_download_url);
        final DownloadManager.Request request = new DownloadManager.Request(
                Uri.parse(location));
        request.setDestinationUri(Uri.fromFile(downloadedFile));
        registerReceiver(mDownloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        mVideoDownloadId = mDlm.enqueue(request);
        Log.i(TAG, "Started downloading the video at " + mDownloadedPath);
    }

    private void showProgressDialog() {
        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setMessage(getString(R.string.downloading_video_msg));
        mProgressDialog.setIndeterminate(false);
        mProgressDialog.setCancelable(false);
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        mProgressDialog.show();
    }

    private void showErrorMsgDialog(int msgResId) {
        new AlertDialog.Builder(this)
                .setMessage(msgResId)
                .setCancelable(false)
                .show();
    }

    private void forceTurnOnScreen() {
        final PowerManager.WakeLock wakeLock = mPowerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
        wakeLock.acquire();
        // Device waken up, release the wake-lock
        wakeLock.release();
    }

    private boolean isConnectedToNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private BroadcastReceiver mDownloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                return;
            }
            final DownloadManager.Query query =
                    new DownloadManager.Query().setFilterById(mVideoDownloadId);
            Cursor cursor = mDlm.query(query);
            try {
                if (cursor != null & cursor.moveToFirst()) {
                    final int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    if (cursor.getInt(columnIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                        unregisterReceiver(mDownloadReceiver);
                        // TODO: Persist the downloaded location to use it next time.
                        String fileUri = cursor.getString(
                                cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                        Log.i(TAG, "Video successfully downloaded at " + fileUri);
                        mVideoView.setVideoURI(Uri.parse(fileUri));
                        mProgressDialog.dismiss();
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
    };
}
