/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.internal.telephony.Phone;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

public class NetworkPreference extends Preference {

    // Network availability
    private static final int AVAILABLE = 0;
    private static final int UNAVAILABLE = 1;
    private static final int HOME = 2;

    private boolean mCurrentNetwork = false;
    private boolean mNetwork3G = false;
    private int mNetworkAvailability = AVAILABLE;

    private ImageView currentNetworkIcon = null;
    private ImageView networkAvailability = null;
    private ImageView networkTechnologyIcon = null;

    public NetworkPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_network_item);
    }

    @Override
    public void onBindView(View view) {
        super.onBindView(view);

        // Set network availability icon
        networkAvailability = (ImageView)view.findViewById(R.id.callhandling_network_availability);
        switch (mNetworkAvailability) {
            case UNAVAILABLE:
                networkAvailability.setVisibility(View.VISIBLE);
                break;

            case HOME:
                networkAvailability.setImageResource(R.drawable.callhandling_settings_homenetwork);
                networkAvailability.setVisibility(View.VISIBLE);
                break;

            case AVAILABLE:
            default:
                networkAvailability.setVisibility(View.INVISIBLE);
                break;
        }

        // Set 2G/3G network icon
        networkTechnologyIcon = (ImageView)view.findViewById(R.id.callhandling_network_technology);
        if (mNetwork3G) {
            networkTechnologyIcon.setImageResource(R.drawable.callhandling_settings_3g_net);
        }
        networkTechnologyIcon.setVisibility(View.VISIBLE);

        // Set current network icon
        currentNetworkIcon = (ImageView)view.findViewById(R.id.callhandling_current_network);
        if (mCurrentNetwork) {
            currentNetworkIcon.setVisibility(View.VISIBLE);
        } else {
            currentNetworkIcon.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * Sets the indicator for current network on the preference.
     */
    public void setCurrentNetwork() {
        if (!mCurrentNetwork) {
            mCurrentNetwork = true;
            notifyChanged();
        }
    }

    /**
     * Removes the indicator for current network on the preference.
     */
    public void unsetCurrentNetwork() {
        if (mCurrentNetwork) {
            mCurrentNetwork = false;
            notifyChanged();
        }
    }

    /**
     * Puts the not available icon on the preference.
     */
    public void setNetworkNotAvailable() {
        if (mNetworkAvailability != UNAVAILABLE) {
            mNetworkAvailability = UNAVAILABLE;
            notifyChanged();
        }
    }

    /**
     * Puts the 3G icon instead of default 2G.
     */
    public void setNetwork3G() {
        if (!mNetwork3G) {
            mNetwork3G = true;
            notifyChanged();
        }
    }

    /**
     * Puts the home icon on the preference.
     */
    public void setHomeNetwork() {
        if (mNetworkAvailability != HOME) {
            mNetworkAvailability = HOME;
            notifyChanged();
        }
    }
}
