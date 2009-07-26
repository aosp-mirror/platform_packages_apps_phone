/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.Window;
import android.os.Bundle;

/**
 * Base class for ICC-related panels in the Phone UI.
 */
public class IccPanel extends Dialog {
    protected static final String TAG = PhoneApp.LOG_TAG;

    private KeyguardManager.KeyguardLock mKeyguardLock;

    public IccPanel(Context context) {
        super(context, R.style.IccPanel);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window winP = getWindow();
        winP.setType(WindowManager.LayoutParams.TYPE_PRIORITY_PHONE);
        winP.setLayout(WindowManager.LayoutParams.FILL_PARENT,
                WindowManager.LayoutParams.FILL_PARENT);
        winP.setGravity(Gravity.CENTER);

        PhoneApp app = PhoneApp.getInstance();
        KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        mKeyguardLock = km.newKeyguardLock(TAG);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        disableKeyguard(true);
    }

    @Override
    public void onStop() {
        super.onStop();
        disableKeyguard(false);
    }

    /**
     * Acquires a wake lock and prevents keyguard from enabling.
     */
    private void disableKeyguard(boolean disable) {
        if (disable) {
            mKeyguardLock.disableKeyguard();
        } else {
            mKeyguardLock.reenableKeyguard();
        }
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }
}
