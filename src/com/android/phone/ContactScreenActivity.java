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
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.content.Intent;

public class ContactScreenActivity extends Activity {
    private static final String TAG = "ContactScreenActivity";
    String mName, mNewName;
    String mPhoneNumber, mNewPhoneNumber;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.contact_screen);

        try {
            final EditText editName = (EditText) this.findViewById(R.id.name);
            final EditText editPhoneNumber = (EditText) this.findViewById(R.id.phoneNumber);
            final Intent intent = getIntent();
            mName = intent.getStringExtra("NAME");
            mPhoneNumber = intent.getStringExtra("PHONE");

            editName.setText(mName, TextView.BufferType.EDITABLE);
            editPhoneNumber.setText(mPhoneNumber, TextView.BufferType.EDITABLE);

            View.OnClickListener handler = new View.OnClickListener(){
                public void onClick(View v) {
                    switch (v.getId()){
                        case R.id.save:
                            mNewName = editName.getText().toString();
                            mNewPhoneNumber = editPhoneNumber.getText().toString();
                            Log.d(TAG, "Name: " + mName + " Number: "
                                    + mPhoneNumber);
                            Log.d(TAG, " After edited Name: "
                                    + mNewName + " Number: " + mNewPhoneNumber);
                            Intent intent = new Intent();
                            intent.putExtra("NAME", mName);
                            intent.putExtra("PHONE", mPhoneNumber);
                            intent.putExtra("NEWNAME", mNewName);
                            intent.putExtra("NEWPHONE", mNewPhoneNumber);
                            setResult(RESULT_OK, intent);
                            finish();
                            break;
                        case R.id.cancel:
                            finish();
                            break;
                    }
                }
            };

            findViewById(R.id.save).setOnClickListener(handler);
            findViewById(R.id.cancel).setOnClickListener(handler);

        } catch(Exception e){
            Log.e("ContactScreenActivity ", e.toString());
        }
    }
}
