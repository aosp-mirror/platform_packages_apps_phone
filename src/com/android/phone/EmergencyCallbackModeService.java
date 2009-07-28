/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.phone;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Binder;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;

/**
 * Application service that inserts/removes Emergency Callback Mode notification and
 * updates Emergency Callback Mode countdown clock in the notification
 *
 * @see EmergencyCallbackModeExitDialog
 */
public class EmergencyCallbackModeService extends Service {

    // Default Emergency Callback Mode timeout value
    private static final int DEFAULT_ECM_EXIT_TIMER_VALUE = 300000;
    private static final String LOG_TAG = "EmergencyCallbackModeService";

    private NotificationManager mNotificationManager = null;
    private CountDownTimer mTimer = null;
    private long mTimeLeft = 0;

    @Override
    public void onCreate() {

        // Check if it is CDMA phone
        if(!(PhoneFactory.getDefaultPhone().getPhoneName().equals("CDMA"))) {
            Log.e(LOG_TAG, "Error! Emergency Callback Mode not supported for " +
                    PhoneFactory.getDefaultPhone().getPhoneName() + " phones");
            stopSelf();
        }

        // Register receiver for intents
        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS);
        registerReceiver(mEcmReceiver, filter);

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // Get Emergency Callback Mode timeout value
        long ecmTimeout = SystemProperties.getLong(
                    TelephonyProperties.PROPERTY_ECM_EXIT_TIMER, DEFAULT_ECM_EXIT_TIMER_VALUE);

        // Show the notification
        showNotification(ecmTimeout);

        // Start countdown timer for the notification updates
        mTimer = new CountDownTimer(ecmTimeout, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                mTimeLeft = millisUntilFinished;
                EmergencyCallbackModeService.this.showNotification(millisUntilFinished);
            }

            @Override
            public void onFinish() {
                //Do nothing
            }

        }.start();
    }

    @Override
    public void onDestroy() {
        // Unregister receiver
        unregisterReceiver(mEcmReceiver);
        // Cancel the notification and timer
        mNotificationManager.cancel(R.string.phone_in_ecm_notification_title);
        mTimer.cancel();
    }

    /**
     * Listens for Emergency Callback Mode intents
     */
    private BroadcastReceiver mEcmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Stop the service when phone exits Emergency Callback Mode
            if (intent.getAction().equals(
                    TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED)) {
                if (intent.getBooleanExtra("phoneinECMState", false) == false) {
                    stopSelf();
                }
            }
            // Show dialog box
            else if (intent.getAction().equals(
                    TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS)) {
                    context.startActivity(
                            new Intent(TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }
    };

    /**
     * Shows notification for Emergency Callback Mode
     */
    private void showNotification(long millisUntilFinished) {

        // Set the icon and text
        Notification notification = new Notification(
                R.drawable.picture_emergency25x25,
                getText(R.string.phone_entered_ecm_text), 0);

        // PendingIntent to launch Emergency Callback Mode Exit activity if the user selects
        // this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(EmergencyCallbackModeExitDialog.ACTION_SHOW_ECM_EXIT_DIALOG), 0);

        // Format notification string
        int minutes = (int)(millisUntilFinished / 60000);
        String time = String.format("%d:%02d", minutes, (millisUntilFinished % 60000) / 1000);
        String text = String.format(getResources().getQuantityText(
                R.plurals.phone_in_ecm_notification_time, minutes).toString(), time);

        // Set the info in the notification
        notification.setLatestEventInfo(this, getText(R.string.phone_in_ecm_notification_title),
                text, contentIntent);

        notification.flags = Notification.FLAG_ONGOING_EVENT;

        // Show notification
        mNotificationManager.notify(R.string.phone_in_ecm_notification_title, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    // This is the object that receives interactions from clients.
    private final IBinder mBinder = new LocalBinder();

    /**
     * Class for clients to access
     */
    public class LocalBinder extends Binder {
        EmergencyCallbackModeService getService() {
            return EmergencyCallbackModeService.this;
        }
    }

    /**
     * Returns Emergency Callback Mode timeout value
     */
    public long getEmergencyCallbackModeTimeout() {
        return mTimeLeft;
    }
}
