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

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.text.format.Time;
import android.util.Log;
import android.widget.RemoteViews;

public class EriTextWidgetProvider extends AppWidgetProvider {
    static final String LOG_TAG = "EriTextWidgetProvider";

    static final ComponentName THIS_APPWIDGET =
        new ComponentName("com.android.phone",
                "com.android.phone.EriTextWidgetProvider");

    private static EriTextWidgetProvider sInstance;

    static synchronized EriTextWidgetProvider getInstance() {
        if (sInstance == null) {
            sInstance = new EriTextWidgetProvider();
        }
        return sInstance;
    }

    @Override
    public void onEnabled(Context context) {
        TelephonyManager mPhone;
        String eriText = "ERI not init";

        try {
            mPhone = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
            eriText = mPhone.getCdmaEriText();
        } catch (Exception ex){
            Log.e(LOG_TAG, "onEnabled, error setting Cdma Eri Text: " + ex);
        }

        if (eriText == null) eriText = "ERI not available";

        performUpdate(context,eriText);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        TelephonyManager mPhone;
        String eriText = "ERI not init";

        try {
            mPhone = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
            eriText = mPhone.getCdmaEriText();
        } catch (Exception ex){
            Log.e(LOG_TAG, "onEnabled, error setting Cdma Eri Text: " + ex);
        }

        if (eriText == null) eriText = "ERI not available";

        performUpdate(context,eriText);
    }

    void performUpdate(Context context, String eriText) {
        final Resources res = context.getResources();
        final RemoteViews views = new RemoteViews(context.getPackageName(),
            R.layout.eri_text_layout);

        views.setTextViewText(R.id.message, eriText);

        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        manager.updateAppWidget(THIS_APPWIDGET, views);

    }
}

