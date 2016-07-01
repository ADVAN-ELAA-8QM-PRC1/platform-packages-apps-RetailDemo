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

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads the video from the specified url. If the video is previously downloaded, then uses
 * that but checks if there is a more recent version of the video available.
 */
class DownloadVideoTask {
    private static final String TAG = "DownloadVideoTask";
    private static final boolean DEBUG = false;

    private static final int MSG_CHECK_FOR_UPDATE = 1;
    private static final int MSG_DOWNLOAD_COMPLETE = 2;
    private static final int MSG_CLEANUP_DOWNLOAD_DIR = 3;

    private static final int CLEANUP_DELAY_MILLIS = 2 * 1000; // 2 seconds

    private Context mContext;
    private DownloadManager mDlm;
    private File mDownloadFile;
    private ResultListener mListener;

    private Handler mHandler;

    private ProgressDialog mProgressDialog;
    private AlertDialog mErrorMsgDialog;
    private NetworkChangeReceiver mNetworkChangeReceiver;
    private String mDownloadUrl;
    private long mVideoDownloadId;
    private long mVideoUpdateDownloadId;
    private String mDownloadedPath;

    public DownloadVideoTask(Context context, String downloadPath, ResultListener listener) {
        mContext = context;
        mDownloadFile = new File(downloadPath);
        mListener = listener;

        mDlm = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
        mDownloadUrl = mContext.getString(R.string.retail_demo_video_download_url);
    }

    public void run() {
        mContext.registerReceiver(mDownloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        // Initialize handler
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new ThreadHandler(thread.getLooper());

        // If file already exists, no need to download it again.
        if (mDownloadFile.exists()) {
            if (DEBUG) Log.d(TAG, "Using the alreaded downloaded video at "
                    + mDownloadFile.getPath());
            mListener.onFileDownloaded(mDownloadFile.getPath());
            mHandler.sendMessage(mHandler.obtainMessage(MSG_CHECK_FOR_UPDATE));
        } else {
            if (!isConnectedToNetwork()) {
                mErrorMsgDialog = createErrorMsgDialog(R.string.no_network_connectivity);
                mErrorMsgDialog.show();
                mNetworkChangeReceiver = new NetworkChangeReceiver();
                mContext.registerReceiver(mNetworkChangeReceiver,
                        new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
                return;
            }
            startDownload();
        }
    }

    private void startDownload() {
        final DownloadManager.Request request = createDownloadRequest();
        mVideoDownloadId = mDlm.enqueue(request);
        if (DEBUG) Log.d(TAG, "Started downloading the video at " + mDownloadFile.getPath());
        showProgressDialog();
    }

    private DownloadManager.Request createDownloadRequest() {
        final DownloadManager.Request request = new DownloadManager.Request(
                Uri.parse(mDownloadUrl));
        request.setDestinationUri(Uri.fromFile(mDownloadFile));
        return request;
    }

    private final BroadcastReceiver mDownloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                return;
            }

            final long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0);
            if (id == mVideoDownloadId) {
                if (checkDownloadsAndSetVideo(id)) {
                    mProgressDialog.dismiss();
                }
            } else if (id == mVideoUpdateDownloadId) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_DOWNLOAD_COMPLETE));
            }
        }
    };

    private final class ThreadHandler extends Handler {
        public ThreadHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CHECK_FOR_UPDATE:
                    if (!isConnectedToNetwork()) {
                        mNetworkChangeReceiver = new NetworkChangeReceiver();
                        mContext.registerReceiver(mNetworkChangeReceiver,
                                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
                        return;
                    }
                    HttpURLConnection conn = null;
                    try {
                        conn = (HttpURLConnection) new URL(mDownloadUrl).openConnection();
                        final long lastModified = mDownloadFile.lastModified();
                        conn.setIfModifiedSince(lastModified);
                        conn.connect();
                        if (conn.getResponseCode() == HttpURLConnection.HTTP_NOT_MODIFIED) {
                            return;
                        }
                        final DownloadManager.Request request = createDownloadRequest();
                        mVideoUpdateDownloadId = mDlm.enqueue(request);
                        if (DEBUG) Log.d(TAG, "Started downloading the updated video");
                    } catch (IOException e) {
                        Log.e(TAG, "Error while checking for an updated video", e);
                    } finally {
                        if (conn != null) {
                            conn.disconnect();
                        }
                    }
                    break;
                case MSG_DOWNLOAD_COMPLETE:
                    checkDownloadsAndSetVideo(mVideoUpdateDownloadId);
                    break;
                case MSG_CLEANUP_DOWNLOAD_DIR:
                    // If the video was downloaded to the same location as we needed, then
                    // nothing else to do.
                    if (mDownloadFile.getPath().equals(mDownloadedPath)) {
                        return;
                    }
                    if (mDownloadFile.exists()) {
                        mDownloadFile.delete();
                    }
                    if (new File(mDownloadedPath).renameTo(mDownloadFile)) {
                        mListener.onFileDownloaded(mDownloadFile.getPath());
                        final String downloadFileName = mDownloadFile.getName();
                        // Delete other files in the directory
                        for (File file : mDownloadFile.getParentFile().listFiles()) {
                            if (file.getName().startsWith(downloadFileName)
                                    && !file.getPath().equals(mDownloadFile.getPath())) {
                                file.delete();
                            }
                        }
                    }
                    break;
            }
        }
    }

    private boolean checkDownloadsAndSetVideo(long downloadId) {
        final DownloadManager.Query query =
                new DownloadManager.Query().setFilterById(downloadId);
        Cursor cursor = mDlm.query(query);
        try {
            if (cursor != null & cursor.moveToFirst()) {
                final int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                if (cursor.getInt(columnIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                    mContext.unregisterReceiver(mDownloadReceiver);
                    if (mNetworkChangeReceiver != null) {
                        mContext.unregisterReceiver(mNetworkChangeReceiver);
                    }
                    final String fileUri = cursor.getString(
                            cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                    mDownloadedPath = Uri.parse(fileUri).getPath();
                    if (DEBUG) Log.d(TAG, "Video successfully downloaded at " + mDownloadedPath);
                    mListener.onFileDownloaded(mDownloadedPath);
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_CLEANUP_DOWNLOAD_DIR),
                            CLEANUP_DELAY_MILLIS);
                    return true;
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return false;
    }

    private class NetworkChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())
                    && isConnectedToNetwork()) {
                if (mDownloadFile.exists()) {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_CHECK_FOR_UPDATE));
                } else {
                    mErrorMsgDialog.dismiss();
                    startDownload();
                }
            }
        }
    };

    private void showProgressDialog() {
        mProgressDialog = new ProgressDialog(mContext);
        mProgressDialog.setMessage(mContext.getString(R.string.downloading_video_msg));
        mProgressDialog.setIndeterminate(false);
        mProgressDialog.setCancelable(false);
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        mProgressDialog.show();
    }

    private boolean isConnectedToNetwork() {
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private AlertDialog createErrorMsgDialog(int msgResId) {
        return new AlertDialog.Builder(mContext)
                .setMessage(msgResId)
                .setCancelable(false)
                .create();
    }

    interface ResultListener {
        void onFileDownloaded(String downloadedFilePath);
    }
}