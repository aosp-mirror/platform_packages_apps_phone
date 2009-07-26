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

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.os.Bundle;

/**
 * Panel which displays the "ICC missing" message.
 */
public class IccMissingPanel extends IccPanel {

    public IccMissingPanel(Context context) {
        super(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.sim_missing);
        ((Button) findViewById(R.id.continueView)).setOnClickListener(mButtonListener);
    }

    View.OnClickListener mButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            dismiss();
        }
    };
}
