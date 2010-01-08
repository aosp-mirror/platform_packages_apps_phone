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

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;


/**
 * Custom View used as the "options panel" for the InCallScreen
 * (i.e. the standard menu triggered by the MENU button.)
 *
 * This class purely handles the layout and display of the in-call menu
 * items, *not* the actual contents of the menu or the states of the
 * items.  (See InCallMenu for the corresponding "model" class.)

 */
class InCallMenuView extends ViewGroup {
    private static final String LOG_TAG = "PHONE/InCallMenuView";
    private static final boolean DBG = false;

    private int mRowHeight;

    /** Divider that is drawn between all rows */
    private Drawable mHorizontalDivider;
    /** Height of the horizontal divider */
    private int mHorizontalDividerHeight;
    /** Set of horizontal divider positions where the horizontal divider will be drawn */
    private ArrayList<Rect> mHorizontalDividerRects;

    /** Divider that is drawn between all columns */
    private Drawable mVerticalDivider;
    /** Width of the vertical divider */
    private int mVerticalDividerWidth;
    /** Set of vertical divider positions where the vertical divider will be drawn */
    private ArrayList<Rect> mVerticalDividerRects;

    /** Background of each item (should contain the selected and focused states) */
    private Drawable mItemBackground;

    /**
     * The actual layout of items in the menu, organized into 3 rows.
     *
     * Row 0 is the topmost row onscreen, item 0 is the leftmost item in a row.
     *
     * Individual items may be disabled or hidden, but never move between
     * rows or change their order within a row.
     */
    private static final int NUM_ROWS = 3;
    private static final int MAX_ITEMS_PER_ROW = 10;
    private InCallMenuItemView[][] mItems = new InCallMenuItemView[NUM_ROWS][MAX_ITEMS_PER_ROW];

    private int mNumItemsForRow[] = new int[NUM_ROWS];

    /**
     * Number of visible items per row, given the current state of all the
     * menu items.
     * A row with zero visible items isn't drawn at all.
     */
    private int mNumVisibleItemsForRow[] = new int[NUM_ROWS];
    private int mNumVisibleRows;

    /**
     * Reference to the InCallScreen activity that owns us.  This will be
     * null if we haven't been initialized yet *or* after the InCallScreen
     * activity has been destroyed.
     */
    private InCallScreen mInCallScreen;


