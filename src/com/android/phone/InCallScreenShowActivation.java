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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Trampoline to InCallScreen protected by PERFORM_CDMA_PROVISIONING permission
 * to only expose SHOW_ACTIVATION.
 */
public class InCallScreenShowActivation extends Activity {
    private static final String LOG_TAG = "InCallScreenShowActivation";

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        if (getIntent().getAction().equals(InCallScreen.ACTION_SHOW_ACTIVATION)) {
            Intent newIntent = new Intent().setClass(this, InCallScreen.class)
                    .setAction(InCallScreen.ACTION_SHOW_ACTIVATION);
            startActivity(newIntent);
        } else {
            Log.e(LOG_TAG, "Inappropriate launch of InCallScreenShowActivation");
        }

        finish();
    }

}
