/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.Spinner;

/**
 * This activity is just a temporary solution to easier demo the switching of UI between Vanilla
 * and SOMC UI.
 */
public class SetUiActivity extends Activity implements OnItemSelectedListener {

    private Spinner mSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_set_ui);

        mSpinner = (Spinner) findViewById(R.id.spinner1);
        mSpinner.setOnItemSelectedListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Update the spinner to show correct value.
        SharedPreferences prefs = this.getSharedPreferences(
                  "selected_ui", Context.MODE_PRIVATE);
        int ui = prefs.getInt("ui", 0);

        if (ui == 2) {
            // SOMC UI.
            mSpinner.setSelection(1);
        } else {
            // Vanilla UI.
            mSpinner.setSelection(0);
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> arg0, View arg1, int pos, long id) {
        SharedPreferences prefs = this.getSharedPreferences("selected_ui", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("ui", pos + 1);
        editor.commit();

        Log.d("SetUiActivity", "Stored " + (pos + 1) + " in shared preference.");
    }

    @Override
    public void onNothingSelected(AdapterView<?> arg0) {
    }

}
