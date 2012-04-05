/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.app.IntentService;
import android.content.Intent;
import android.telephony.PhoneNumberUtils;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

public class UssdService extends IntentService {

    private Phone phone = null;
    private PhoneApp phoneApp = null;

    public UssdService() {
        super("UssdService");
        phoneApp = PhoneApp.getInstance();
        phone = PhoneFactory.getDefaultPhone();
        phoneApp.mCM.registerPhone(phone);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        String ussd = PhoneNumberUtils.getNumberFromIntent(intent, this);
        if (!phoneApp.isShowingCallScreen()) {
            phone.sendUssdResponse(ussd);
        } else {
            // TODO: How to inform the Intent sender no USSD was sent?
        }
    }

}
