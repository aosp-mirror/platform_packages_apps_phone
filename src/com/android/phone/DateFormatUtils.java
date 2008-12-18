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

import android.text.format.DateUtils;

import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * Date and time formatting utilities for the Phone app.
 */
public class DateFormatUtils {
    /** This class is never instantiated. */
    private DateFormatUtils() {
    }

    private static void appendAbbevYear(StringBuilder buf, int year) {
        buf.append(", ");

        year = year % 100;
        if (year < 10) {
            buf.append('0');
        }

        buf.append(year);
    }

    public static String formatCallTime(long when, boolean abbrev) {
        Calendar c = new GregorianCalendar();

        c.setTimeInMillis(when);
        int year = c.get(Calendar.YEAR);
        int month = c.get(Calendar.MONTH);
        int day = c.get(Calendar.DATE);
        int hour = c.get(Calendar.HOUR);
        int minutes = c.get(Calendar.MINUTE);
        int amPm = c.get(Calendar.AM_PM);
        StringBuilder str = new StringBuilder("");

        if (hour == 0) {
            hour = 12;
        }
        str.append(hour);
        str.append(':');
        if (minutes < 10) {
            str.append('0');
        }
        str.append(minutes);
        str.append((amPm == 0) ? "am" : "pm");
        str.append("  ");

        str.append(DateUtils.getMonthString(month, DateUtils.LENGTH_SHORT));
        str.append(" ");
        str.append(day);

        if (abbrev) {
            Calendar c2 = new GregorianCalendar();
            int currentYear = c2.get(Calendar.YEAR);
            if (currentYear != year) {
                appendAbbevYear(str, year);
            }
        } else {
            appendAbbevYear(str, year);
        }

        return str.toString();
    }

    private static void timeFormatHelper(StringBuilder buf, int t, boolean appendColon) {
        if (t < 10) {
            buf.append('0');
        }

        buf.append(t);

        if (appendColon) {
            buf.append(':');
        }
    }

    public static String formatDurationTime(int duration) {
        int secondsPerHour = 3600;
        int hours = duration / secondsPerHour;
        int mins = (duration % secondsPerHour) / 60;
        int secs = duration % 60;

        StringBuilder buf = new StringBuilder();
        timeFormatHelper(buf, hours, true);
        timeFormatHelper(buf, mins, true);
        timeFormatHelper(buf, secs, false);

        return buf.toString();
    }
}