    InCallMenuView(Context context, InCallScreen inCallScreen) {
        super(context);
        if (DBG) log("InCallMenuView constructor...");

        mInCallScreen = inCallScreen;

        // Look up a few styled attrs from IconMenuView and/or MenuView
        // (to keep our look and feel at least *somewhat* consistent with
        // menus in other apps.)

        TypedArray a =
                mContext.obtainStyledAttributes(com.android.internal.R.styleable.IconMenuView);
        if (DBG) log("- IconMenuView styled attrs: " + a);
        mRowHeight = a.getDimensionPixelSize(
                com.android.internal.R.styleable.IconMenuView_rowHeight, 64);
        if (DBG) log("  - mRowHeight: " + mRowHeight);
        a.recycle();

        a = mContext.obtainStyledAttributes(com.android.internal.R.styleable.MenuView);
        if (DBG) log("- MenuView styled attrs: " + a);
        mItemBackground = a.getDrawable(com.android.internal.R.styleable.MenuView_itemBackground);
        if (DBG) log("  - mItemBackground: " + mItemBackground);
        mHorizontalDivider = a.getDrawable(com.android.internal.R.styleable.MenuView_horizontalDivider);
        if (DBG) log("  - mHorizontalDivider: " + mHorizontalDivider);
        mHorizontalDividerRects = new ArrayList<Rect>();
        mVerticalDivider =  a.getDrawable(com.android.internal.R.styleable.MenuView_verticalDivider);
        if (DBG) log("  - mVerticalDivider: " + mVerticalDivider);
        mVerticalDividerRects = new ArrayList<Rect>();
        a.recycle();

        if (mHorizontalDivider != null) {
            mHorizontalDividerHeight = mHorizontalDivider.getIntrinsicHeight();
            // Make sure to have some height for the divider
            if (mHorizontalDividerHeight == -1) mHorizontalDividerHeight = 1;
        }

        if (mVerticalDivider != null) {
            mVerticalDividerWidth = mVerticalDivider.getIntrinsicWidth();
            // Make sure to have some width for the divider
            if (mVerticalDividerWidth == -1) mVerticalDividerWidth = 1;
        }

        // This view will be drawing the dividers.
        setWillNotDraw(false);

        // Arrange to get key events even when there's no focused item in
        // the in-call menu (i.e. when in touch mode).
        // (We *always* want key events whenever we're visible, so that we
        // can forward them to the InCallScreen activity; see dispatchKeyEvent().)
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        // The default ViewGroup.LayoutParams width and height are
        // WRAP_CONTENT.  (This applies to us right now since we
        // initially have no LayoutParams at all.)
        // But in the Menu framework, when returning a view from
        // onCreatePanelView(), a layout width of WRAP_CONTENT indicates
        // that you want the smaller-sized "More" menu frame.  We want the
        // full-screen-width menu frame instead, though, so we need to
        // give ourselves a LayoutParams with width==MATCH_PARENT.
        ViewGroup.LayoutParams lp =
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                           ViewGroup.LayoutParams.WRAP_CONTENT);
        setLayoutParams(lp);
    }

    /**
     * Null out our reference to the InCallScreen activity.
     * This indicates that the InCallScreen activity has been destroyed.
     */
    void clearInCallScreenReference() {
        mInCallScreen = null;
    }

    /**
     * Adds an InCallMenuItemView to the specified row.
     */
    /* package */ void addItemView(InCallMenuItemView itemView, int row) {
        if (DBG) log("addItemView(" + itemView + ", row " + row + ")...");

        if (row >= NUM_ROWS) {
            throw new IllegalStateException("Row index " + row + " > NUM_ROWS");
        }

        int indexInRow = mNumItemsForRow[row];
        if (indexInRow >= MAX_ITEMS_PER_ROW) {
            throw new IllegalStateException("Too many items (" + indexInRow + ") in row " + row);
        }
        mNumItemsForRow[row]++;
        mItems[row][indexInRow] = itemView;

        //
        // Finally, add this item as a child.
        //

        ViewGroup.LayoutParams lp = itemView.getLayoutParams();

        if (lp == null) {
            // Default layout parameters
            lp = new LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        }

        // Apply the background to the item view
        itemView.setBackgroundDrawable(mItemBackground.getConstantState().newDrawable());

        addView(itemView, lp);
    }

    /**
     * Precomputes the number of visible items per row, and the total
     * number of visible rows.  (A row with zero visible items isn't
     * drawn at all.)
     */
    /* package */ void updateVisibility() {
        if (DBG) log("updateVisibility()...");

        mNumVisibleRows = 0;

        for (int row = 0; row < NUM_ROWS; row++) {
            InCallMenuItemView[] thisRow = mItems[row];
            int numItemsThisRow = mNumItemsForRow[row];
            int numVisibleThisRow = 0;
            for (int itemIndex = 0; itemIndex < numItemsThisRow; itemIndex++) {
                // if (DBG) log("  - Checking item: " + mItems[row][itemIndex]);
                if  (mItems[row][itemIndex].isVisible()) numVisibleThisRow++;
            }
            if (DBG) log("==> Num visible for row " + row + ": " + numVisibleThisRow);
            mNumVisibleItemsForRow[row] = numVisibleThisRow;
            if (numVisibleThisRow > 0) mNumVisibleRows++;
        }
        if (DBG) log("==> Num visible rows: " + mNumVisibleRows);
    }

    /* package */ void dumpState() {
        if (DBG) log("============ dumpState() ============");
        if (DBG) log("- mItems LENGTH: " + mItems.length);
        for (int row = 0; row < NUM_ROWS; row++) {
            if (DBG) log("-      Row " + row + ": length " + mItems[row].length
                         + ", num items " + mNumItemsForRow[row]
                         + ", num visible " + mNumVisibleItemsForRow[row]);
        }
    }

    /**
     * The positioning algorithm that gets called from onMeasure.  It just
     * computes positions for each child, and then stores them in the
     * child's layout params.
     *
     * At this point the visibility of each item in mItems[][] is correct,
     * and mNumVisibleRows and mNumVisibleItemsForRow[] have already been
     * precomputed.
     *
     * @param menuWidth The width of this menu to assume for positioning
     * @param menuHeight The height of this menu to assume for positioning
     *
     * TODO: This is a near-exact duplicate of IconMenuView.positionChildren().
     * Consider abstracting this out into a more general-purpose "grid layout
     * with dividers" container that both classes could use...
     */
    private void positionChildren(int menuWidth, int menuHeight) {
        if (DBG) log("positionChildren(" + menuWidth + " x " + menuHeight + ")...");

        // Clear the containers for the positions where the dividers should be drawn
        if (mHorizontalDivider != null) mHorizontalDividerRects.clear();
        if (mVerticalDivider != null) mVerticalDividerRects.clear();

        InCallMenuItemView child;
        InCallMenuView.LayoutParams childLayoutParams = null;

        // Use float for this to get precise positions (uniform item widths
        // instead of last one taking any slack), and then convert to ints at last opportunity
        float itemLeft;
        float itemTop = 0;
        // Since each row can have a different number of items, this will be computed per row
        float itemWidth;
        // Subtract the space needed for the horizontal dividers
        final float itemHeight = (menuHeight - mHorizontalDividerHeight * (mNumVisibleRows - 1))
                / (float) mNumVisibleRows;

        // We add horizontal dividers between each visible row, so there should
        // be a total of mNumVisibleRows-1 of them.
        int numHorizDividersRemainingToDraw = mNumVisibleRows - 1;

        for (int row = 0; row < NUM_ROWS; row++) {
            int numItemsThisRow = mNumItemsForRow[row];
            int numVisibleThisRow = mNumVisibleItemsForRow[row];
            if (DBG) log("  - num visible for row " + row + ": " + numVisibleThisRow);
            if (numVisibleThisRow == 0) {
                continue;
            }

            InCallMenuItemView[] thisRow = mItems[row];

            // Start at the left
            itemLeft = 0;

            // Subtract the space needed for the vertical dividers, and
            // divide by the number of items.
            itemWidth = (menuWidth - mVerticalDividerWidth * (numVisibleThisRow - 1))
                    / (float) numVisibleThisRow;

            for (int itemIndex = 0; itemIndex < numItemsThisRow; itemIndex++) {
                child = mItems[row][itemIndex];

                if (!child.isVisible()) continue;

                if (DBG) log("==> child [" + row + "][" + itemIndex + "]: " + child);

                // Tell the child to be exactly this size
                child.measure(MeasureSpec.makeMeasureSpec((int) itemWidth, MeasureSpec.EXACTLY),
                              MeasureSpec.makeMeasureSpec((int) itemHeight, MeasureSpec.EXACTLY));

                // Remember the child's position for layout
                childLayoutParams = (InCallMenuView.LayoutParams) child.getLayoutParams();
                childLayoutParams.left = (int) itemLeft;
                childLayoutParams.right = (int) (itemLeft + itemWidth);
                childLayoutParams.top = (int) itemTop;
                childLayoutParams.bottom = (int) (itemTop + itemHeight);

                // Increment by item width
                itemLeft += itemWidth;

                // Add a vertical divider to draw
                if (mVerticalDivider != null) {
                    mVerticalDividerRects.add(new Rect((int) itemLeft,
                            (int) itemTop, (int) (itemLeft + mVerticalDividerWidth),
                            (int) (itemTop + itemHeight)));
                }

                // Increment by divider width (even if we're not computing
                // dividers, since we need to leave room for them when
                // calculating item positions)
                itemLeft += mVerticalDividerWidth;
            }

            // Last child on each row should extend to very right edge
            if (childLayoutParams != null) {
                childLayoutParams.right = menuWidth;
            }

            itemTop += itemHeight;

            // Add a horizontal divider (if we need one under this row)
            if ((mHorizontalDivider != null) && (numHorizDividersRemainingToDraw-- > 0)) {
                mHorizontalDividerRects.add(new Rect(0, (int) itemTop, menuWidth,
                                                     (int) (itemTop + mHorizontalDividerHeight)));
                itemTop += mHorizontalDividerHeight;
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (DBG) log("onMeasure(" + widthMeasureSpec + " x " + heightMeasureSpec + ")...");

        // Get the desired height of the icon menu view (last row of items does
        // not have a divider below)
        final int desiredHeight = (mRowHeight + mHorizontalDividerHeight) * mNumVisibleRows
                - mHorizontalDividerHeight;

        // Maximum possible width and desired height
        setMeasuredDimension(resolveSize(Integer.MAX_VALUE, widthMeasureSpec),
                             resolveSize(desiredHeight, heightMeasureSpec));

        // Position the children
        positionChildren(mMeasuredWidth, mMeasuredHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (DBG) log("onLayout(changed " + changed
                     + ", l " + l + " t " + t + " r " + r + " b " + b + ")...");

        View child;
        InCallMenuView.LayoutParams childLayoutParams;

        for (int i = getChildCount() - 1; i >= 0; i--) {
            child = getChildAt(i);
            childLayoutParams = (InCallMenuView.LayoutParams) child.getLayoutParams();

            // Layout children according to positions set during the measure
            child.layout(childLayoutParams.left, childLayoutParams.top,
                         childLayoutParams.right, childLayoutParams.bottom);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (DBG) log("onDraw()...");

        if (mHorizontalDivider != null) {
            // If we have a horizontal divider to draw, draw it at the remembered positions
            for (int i = mHorizontalDividerRects.size() - 1; i >= 0; i--) {
                mHorizontalDivider.setBounds(mHorizontalDividerRects.get(i));
                mHorizontalDivider.draw(canvas);
            }
        }

        if (mVerticalDivider != null) {
            // If we have a vertical divider to draw, draw it at the remembered positions
            for (int i = mVerticalDividerRects.size() - 1; i >= 0; i--) {
                mVerticalDivider.setBounds(mVerticalDividerRects.get(i));
                mVerticalDivider.draw(canvas);
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (DBG) log("dispatchKeyEvent(" + event + ")...");

        // In most other apps, when a menu is up, the menu itself handles
        // keypresses.  And keys that aren't handled by the menu do NOT
        // get dispatched to the current Activity.
        //
        // But in the in-call UI, we don't have any menu shortcuts, *and*
        // it's important for buttons like CALL to work normally even
        // while the menu is up.  So we handle ALL key events (with some
        // exceptions -- see below) by simply forwarding them to the
        // InCallScreen.

        int keyCode = event.getKeyCode();
        if (event.isDown()) {
            switch (keyCode) {
                // The BACK key dismisses the menu.
                case KeyEvent.KEYCODE_BACK:
                    if (DBG) log("==> BACK key!  handling it ourselves...");
                    // We don't need to do anything here (since BACK
                    // is magically handled by the framework); we just
                    // need to *not* forward it to the InCallScreen.
                    break;

                // Don't send KEYCODE_DPAD_CENTER/KEYCODE_ENTER to the
                // InCallScreen either, since the framework needs those to
                // activate the focused item when using the trackball.
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    break;

                // Anything else gets forwarded to the InCallScreen.
                default:
                    if (DBG) log("==> dispatchKeyEvent: forwarding event to the InCallScreen");
                    if (mInCallScreen != null) {
                        return mInCallScreen.onKeyDown(keyCode, event);
                    }
                    break;
            }
        } else if (mInCallScreen != null &&
                (keyCode == KeyEvent.KEYCODE_CALL ||
                        mInCallScreen.isKeyEventAcceptableDTMF(event))) {

            // Forward the key-up for the call and dialer buttons to the
            // InCallScreen.  All other key-up events are NOT handled here,
            // but instead fall through to dispatchKeyEvent from the superclass.
            if (DBG) log("==> dispatchKeyEvent: forwarding key up event to the InCallScreen");
            return mInCallScreen.onKeyUp(keyCode, event);
        }
        return super.dispatchKeyEvent(event);
    }


    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new InCallMenuView.LayoutParams(getContext(), attrs);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        // Override to allow type-checking of LayoutParams.
        return p instanceof InCallMenuView.LayoutParams;
    }

    /**
     * Layout parameters specific to InCallMenuView (stores the left, top,
     * right, bottom from the measure pass).
     */
    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        int left, top, right, bottom;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
