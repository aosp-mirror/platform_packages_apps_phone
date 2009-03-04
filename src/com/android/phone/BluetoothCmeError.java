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

/* Constants for extended AT error codes specified by the Handsfree profile. */
public class BluetoothCmeError {
    public static final int AG_FAILURE = 0;
    public static final int NO_CONNECTION_TO_PHONE = 1;
    public static final int OPERATION_NOT_ALLOWED = 3;
    public static final int OPERATION_NOT_SUPPORTED = 4;
    public static final int PIN_REQUIRED = 5;
    public static final int SIM_MISSING = 10;
    public static final int SIM_PIN_REQUIRED = 11;
    public static final int SIM_PUK_REQUIRED = 12;
    public static final int SIM_FAILURE = 13;
    public static final int SIM_BUSY = 14;
    public static final int WRONG_PASSWORD = 16;
    public static final int SIM_PIN2_REQUIRED = 17;
    public static final int SIM_PUK2_REQUIRED = 18;
    public static final int MEMORY_FULL = 20;
    public static final int INVALID_INDEX = 21;
    public static final int MEMORY_FAILURE = 23;
    public static final int TEXT_TOO_LONG = 24;
    public static final int TEXT_HAS_INVALID_CHARS = 25;
    public static final int DIAL_STRING_TOO_LONG = 26;
    public static final int DIAL_STRING_HAS_INVALID_CHARS = 27;
    public static final int NO_SERVICE = 30;
    public static final int ONLY_911_ALLOWED = 32;
}
