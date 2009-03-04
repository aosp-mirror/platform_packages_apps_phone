/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.TabActivity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.CallLog.Calls;
import android.provider.Contacts.Intents.UI;
import android.view.KeyEvent;
import android.view.Window;
import android.widget.TabHost;

/**
 * This is old cruft and should be removed, but there is apparently some
 * test code that depends on it.
 */
public class DialtactsActivity extends TabActivity {

    public static final String EXTRA_IGNORE_STATE = "ignore-state";

    private static final int FAVORITES_STARRED = 1;
    private static final int FAVORITES_FREQUENT = 2;
    private static final int FAVORITES_STREQUENT = 3;
    
    /** Defines what is displayed in the right tab */
    private static final int FAVORITES_TAB_MODE = FAVORITES_STARRED;

    protected TabHost mTabHost;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final Intent intent = getIntent();
        fixIntent(intent);
        final boolean recentCallsRequest = Calls.CONTENT_TYPE.equals(intent.getType());

        if (isSendKeyWhileInCall(intent, recentCallsRequest)) {
            finish();
            return;
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialer_activity);

        mTabHost = getTabHost();

        // Setup the tabs
        setupDialerTab();
        setupCallLogTab();
        setupContactsTab();
        setupFavoritesTab();

        setCurrentTab(intent);
    }

    private void fixIntent(Intent intent) {
        // This should be cleaned up: the call key used to send an Intent
        // that just said to go to the recent calls list.  It now sends this
        // abstract action, but this class hasn't been rewritten to deal with it.
        if (Intent.ACTION_CALL_BUTTON.equals(intent.getAction())) {
            intent.setDataAndType(Calls.CONTENT_URI, Calls.CONTENT_TYPE);
            intent.putExtra("call_key", true);
            setIntent(intent);
        }
    }
    
    private void setupCallLogTab() {
        mTabHost.addTab(mTabHost.newTabSpec("call_log")
                .setIndicator(getString(R.string.recentCallsIconLabel),
                        getResources().getDrawable(R.drawable.ic_tab_recent))
                .setContent(new Intent("com.android.phone.action.RECENT_CALLS")));
    }

    private void setupDialerTab() {
        mTabHost.addTab(mTabHost.newTabSpec("dialer")
                .setIndicator(getString(R.string.dialerIconLabel),
                        getResources().getDrawable(R.drawable.ic_tab_dialer))
                .setContent(new Intent("com.android.phone.action.TOUCH_DIALER")));
    }

    private void setupContactsTab() {
        mTabHost.addTab(mTabHost.newTabSpec("contacts")
                .setIndicator(getText(R.string.contactsIconLabel),
                        getResources().getDrawable(R.drawable.ic_tab_contacts))
                .setContent(new Intent(UI.LIST_DEFAULT)));
    }

    private void setupFavoritesTab() {
        Intent tab2Intent;
        switch (FAVORITES_TAB_MODE) {
            case FAVORITES_STARRED:
                tab2Intent = new Intent(UI.LIST_STARRED_ACTION);
                break;

            case FAVORITES_FREQUENT:
                tab2Intent = new Intent(UI.LIST_FREQUENT_ACTION);
                break;

            case FAVORITES_STREQUENT:
                tab2Intent = new Intent(UI.LIST_STREQUENT_ACTION);
                break;

            default:
                throw new UnsupportedOperationException("unknown default mode");
        }
        Drawable tab2Icon = getResources().getDrawable(R.drawable.ic_tab_starred);

        mTabHost.addTab(mTabHost.newTabSpec("favorites")
                .setIndicator(getString(R.string.contactsFavoritesLabel), tab2Icon)
                .setContent(tab2Intent));
    }

    /**
     * Returns true if the intent is due to hitting the green send key while in a call.
     * 
     * @param intent the intent that launched this activity
     * @param recentCallsRequest true if the intent is requesting to view recent calls
     * @return true if the intent is due to hitting the green send key while in a call 
     */
    private boolean isSendKeyWhileInCall(final Intent intent, final boolean recentCallsRequest) {
        // If there is a call in progress go to the call screen
        if (recentCallsRequest) {
            final boolean callKey = intent.getBooleanExtra("call_key", false);
            // When running under instrumentation PhoneApp.getInstance() can return null, which
            // causes a crash here.
            PhoneApp app = PhoneApp.getInstance();
            if (callKey && app != null && app.handleInCallOrRinging()) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Sets the current tab based on the intent's request type
     * 
     * @param recentCallsRequest true is the recent calls tab is desired, false oltherwise
     */
    private void setCurrentTab(Intent intent) {
        final boolean recentCallsRequest = Calls.CONTENT_TYPE.equals(intent.getType());
        if (isSendKeyWhileInCall(intent, recentCallsRequest)) {
            finish();
            return;
        }
        intent.putExtra(EXTRA_IGNORE_STATE, true);
        if (intent.getComponent().getClassName().equals(getClass().getName())) {
            if (recentCallsRequest) {
                mTabHost.setCurrentTab(1);
            } else {
                mTabHost.setCurrentTab(0);
            }
        } else {
            mTabHost.setCurrentTab(2);
        }
        intent.putExtra(EXTRA_IGNORE_STATE, false);
    }

    @Override
    public void onNewIntent(Intent newIntent) {
        setIntent(newIntent);
        fixIntent(newIntent);
        setCurrentTab(newIntent);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Handle BACK
        if (keyCode == KeyEvent.KEYCODE_BACK && isTaskRoot()) {
            // Instead of stopping, simply push this to the back of the stack.
            // This is only done when running at the top of the stack;
            // otherwise, we have been launched by someone else so need to
            // allow the user to go back to the caller.
            moveTaskToBack(false);
            return true;
        }
        
        return super.onKeyDown(keyCode, event);
    }
}
