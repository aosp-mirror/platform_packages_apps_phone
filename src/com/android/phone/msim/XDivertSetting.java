/*
 * Copyright (c) 2012 Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.phone;

import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceScreen;
import android.util.Log;

// SIM records have msisdn.Hence directly process XDivert feature

public class XDivertSetting extends TimeConsumingPreferenceActivity {
    private static final String LOG_TAG = "XDivertSetting";

    private static final String BUTTON_XDIVERT = "xdivert_checkbox";

    private XDivertCheckBoxPreference mXDivertButton;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        String sub1Number;
        String sub2Number;

        addPreferencesFromResource(R.xml.xdivert);

        Intent intent = getIntent();
        sub1Number = intent.getStringExtra("Sub1_Line1Number");
        sub2Number = intent.getStringExtra("Sub2_Line1Number");
        Log.d(LOG_TAG, "onCreate sub1Number = " + sub1Number + "sub2Number = " + sub2Number);
        PreferenceScreen prefSet = getPreferenceScreen();
        mXDivertButton = (XDivertCheckBoxPreference) prefSet.findPreference(BUTTON_XDIVERT);
        mXDivertButton.init(this, false, sub1Number, sub2Number);
   }

}
