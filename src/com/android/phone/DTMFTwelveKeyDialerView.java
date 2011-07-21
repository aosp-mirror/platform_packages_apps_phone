/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.ArrayList;

/**
 * DTMFTwelveKeyDialerView is the view logic that the DTMFDialer uses.
 * This is really a thin wrapper around Linear Layout that intercepts
 * some user interactions to provide the correct UI behaviour for the
 * dialer.
 *
 * See dtmf_twelve_key_dialer_view.xml.
 */
class DTMFTwelveKeyDialerView extends LinearLayout {

    private static final String LOG_TAG = "PHONE/DTMFTwelveKeyDialerView";
    private static final boolean DBG = false;

    private DTMFTwelveKeyDialer mDialer;


    public DTMFTwelveKeyDialerView (Context context) {
        super(context);
    }

    public DTMFTwelveKeyDialerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setDialer (DTMFTwelveKeyDialer dialer) {
        mDialer = dialer;
    }

    /**
     * Normally we ignore everything except for the BACK and CALL keys.
     * For those, we pass them to the model (and then the InCallScreen).
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (DBG) log("dispatchKeyEvent(" + event + ")...");

        int keyCode = event.getKeyCode();
        if (mDialer != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_CALL:
                    return event.isDown() ? mDialer.onKeyDown(keyCode, event) :
                        mDialer.onKeyUp(keyCode, event);
            }
        }

        if (DBG) log("==> dispatchKeyEvent: forwarding event to the DTMFDialer");
        return super.dispatchKeyEvent(event);
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
