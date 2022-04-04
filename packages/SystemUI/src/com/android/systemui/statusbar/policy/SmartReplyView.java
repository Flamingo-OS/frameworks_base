package com.android.systemui.statusbar.policy;

import static java.lang.Float.NaN;

import android.annotation.ColorInt;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.text.Layout;
import android.text.TextPaint;
import android.text.method.TransformationMethod;
import android.util.AttributeSet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationUtils;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** View which displays smart reply and smart actions buttons in notifications. */
public class SmartReplyView extends ViewGroup {

    private static final String TAG = "SmartReplyView";

    private static final int MEASURE_SPEC_ANY_LENGTH =
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

    private static final Comparator<View> DECREASING_MEASURED_WIDTH_WITHOUT_PADDING_COMPARATOR =
            (v1, v2) -> ((v2.getMeasuredWidth() - v2.getPaddingLeft() - v2.getPaddingRight())
                    - (v1.getMeasuredWidth() - v1.getPaddingLeft() - v1.getPaddingRight()));

    private static final int SQUEEZE_FAILED = -1;

    /**
     * The upper bound for the height of this view in pixels. Notifications are automatically
     * recreated on density or font size changes so caching this should be fine.
     */
    private final int mHeightUpperLimit;

    /** Spacing to be applied between views. */
    private final int mSpacing;

    private final BreakIterator mBreakIterator;

    private PriorityQueue<Button> mCandidateButtonQueueForSqueezing;

    private View mSmartReplyContainer;

    /**
     * Whether the smart replies in this view were generated by the notification assistant. If not
     * they're provided by the app.
     */
    private boolean mSmartRepliesGeneratedByAssistant = false;

    @ColorInt private int mCurrentBackgroundColor;
    @ColorInt private final int mDefaultBackgroundColor;
    @ColorInt private final int mDefaultStrokeColor;
    @ColorInt private final int mDefaultTextColor;
    @ColorInt private final int mDefaultTextColorDarkBg;
    @ColorInt private final int mRippleColorDarkBg;
    @ColorInt private final int mRippleColor;
    private final int mStrokeWidth;
    private final double mMinStrokeContrast;

    @ColorInt private int mCurrentStrokeColor;
    @ColorInt private int mCurrentTextColor;
    @ColorInt private int mCurrentRippleColor;
    private boolean mCurrentColorized;
    private int mMaxSqueezeRemeasureAttempts;
    private int mMaxNumActions;
    private int mMinNumSystemGeneratedReplies;

    // DEBUG variables tracked for the dump()
    private long mLastDrawChildTime;
    private long mLastDispatchDrawTime;
    private long mLastMeasureTime;
    private int mTotalSqueezeRemeasureAttempts;
    private boolean mDidHideSystemReplies;

    public SmartReplyView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mHeightUpperLimit = NotificationUtils.getFontScaledHeight(mContext,
            R.dimen.smart_reply_button_max_height);

        mDefaultBackgroundColor = context.getColor(R.color.smart_reply_button_background);
        mDefaultTextColor = mContext.getColor(R.color.smart_reply_button_text);
        mDefaultTextColorDarkBg = mContext.getColor(R.color.smart_reply_button_text_dark_bg);
        mDefaultStrokeColor = mContext.getColor(R.color.smart_reply_button_stroke);
        mRippleColor = mContext.getColor(R.color.notification_ripple_untinted_color);
        mRippleColorDarkBg = Color.argb(Color.alpha(mRippleColor),
                255 /* red */, 255 /* green */, 255 /* blue */);
        mMinStrokeContrast = ContrastColorUtil.calculateContrast(mDefaultStrokeColor,
                mDefaultBackgroundColor);

        int spacing = 0;
        int strokeWidth = 0;

        final TypedArray arr = context.obtainStyledAttributes(attrs, R.styleable.SmartReplyView,
                0, 0);
        final int length = arr.getIndexCount();
        for (int i = 0; i < length; i++) {
            int attr = arr.getIndex(i);
            if (attr == R.styleable.SmartReplyView_spacing) {
                spacing = arr.getDimensionPixelSize(i, 0);
            } else if (attr == R.styleable.SmartReplyView_buttonStrokeWidth) {
                strokeWidth = arr.getDimensionPixelSize(i, 0);
            }
        }
        arr.recycle();

