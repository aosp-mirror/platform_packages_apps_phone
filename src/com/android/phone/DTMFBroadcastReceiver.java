
package com.android.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * DTMFBroadcastReceiver receives android.intent.action.SEND_DTMF intents 
 * and forwards them to the internal {@see DTMFSenderService} for processing.
 */
public class DTMFBroadcastReceiver extends BroadcastReceiver {
    
    public static final String TAG = "DTMFBroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        intent.setClass(context, DTMFSenderService.class);
        context.startService(intent);
    }

}
