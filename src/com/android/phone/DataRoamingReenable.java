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

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.os.Bundle;

public class DataRoamingReenable extends Activity {

    private Button mConfirmButton;
    private Button mReenableButton;
    
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.data_roaming);

        mConfirmButton = (Button)findViewById(R.id.confirmButton);
        mReenableButton = (Button)findViewById(R.id.reenableButton);

        mConfirmButton.setOnClickListener(new ConfirmButtonListener());
        mReenableButton.setOnClickListener(new ReenableButtonListener());
    }

    private class ConfirmButtonListener implements OnClickListener {
        public void onClick(View v) {
            finish();
        }
    };

    private class ReenableButtonListener implements OnClickListener {
        public void onClick(View v) {
            Intent intent = new Intent();
            intent.setClass(DataRoamingReenable.this, Settings.class);
            startActivity(intent);
            finish();
        }
    }
}