        mStrokeWidth = strokeWidth;
        mSpacing = spacing;

        mBreakIterator = BreakIterator.getLineInstance();

        setBackgroundTintColor(mDefaultBackgroundColor, false /* colorized */);
        reallocateCandidateButtonQueueForSqueezing();
    }

    /**
     * Inflate an instance of this class.
     */
    public static SmartReplyView inflate(Context context, SmartReplyConstants constants) {
        SmartReplyView view = (SmartReplyView) LayoutInflater.from(context).inflate(
                R.layout.smart_reply_view, null /* root */);
        view.setMaxNumActions(constants.getMaxNumActions());
        view.setMaxSqueezeRemeasureAttempts(constants.getMaxSqueezeRemeasureAttempts());
        view.setMinNumSystemGeneratedReplies(constants.getMinNumSystemGeneratedReplies());
        return view;
    }

    /**
     * Returns an upper bound for the height of this view in pixels. This method is intended to be
     * invoked before onMeasure, so it doesn't do any analysis on the contents of the buttons.
     */
    public int getHeightUpperLimit() {
       return mHeightUpperLimit;
    }

    private void reallocateCandidateButtonQueueForSqueezing() {
        // Instead of clearing the priority queue, we re-allocate so that it would fit all buttons
        // exactly. This avoids (1) wasting memory because PriorityQueue never shrinks and
        // (2) growing in onMeasure.
        // The constructor throws an IllegalArgument exception if initial capacity is less than 1.
        mCandidateButtonQueueForSqueezing = new PriorityQueue<>(
                Math.max(getChildCount(), 1), DECREASING_MEASURED_WIDTH_WITHOUT_PADDING_COMPARATOR);
    }

    /**
     * Reset the smart suggestions view to allow adding new replies and actions.
     */
    public void resetSmartSuggestions(View newSmartReplyContainer) {
        mSmartReplyContainer = newSmartReplyContainer;
        removeAllViews();
        setBackgroundTintColor(mDefaultBackgroundColor, false /* colorized */);
    }

    /** Add buttons to the {@link SmartReplyView} */
    public void addPreInflatedButtons(List<Button> smartSuggestionButtons) {
        for (Button button : smartSuggestionButtons) {
            addView(button);
            setButtonColors(button);
        }
        reallocateCandidateButtonQueueForSqueezing();
    }

    public void setMaxNumActions(int maxNumActions) {
        mMaxNumActions = maxNumActions;
    }

    public void setMinNumSystemGeneratedReplies(int minNumSystemGeneratedReplies) {
        mMinNumSystemGeneratedReplies = minNumSystemGeneratedReplies;
    }

    public void setMaxSqueezeRemeasureAttempts(int maxSqueezeRemeasureAttempts) {
        mMaxSqueezeRemeasureAttempts = maxSqueezeRemeasureAttempts;
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(mContext, attrs);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams params) {
        return new LayoutParams(params.width, params.height);
    }

    private void clearLayoutLineCount(View view) {
        if (view instanceof TextView) {
            ((TextView) view).nullLayouts();
            view.forceLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int targetWidth = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE : MeasureSpec.getSize(widthMeasureSpec);

        // Mark all buttons as hidden and un-squeezed.
        resetButtonsLayoutParams();
        mTotalSqueezeRemeasureAttempts = 0;

        if (!mCandidateButtonQueueForSqueezing.isEmpty()) {
            Log.wtf(TAG, "Single line button queue leaked between onMeasure calls");
            mCandidateButtonQueueForSqueezing.clear();
        }

        SmartSuggestionMeasures accumulatedMeasures = new SmartSuggestionMeasures(
                mPaddingLeft + mPaddingRight,
                0 /* maxChildHeight */);
        int displayedChildCount = 0;

        // Set up a list of suggestions where actions come before replies. Note that the Buttons
        // themselves have already been added to the view hierarchy in an order such that Smart
        // Replies are shown before Smart Actions. The order of the list below determines which
        // suggestions will be shown at all - only the first X elements are shown (where X depends
        // on how much space each suggestion button needs).
        List<View> smartActions = filterActionsOrReplies(SmartButtonType.ACTION);
        List<View> smartReplies = filterActionsOrReplies(SmartButtonType.REPLY);
        List<View> smartSuggestions = new ArrayList<>(smartActions);
        smartSuggestions.addAll(smartReplies);
        List<View> coveredSuggestions = new ArrayList<>();

        // SmartSuggestionMeasures for all action buttons, this will be filled in when the first
        // reply button is added.
        SmartSuggestionMeasures actionsMeasures = null;

        final int maxNumActions = mMaxNumActions;
        int numShownActions = 0;

        for (View child : smartSuggestions) {
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (maxNumActions != -1 // -1 means 'no limit'
                    && lp.mButtonType == SmartButtonType.ACTION
                    && numShownActions >= maxNumActions) {
                // We've reached the maximum number of actions, don't add another one!
                continue;
            }

            clearLayoutLineCount(child);
            child.measure(MEASURE_SPEC_ANY_LENGTH, heightMeasureSpec);
            if (((Button) child).getLayout() == null) {
                Log.wtf(TAG, "Button layout is null after measure.");
            }

            coveredSuggestions.add(child);

            final int lineCount = ((Button) child).getLineCount();
            if (lineCount < 1 || lineCount > 2) {
                // If smart reply has no text, or more than two lines, then don't show it.
                continue;
            }

            if (lineCount == 1) {
                mCandidateButtonQueueForSqueezing.add((Button) child);
            }

            // Remember the current measurements in case the current button doesn't fit in.
            SmartSuggestionMeasures originalMeasures = accumulatedMeasures.clone();
            if (actionsMeasures == null && lp.mButtonType == SmartButtonType.REPLY) {
                // We've added all actions (we go through actions first), now add their
                // measurements.
                actionsMeasures = accumulatedMeasures.clone();
            }

            final int spacing = displayedChildCount == 0 ? 0 : mSpacing;
            final int childWidth = child.getMeasuredWidth();
            final int childHeight = child.getMeasuredHeight();
            accumulatedMeasures.mMeasuredWidth += spacing + childWidth;
            accumulatedMeasures.mMaxChildHeight =
                    Math.max(accumulatedMeasures.mMaxChildHeight, childHeight);

            // If the last button doesn't fit into the remaining width, try squeezing preceding
            // smart reply buttons.
            if (accumulatedMeasures.mMeasuredWidth > targetWidth) {
                // Keep squeezing preceding and current smart reply buttons until they all fit.
                while (accumulatedMeasures.mMeasuredWidth > targetWidth
                        && !mCandidateButtonQueueForSqueezing.isEmpty()) {
                    final Button candidate = mCandidateButtonQueueForSqueezing.poll();
                    final int squeezeReduction = squeezeButton(candidate, heightMeasureSpec);
                    if (squeezeReduction != SQUEEZE_FAILED) {
                        accumulatedMeasures.mMaxChildHeight =
                                Math.max(accumulatedMeasures.mMaxChildHeight,
                                        candidate.getMeasuredHeight());
                        accumulatedMeasures.mMeasuredWidth -= squeezeReduction;
                    }
                }

                // If the current button still doesn't fit after squeezing all buttons, undo the
                // last squeezing round.
                if (accumulatedMeasures.mMeasuredWidth > targetWidth) {
                    accumulatedMeasures = originalMeasures;

                    // Mark all buttons from the last squeezing round as "failed to squeeze", so
                    // that they're re-measured without squeezing later.
                    markButtonsWithPendingSqueezeStatusAs(
                            LayoutParams.SQUEEZE_STATUS_FAILED, coveredSuggestions);

                    // The current button doesn't fit, keep on adding lower-priority buttons in case
                    // any of those fit.
                    continue;
                }

                // The current button fits, so mark all squeezed buttons as "successfully squeezed"
                // to prevent them from being un-squeezed in a subsequent squeezing round.
                markButtonsWithPendingSqueezeStatusAs(
                        LayoutParams.SQUEEZE_STATUS_SUCCESSFUL, coveredSuggestions);
            }

            lp.show = true;
            displayedChildCount++;
            if (lp.mButtonType == SmartButtonType.ACTION) {
                numShownActions++;
            }
        }

        mDidHideSystemReplies = false;
        if (mSmartRepliesGeneratedByAssistant) {
            if (!gotEnoughSmartReplies(smartReplies)) {
                // We don't have enough smart replies - hide all of them.
                for (View smartReplyButton : smartReplies) {
                    final LayoutParams lp = (LayoutParams) smartReplyButton.getLayoutParams();
                    lp.show = false;
                }
                // Reset our measures back to when we had only added actions (before adding
                // replies).
                accumulatedMeasures = actionsMeasures;
                mDidHideSystemReplies = true;
            }
        }

        // We're done squeezing buttons, so we can clear the priority queue.
        mCandidateButtonQueueForSqueezing.clear();

        // Finally, we need to re-measure some buttons.
        remeasureButtonsIfNecessary(accumulatedMeasures.mMaxChildHeight);

        int buttonHeight = Math.max(getSuggestedMinimumHeight(), mPaddingTop
                + accumulatedMeasures.mMaxChildHeight + mPaddingBottom);

        setMeasuredDimension(
                resolveSize(Math.max(getSuggestedMinimumWidth(),
                                     accumulatedMeasures.mMeasuredWidth),
                            widthMeasureSpec),
                resolveSize(buttonHeight, heightMeasureSpec));
        mLastMeasureTime = SystemClock.elapsedRealtime();
    }

    // TODO: this should be replaced, and instead, setMinSystemGenerated... should be invoked
    //  with MAX_VALUE if mSmartRepliesGeneratedByAssistant would be false (essentially, this is a
    //  ViewModel decision, as opposed to a View decision)
    void setSmartRepliesGeneratedByAssistant(boolean fromAssistant) {
        mSmartRepliesGeneratedByAssistant = fromAssistant;
    }

    void hideSmartSuggestions() {
        if (mSmartReplyContainer != null) {
            mSmartReplyContainer.setVisibility(View.GONE);
        }
    }

    /** Dump internal state for debugging */
    public void dump(IndentingPrintWriter pw) {
        pw.println(this);
        pw.increaseIndent();
        pw.print("mMaxSqueezeRemeasureAttempts=");
        pw.println(mMaxSqueezeRemeasureAttempts);
        pw.print("mTotalSqueezeRemeasureAttempts=");
        pw.println(mTotalSqueezeRemeasureAttempts);
        pw.print("mMaxNumActions=");
        pw.println(mMaxNumActions);
        pw.print("mSmartRepliesGeneratedByAssistant=");
        pw.println(mSmartRepliesGeneratedByAssistant);
        pw.print("mMinNumSystemGeneratedReplies=");
        pw.println(mMinNumSystemGeneratedReplies);
        pw.print("mHeightUpperLimit=");
        pw.println(mHeightUpperLimit);
        pw.print("mDidHideSystemReplies=");
        pw.println(mDidHideSystemReplies);
        long now = SystemClock.elapsedRealtime();
        pw.print("lastMeasureAge (s)=");
        pw.println(mLastMeasureTime == 0 ? NaN : (now - mLastMeasureTime) / 1000.0f);
        pw.print("lastDrawChildAge (s)=");
        pw.println(mLastDrawChildTime == 0 ? NaN : (now - mLastDrawChildTime) / 1000.0f);
        pw.print("lastDispatchDrawAge (s)=");
        pw.println(mLastDispatchDrawTime == 0 ? NaN : (now - mLastDispatchDrawTime) / 1000.0f);
        int numChildren = getChildCount();
        pw.print("children: num=");
        pw.println(numChildren);
        pw.increaseIndent();
        for (int i = 0; i < numChildren; i++) {
            View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            pw.print("[");
            pw.print(i);
            pw.print("] type=");
            pw.print(lp.mButtonType);
            pw.print(" squeezeStatus=");
            pw.print(lp.squeezeStatus);
            pw.print(" show=");
            pw.print(lp.show);
            pw.print(" view=");
            pw.println(child);
        }
        pw.decreaseIndent();
        pw.decreaseIndent();
    }

    /**
     * Fields we keep track of inside onMeasure() to correctly measure the SmartReplyView depending
     * on which suggestions are added.
     */
    private static class SmartSuggestionMeasures {
        int mMeasuredWidth = -1;
        int mMaxChildHeight = -1;

        SmartSuggestionMeasures(int measuredWidth, int maxChildHeight) {
            this.mMeasuredWidth = measuredWidth;
            this.mMaxChildHeight = maxChildHeight;
        }

        public SmartSuggestionMeasures clone() {
            return new SmartSuggestionMeasures(mMeasuredWidth, mMaxChildHeight);
        }
    }

    /**
     * Returns whether our notification contains at least N smart replies (or 0) where N is
     * determined by {@link SmartReplyConstants}.
     */
    private boolean gotEnoughSmartReplies(List<View> smartReplies) {
        if (mMinNumSystemGeneratedReplies <= 1) {
            // Count is irrelevant, do not bother.
            return true;
        }
        int numShownReplies = 0;
        for (View smartReplyButton : smartReplies) {
            final LayoutParams lp = (LayoutParams) smartReplyButton.getLayoutParams();
            if (lp.show) {
                numShownReplies++;
            }
        }
        if (numShownReplies == 0 || numShownReplies >= mMinNumSystemGeneratedReplies) {
            // We have enough replies, yay!
            return true;
        }
        return false;
    }

    private List<View> filterActionsOrReplies(SmartButtonType buttonType) {
        List<View> actions = new ArrayList<>();
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (child.getVisibility() != View.VISIBLE || !(child instanceof Button)) {
                continue;
            }
            if (lp.mButtonType == buttonType) {
                actions.add(child);
            }
        }
        return actions;
    }

    private void resetButtonsLayoutParams() {
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            lp.show = false;
            lp.squeezeStatus = LayoutParams.SQUEEZE_STATUS_NONE;
        }
    }

    private int squeezeButton(Button button, int heightMeasureSpec) {
        final int estimatedOptimalTextWidth = estimateOptimalSqueezedButtonTextWidth(button);
        if (estimatedOptimalTextWidth == SQUEEZE_FAILED) {
            return SQUEEZE_FAILED;
        }
        return squeezeButtonToTextWidth(button, heightMeasureSpec, estimatedOptimalTextWidth);
    }

    private int estimateOptimalSqueezedButtonTextWidth(Button button) {
        // Find a line-break point in the middle of the smart reply button text.
        final String rawText = button.getText().toString();

        // The button sometimes has a transformation affecting text layout (e.g. all caps).
        final TransformationMethod transformation = button.getTransformationMethod();
        final String text = transformation == null ?
                rawText : transformation.getTransformation(rawText, button).toString();
        final int length = text.length();
        mBreakIterator.setText(text);

        if (mBreakIterator.preceding(length / 2) == BreakIterator.DONE) {
            if (mBreakIterator.next() == BreakIterator.DONE) {
                // Can't find a single possible line break in either direction.
                return SQUEEZE_FAILED;
            }
        }

        final TextPaint paint = button.getPaint();
        final int initialPosition = mBreakIterator.current();
        final float initialLeftTextWidth = Layout.getDesiredWidth(text, 0, initialPosition, paint);
        final float initialRightTextWidth =
                Layout.getDesiredWidth(text, initialPosition, length, paint);
        float optimalTextWidth = Math.max(initialLeftTextWidth, initialRightTextWidth);

        if (initialLeftTextWidth != initialRightTextWidth) {
            // See if there's a better line-break point (leading to a more narrow button) in
            // either left or right direction.
            final boolean moveLeft = initialLeftTextWidth > initialRightTextWidth;
            final int maxSqueezeRemeasureAttempts = mMaxSqueezeRemeasureAttempts;
            for (int i = 0; i < maxSqueezeRemeasureAttempts; i++) {
                mTotalSqueezeRemeasureAttempts++;
                final int newPosition =
                        moveLeft ? mBreakIterator.previous() : mBreakIterator.next();
                if (newPosition == BreakIterator.DONE) {
                    break;
                }

                final float newLeftTextWidth = Layout.getDesiredWidth(text, 0, newPosition, paint);
                final float newRightTextWidth =
                        Layout.getDesiredWidth(text, newPosition, length, paint);
                final float newOptimalTextWidth = Math.max(newLeftTextWidth, newRightTextWidth);
                if (newOptimalTextWidth < optimalTextWidth) {
                    optimalTextWidth = newOptimalTextWidth;
                } else {
                    break;
                }

                boolean tooFar = moveLeft
                        ? newLeftTextWidth <= newRightTextWidth
                        : newLeftTextWidth >= newRightTextWidth;
                if (tooFar) {
                    break;
                }
            }
        }

        return (int) Math.ceil(optimalTextWidth);
    }

    /**
     * Returns the combined width of the left drawable (the action icon) and the padding between the
     * drawable and the button text.
     */
    private int getLeftCompoundDrawableWidthWithPadding(Button button) {
        Drawable[] drawables = button.getCompoundDrawables();
        Drawable leftDrawable = drawables[0];
        if (leftDrawable == null) return 0;

        return leftDrawable.getBounds().width() + button.getCompoundDrawablePadding();
    }

    private int squeezeButtonToTextWidth(Button button, int heightMeasureSpec, int textWidth) {
        int oldWidth = button.getMeasuredWidth();

        // Re-measure the squeezed smart reply button.
        clearLayoutLineCount(button);
        final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(
                button.getPaddingLeft() + button.getPaddingRight() + textWidth
                      + getLeftCompoundDrawableWidthWithPadding(button), MeasureSpec.AT_MOST);
        button.measure(widthMeasureSpec, heightMeasureSpec);
        if (button.getLayout() == null) {
            Log.wtf(TAG, "Button layout is null after measure.");
        }

        final int newWidth = button.getMeasuredWidth();

        final LayoutParams lp = (LayoutParams) button.getLayoutParams();
        if (button.getLineCount() > 2 || newWidth >= oldWidth) {
            lp.squeezeStatus = LayoutParams.SQUEEZE_STATUS_FAILED;
            return SQUEEZE_FAILED;
        } else {
            lp.squeezeStatus = LayoutParams.SQUEEZE_STATUS_PENDING;
            return oldWidth - newWidth;
        }
    }

    private void remeasureButtonsIfNecessary(int maxChildHeight) {
        final int maxChildHeightMeasure =
                MeasureSpec.makeMeasureSpec(maxChildHeight, MeasureSpec.EXACTLY);

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (!lp.show) {
                continue;
            }

            boolean requiresNewMeasure = false;
            int newWidth = child.getMeasuredWidth();

            // Re-measure reason 1: The button needs to be un-squeezed (either because it resulted
            // in more than two lines or because it was unnecessary).
            if (lp.squeezeStatus == LayoutParams.SQUEEZE_STATUS_FAILED) {
                requiresNewMeasure = true;
                newWidth = Integer.MAX_VALUE;
            }

            // Re-measure reason 2: The button's height is less than the max height of all buttons
            // (all should have the same height).
            if (child.getMeasuredHeight() != maxChildHeight) {
                requiresNewMeasure = true;
            }

            if (requiresNewMeasure) {
                child.measure(MeasureSpec.makeMeasureSpec(newWidth, MeasureSpec.AT_MOST),
                        maxChildHeightMeasure);
            }
        }
    }

    private void markButtonsWithPendingSqueezeStatusAs(
            int squeezeStatus, List<View> coveredChildren) {
        for (View child : coveredChildren) {
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (lp.squeezeStatus == LayoutParams.SQUEEZE_STATUS_PENDING) {
                lp.squeezeStatus = squeezeStatus;
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final boolean isRtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        final int width = right - left;
        int position = isRtl ? width - mPaddingRight : mPaddingLeft;

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (!lp.show) {
                continue;
            }

            final int childWidth = child.getMeasuredWidth();
            final int childHeight = child.getMeasuredHeight();
            final int childLeft = isRtl ? position - childWidth : position;
            child.layout(childLeft, 0, childLeft + childWidth, childHeight);

            final int childWidthWithSpacing = childWidth + mSpacing;
            if (isRtl) {
                position -= childWidthWithSpacing;
            } else {
                position += childWidthWithSpacing;
            }
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        final LayoutParams lp = (LayoutParams) child.getLayoutParams();
        if (!lp.show) {
            return false;
        }
        mLastDrawChildTime = SystemClock.elapsedRealtime();
        return super.drawChild(canvas, child, drawingTime);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        mLastDispatchDrawTime = SystemClock.elapsedRealtime();
    }

    /**
     * Set the current background color of the notification so that the smart reply buttons can
     * match it, and calculate other colors (e.g. text, ripple, stroke)
     */
    public void setBackgroundTintColor(int backgroundColor, boolean colorized) {
        if (backgroundColor == mCurrentBackgroundColor && colorized == mCurrentColorized) {
            // Same color ignoring.
           return;
        }
        mCurrentBackgroundColor = backgroundColor;
        mCurrentColorized = colorized;

        final boolean dark = Notification.Builder.isColorDark(backgroundColor);

        mCurrentTextColor = ContrastColorUtil.ensureTextContrast(
                dark ? mDefaultTextColorDarkBg : mDefaultTextColor,
                backgroundColor | 0xff000000, dark);
        mCurrentStrokeColor = colorized ? mCurrentTextColor : ContrastColorUtil.ensureContrast(
                mDefaultStrokeColor, backgroundColor | 0xff000000, dark, mMinStrokeContrast);
        mCurrentRippleColor = dark ? mRippleColorDarkBg : mRippleColor;

        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            setButtonColors((Button) getChildAt(i));
        }
    }

    private void setButtonColors(Button button) {
        Drawable drawable = button.getBackground();
        if (drawable instanceof RippleDrawable) {
            // Mutate in case other notifications are using this drawable.
            drawable = drawable.mutate();
            RippleDrawable ripple = (RippleDrawable) drawable;
            ripple.setColor(ColorStateList.valueOf(mCurrentRippleColor));
            Drawable inset = ripple.getDrawable(0);
            if (inset instanceof InsetDrawable) {
                Drawable background = ((InsetDrawable) inset).getDrawable();
                if (background instanceof GradientDrawable) {
                    GradientDrawable gradientDrawable = (GradientDrawable) background;
                    gradientDrawable.setColor(mCurrentBackgroundColor);
                    gradientDrawable.setStroke(mStrokeWidth, mCurrentStrokeColor);
                }
            }
            button.setBackground(drawable);
        }
        button.setTextColor(mCurrentTextColor);
    }

    enum SmartButtonType {
        REPLY,
        ACTION
    }

    @VisibleForTesting
    static class LayoutParams extends ViewGroup.LayoutParams {

        /** Button is not squeezed. */
        private static final int SQUEEZE_STATUS_NONE = 0;

        /**
         * Button was successfully squeezed, but it might be un-squeezed later if the squeezing
         * turns out to have been unnecessary (because there's still not enough space to add another
         * button).
         */
        private static final int SQUEEZE_STATUS_PENDING = 1;

        /** Button was successfully squeezed and it won't be un-squeezed. */
        private static final int SQUEEZE_STATUS_SUCCESSFUL = 2;

        /**
         * Button wasn't successfully squeezed. The squeezing resulted in more than two lines of
         * text or it didn't reduce the button's width at all. The button will have to be
         * re-measured to use only one line of text.
         */
        private static final int SQUEEZE_STATUS_FAILED = 3;

        private boolean show = false;
        private int squeezeStatus = SQUEEZE_STATUS_NONE;
        SmartButtonType mButtonType = SmartButtonType.REPLY;

        private LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        private LayoutParams(int width, int height) {
            super(width, height);
        }

        @VisibleForTesting
        boolean isShown() {
            return show;
        }
    }

    /**
     * Data class for smart replies.
     */
    public static class SmartReplies {
        @NonNull
        public final RemoteInput remoteInput;
        @NonNull
        public final PendingIntent pendingIntent;
        @NonNull
        public final List<CharSequence> choices;
        public final boolean fromAssistant;

        public SmartReplies(@NonNull List<CharSequence> choices, @NonNull RemoteInput remoteInput,
                @NonNull PendingIntent pendingIntent, boolean fromAssistant) {
            this.choices = choices;
            this.remoteInput = remoteInput;
            this.pendingIntent = pendingIntent;
            this.fromAssistant = fromAssistant;
        }
    }


    /**
     * Data class for smart actions.
     */
    public static class SmartActions {
        @NonNull
        public final List<Notification.Action> actions;
        public final boolean fromAssistant;

        public SmartActions(@NonNull List<Notification.Action> actions, boolean fromAssistant) {
            this.actions = actions;
            this.fromAssistant = fromAssistant;
        }
    }
}
