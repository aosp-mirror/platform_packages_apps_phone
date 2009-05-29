/*
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


import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ImageButton;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

/**
 * Phone app Emergency Callback screen.
 */
public class EmergencyCallbackMode extends Activity {

    /** Event for TTY mode change */
    private static final int EVENT_EXIT_ECBM    = 100;

    private Phone mPhone;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ecbm_layout);

        //get mPhone instance
        mPhone = PhoneFactory.getDefaultPhone();

        // Watch for button clicks.
        ImageButton dialButton = (ImageButton)findViewById(R.id.button_dial);
        dialButton.setOnClickListener(mDialListener);

        Button exitButton = (Button)findViewById(R.id.button_exit);
        exitButton.setOnClickListener(mExitListener);

        Button okButton = (Button)findViewById(R.id.button_ok);
        okButton.setOnClickListener(mOkListener);

        //cancel ECBM notification
        NotificationMgr.getDefault().cancelEcbmNotification();
    }

    private OnClickListener mDialListener = new OnClickListener()
    {
        public void onClick(View v)
        {
            Intent intent = new Intent(Intent.ACTION_CALL_EMERGENCY,  Uri.parse("tel:911"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            //create Notification
            NotificationMgr.getDefault().notifyECBM();
            // finish Application
            finish();

        }
    };

    private OnClickListener mExitListener = new OnClickListener()
    {
        public void onClick(View v) {
            // Send ECBM exit

            // TODO(Moto): There is a change, no parameter looks like an intent is sent?
            //mPhone.exitEmergencyCallbackMode(Message.obtain(mExitEmergencyCallbackMode,
            //        EVENT_EXIT_ECBM));
        }
    };


    // **Callback on Exit Emergency callback mode
    private Handler mExitEmergencyCallbackMode = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            // query to make sure we're looking at the same data as that in the network.
            switch (msg.what) {
                case EVENT_EXIT_ECBM:
                    handleExitEmergencyCallbackModeResponse(msg);
                    break;
                default:
                    // TODO: should never reach this, may want to throw exception
            }
        }
    };

    /*
     * Handle the respone of the ExitEmergencyCallbackMode request
     */
    private void handleExitEmergencyCallbackModeResponse(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;

        if (ar.exception == null) {
            // Finish Emergency Callback Mode Application
            finish();
        }
    }


    private OnClickListener mOkListener = new OnClickListener()
    {
        public void onClick(View v)
        {
            // create a notification
            NotificationMgr.getDefault().notifyECBM();
            // finish Application
            finish();
        }
    };


    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // suppress all key presses except of call key
        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL: {
                Intent intent = new Intent(Intent.ACTION_CALL_EMERGENCY,  Uri.parse("tel:911"));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
            }
        }
        return true;
    }

}