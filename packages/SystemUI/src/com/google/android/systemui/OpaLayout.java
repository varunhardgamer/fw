package com.google.android.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.RenderNodeAnimator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.phone.NavBarButtonProvider.ButtonInterface;
import com.android.systemui.statusbar.policy.KeyButtonView;
import com.android.systemui.tuner.TunerService.Tunable;

import com.android.systemui.Dependency;
import com.android.systemui.OverviewProxyService;

import com.android.systemui.statusbar.phone.ShadowKeyDrawable;

public class OpaLayout extends FrameLayout implements ButtonInterface, Tunable {

    private static final int ANIMATION_STATE_NONE = 0;
    private static final int ANIMATION_STATE_DIAMOND = 1;
    private static final int ANIMATION_STATE_RETRACT = 2;
    private static final int ANIMATION_STATE_OTHER = 3;

    private static final int MIN_DIAMOND_DURATION = 100;
    private static final int COLLAPSE_ANIMATION_DURATION_RY = 83;
    private static final int COLLAPSE_ANIMATION_DURATION_BG = 100;
    private static final int LINE_ANIMATION_DURATION_Y = 275;
    private static final int LINE_ANIMATION_DURATION_X = 133;
    private static final int RETRACT_ANIMATION_DURATION = 300;
    private static final int DIAMOND_ANIMATION_DURATION = 200;
    private static final int HALO_ANIMATION_DURATION = 100;
    private static final int OPA_FADE_IN_DURATION = 50;
    private static final int OPA_FADE_OUT_DURATION = 250;

    private static final int DOTS_RESIZE_DURATION = 200;
    private static final int HOME_RESIZE_DURATION = 83;

    private static final int HOME_REAPPEAR_ANIMATION_OFFSET = 33;
    private static final int HOME_REAPPEAR_DURATION = 150;

    private static final float DIAMOND_DOTS_SCALE_FACTOR = 0.8f;
    private static final float DIAMOND_HOME_SCALE_FACTOR = 0.625f;
    private static final float HALO_SCALE_FACTOR = 0.47619048f;

    private static final String NAV_BAR_VIEWS = "sysui_nav_bar";

    private KeyButtonView mHome;

    private int mAnimationState;
    private final ArraySet<Animator> mCurrentAnimators;

    private boolean mVertical;
    private boolean mIsPressed;
    private boolean mLongClicked;
    private boolean mOpaEnabled;
    private long mStartTime;

    private View mRed;
    private View mBlue;
    private View mGreen;
    private View mYellow;
    private ImageView mWhite;

    private int mDarkModeFillColor;
    private int mLightModeFillColor;
    private int mIconTint = Color.WHITE;

    private View mTop;
    private View mRight;
    private View mLeft;
    private View mBottom;

    private final Runnable mCheckLongPress;
    private final Runnable mRetract;

    private final Interpolator mRetractInterpolator;
    private final Interpolator mCollapseInterpolator;
    private final Interpolator mDiamondInterpolator;
    private final Interpolator mDotsFullSizeInterpolator;
    private final Interpolator mFastOutSlowInInterpolator;
    private final Interpolator mHomeDisappearInterpolator;
    private SettingsObserver mSettingsObserver;

    protected class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
           ContentResolver resolver = mContext.getContentResolver();
           resolver.registerContentObserver(Settings.System.getUriFor(
                  Settings.System.PIXEL_NAV_ANIMATION),
                  false, this, UserHandle.USER_CURRENT);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
           super.onChange(selfChange, uri);
           setOpaEnabled(true);
        }
    }

    private OverviewProxyService mOverviewProxyService;

    public OpaLayout(Context context) {
        super(context);
        mDarkModeFillColor = context.getColor(R.color.dark_mode_icon_color_single_tone);
        mLightModeFillColor = context.getColor(R.color.light_mode_icon_color_single_tone);
        mFastOutSlowInInterpolator = Interpolators.FAST_OUT_SLOW_IN;
        mHomeDisappearInterpolator = new PathInterpolator(0.8f, 0f, 1f, 1f);
        mCollapseInterpolator = Interpolators.FAST_OUT_LINEAR_IN;
        mDotsFullSizeInterpolator = new PathInterpolator(0.4f, 0f, 0f, 1f);
        mRetractInterpolator = new PathInterpolator(0.4f, 0f, 0f, 1f);
        mDiamondInterpolator = new PathInterpolator(0.2f, 0f, 0.2f, 1f);
        mCheckLongPress = new Runnable() {
            @Override
            public void run() {
                if (OpaLayout.this.mIsPressed) {
                    OpaLayout.this.mLongClicked = true;
                }
            }
        };
        mRetract = new Runnable() {
            @Override
            public void run() {
                OpaLayout.this.cancelCurrentAnimation();
                OpaLayout.this.startRetractAnimation();
                hideAllOpa();
            }
        };
        mAnimationState = OpaLayout.ANIMATION_STATE_NONE;
        mCurrentAnimators = new ArraySet<Animator>();
        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(new Handler());
        }
        mSettingsObserver.observe();
    }

    public OpaLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDarkModeFillColor = context.getColor(R.color.dark_mode_icon_color_single_tone);
        mLightModeFillColor = context.getColor(R.color.light_mode_icon_color_single_tone);
        mFastOutSlowInInterpolator = Interpolators.FAST_OUT_SLOW_IN;
        mHomeDisappearInterpolator = new PathInterpolator(0.8f, 0f, 1f, 1f);
        mCollapseInterpolator = Interpolators.FAST_OUT_LINEAR_IN;
        mDotsFullSizeInterpolator = new PathInterpolator(0.4f, 0f, 0f, 1f);
        mRetractInterpolator = new PathInterpolator(0.4f, 0f, 0f, 1f);
        mDiamondInterpolator = new PathInterpolator(0.2f, 0f, 0.2f, 1f);
        mCheckLongPress = new Runnable() {
            @Override
            public void run() {
                if (OpaLayout.this.mIsPressed) {
                    OpaLayout.this.mLongClicked = true;
                }
            }
        };
        mRetract = new Runnable() {
            @Override
            public void run() {
                OpaLayout.this.cancelCurrentAnimation();
                OpaLayout.this.startRetractAnimation();
                hideAllOpa();
            }
        };
        mAnimationState = OpaLayout.ANIMATION_STATE_NONE;
        mCurrentAnimators = new ArraySet<Animator>();
        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(new Handler());
        }
        mSettingsObserver.observe();
    }

    public OpaLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDarkModeFillColor = context.getColor(R.color.dark_mode_icon_color_single_tone);
        mLightModeFillColor = context.getColor(R.color.light_mode_icon_color_single_tone);
        mFastOutSlowInInterpolator = Interpolators.FAST_OUT_SLOW_IN;
        mHomeDisappearInterpolator = new PathInterpolator(0.8f, 0f, 1f, 1f);
        mCollapseInterpolator = Interpolators.FAST_OUT_LINEAR_IN;
        mDotsFullSizeInterpolator = new PathInterpolator(0.4f, 0f, 0f, 1f);
        mRetractInterpolator = new PathInterpolator(0.4f, 0f, 0f, 1f);
        mDiamondInterpolator = new PathInterpolator(0.2f, 0f, 0.2f, 1f);
        mCheckLongPress = new Runnable() {
            @Override
            public void run() {
                if (OpaLayout.this.mIsPressed) {
                    OpaLayout.this.mLongClicked = true;
                }
            }
        };
        mRetract = new Runnable() {
            @Override
            public void run() {
                OpaLayout.this.cancelCurrentAnimation();
                OpaLayout.this.startRetractAnimation();
                hideAllOpa();
            }
        };
        mAnimationState = OpaLayout.ANIMATION_STATE_NONE;
        mCurrentAnimators = new ArraySet<Animator>();
        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
	if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(new Handler());
        }
	mSettingsObserver.observe();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (NAV_BAR_VIEWS.equals(key)) {
            updateHomeDrawable(mIconTint);
        }
    }

    public OpaLayout(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mDarkModeFillColor = context.getColor(R.color.dark_mode_icon_color_single_tone);
        mLightModeFillColor = context.getColor(R.color.light_mode_icon_color_single_tone);
        mFastOutSlowInInterpolator = Interpolators.FAST_OUT_SLOW_IN;
        mHomeDisappearInterpolator = new PathInterpolator(0.8f, 0f, 1f, 1f);
        mCollapseInterpolator = Interpolators.FAST_OUT_LINEAR_IN;
        mDotsFullSizeInterpolator = new PathInterpolator(0.4f, 0f, 0f, 1f);
        mRetractInterpolator = new PathInterpolator(0.4f, 0f, 0f, 1f);
        mDiamondInterpolator = new PathInterpolator(0.2f, 0f, 0.2f, 1f);
        mCheckLongPress = new Runnable() {
            @Override
            public void run() {
                if (OpaLayout.this.mIsPressed) {
                    OpaLayout.this.mLongClicked = true;
                }
            }
        };
        mRetract = new Runnable() {
            @Override
            public void run() {
                OpaLayout.this.cancelCurrentAnimation();
                OpaLayout.this.startRetractAnimation();
                hideAllOpa();
            }
        };
        mAnimationState = OpaLayout.ANIMATION_STATE_NONE;
        mCurrentAnimators = new ArraySet<Animator>();
        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(new Handler());
        }
        mSettingsObserver.observe();
    }

    private void startAll(ArraySet<Animator> animators) {
        showAllOpa();
        for(int i=0; i < animators.size(); i++) {
            Animator curAnim = (Animator) mCurrentAnimators.valueAt(i);
            curAnim.start();
        }
    }

    private void startCollapseAnimation() {
        mCurrentAnimators.clear();
        mCurrentAnimators.addAll(getCollapseAnimatorSet());
        mAnimationState = OpaLayout.ANIMATION_STATE_OTHER;
        startAll(mCurrentAnimators);
    }

    private void startDiamondAnimation() {
        mCurrentAnimators.clear();
        mCurrentAnimators.addAll(getDiamondAnimatorSet());
        mAnimationState = OpaLayout.ANIMATION_STATE_DIAMOND;
        startAll(mCurrentAnimators);
    }

    private void startLineAnimation() {
        mCurrentAnimators.clear();
        mCurrentAnimators.addAll(getLineAnimatorSet());
        mAnimationState = OpaLayout.ANIMATION_STATE_OTHER;
        startAll(mCurrentAnimators);
    }

    private void startRetractAnimation() {
        mCurrentAnimators.clear();
        mCurrentAnimators.addAll(getRetractAnimatorSet());
        mAnimationState = OpaLayout.ANIMATION_STATE_RETRACT;
        startAll(mCurrentAnimators);
    }

    private void cancelCurrentAnimation() {
        if(mCurrentAnimators.isEmpty())
            return;
        for(int i=0; i < mCurrentAnimators.size(); i++) {
            Animator curAnim = (Animator) mCurrentAnimators.valueAt(i);
            curAnim.removeAllListeners();
            curAnim.cancel();
        }
        mCurrentAnimators.clear();
        mAnimationState = OpaLayout.ANIMATION_STATE_NONE;
    }

    private void endCurrentAnimation() {
        if(mCurrentAnimators.isEmpty())
            return;
        for(int i=0; i < mCurrentAnimators.size(); i++) {
            Animator curAnim = (Animator) mCurrentAnimators.valueAt(i);
            curAnim.removeAllListeners();
            curAnim.end();
        }
        mCurrentAnimators.clear();
        mAnimationState = OpaLayout.ANIMATION_STATE_NONE;
    }

    private ArraySet<Animator> getCollapseAnimatorSet() {
        final ArraySet<Animator> set = new ArraySet<Animator>();
        Animator animator;
        if (mVertical) {
            animator = getDeltaAnimatorY(mRed, mCollapseInterpolator, -getPxVal(R.dimen.opa_line_x_collapse_ry), OpaLayout.COLLAPSE_ANIMATION_DURATION_RY);
        } else {
            animator = getDeltaAnimatorX(mRed, mCollapseInterpolator, getPxVal(R.dimen.opa_line_x_collapse_ry), OpaLayout.COLLAPSE_ANIMATION_DURATION_RY);
        }
        set.add(animator);
        set.add(getScaleAnimatorX(mRed, 1.0f, OpaLayout.DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        set.add(getScaleAnimatorY(mRed, 1.0f, OpaLayout.DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        Animator animator2;
        if (mVertical) {
            animator2 = getDeltaAnimatorY(mBlue, mCollapseInterpolator, -getPxVal(R.dimen.opa_line_x_collapse_bg), OpaLayout.COLLAPSE_ANIMATION_DURATION_BG);
        } else {
            animator2 = getDeltaAnimatorX(mBlue, mCollapseInterpolator, getPxVal(R.dimen.opa_line_x_collapse_bg), OpaLayout.COLLAPSE_ANIMATION_DURATION_BG);
        }
        set.add(animator2);
        set.add(getScaleAnimatorX(mBlue, 1.0f, OpaLayout.DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        set.add(getScaleAnimatorY(mBlue, 1.0f, OpaLayout.DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        Animator animator3;
        if (mVertical) {
            animator3 = getDeltaAnimatorY(mYellow, mCollapseInterpolator, getPxVal(R.dimen.opa_line_x_collapse_ry), OpaLayout.COLLAPSE_ANIMATION_DURATION_RY);
        } else {
            animator3 = getDeltaAnimatorX(mYellow, mCollapseInterpolator, -getPxVal(R.dimen.opa_line_x_collapse_ry), OpaLayout.COLLAPSE_ANIMATION_DURATION_RY);
        }
        set.add(animator3);
        set.add(getScaleAnimatorX(mYellow, 1.0f, OpaLayout.DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        set.add(getScaleAnimatorY(mYellow, 1.0f, OpaLayout.DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        Animator animator4;
        if (mVertical) {
            animator4 = getDeltaAnimatorY(mGreen, mCollapseInterpolator, getPxVal(R.dimen.opa_line_x_collapse_bg), OpaLayout.COLLAPSE_ANIMATION_DURATION_BG);
        } else {
            animator4 = getDeltaAnimatorX(mGreen, mCollapseInterpolator, -getPxVal(R.dimen.opa_line_x_collapse_bg), OpaLayout.COLLAPSE_ANIMATION_DURATION_BG);
        }
        set.add(animator4);
        set.add(getScaleAnimatorX(mGreen, 1.0f, OpaLayout.DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        set.add(getScaleAnimatorY(mGreen, 1.0f, OpaLayout.DOTS_RESIZE_DURATION, mDotsFullSizeInterpolator));
        final Animator scaleAnimatorX = getScaleAnimatorX(mWhite, 1.0f, OpaLayout.HOME_REAPPEAR_DURATION, mFastOutSlowInInterpolator);
        final Animator scaleAnimatorY = getScaleAnimatorY(mWhite, 1.0f, OpaLayout.HOME_REAPPEAR_DURATION, mFastOutSlowInInterpolator);
        scaleAnimatorX.setStartDelay(OpaLayout.HOME_REAPPEAR_ANIMATION_OFFSET);
        scaleAnimatorY.setStartDelay(OpaLayout.HOME_REAPPEAR_ANIMATION_OFFSET);
        set.add(scaleAnimatorX);
        set.add(scaleAnimatorY);
        getLongestAnim((set)).addListener((Animator.AnimatorListener)new AnimatorListenerAdapter() {
            public void onAnimationEnd(final Animator animator) {
                OpaLayout.this.mCurrentAnimators.clear();
                OpaLayout.this.mAnimationState = OpaLayout.ANIMATION_STATE_NONE;
                hideAllOpa();
            }
        });
        return set;
    }

    private ArraySet<Animator> getDiamondAnimatorSet() {
        final ArraySet<Animator> set = new ArraySet<Animator>();
        set.add(getDeltaAnimatorY(mTop, mDiamondInterpolator, -getPxVal(R.dimen.opa_diamond_translation), OpaLayout.DIAMOND_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mTop, OpaLayout.DIAMOND_DOTS_SCALE_FACTOR, OpaLayout.DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorY(mTop, OpaLayout.DIAMOND_DOTS_SCALE_FACTOR, OpaLayout.DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getDeltaAnimatorY(mBottom, mDiamondInterpolator, getPxVal(R.dimen.opa_diamond_translation), OpaLayout.DIAMOND_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mBottom, OpaLayout.DIAMOND_DOTS_SCALE_FACTOR, OpaLayout.DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorY(mBottom, OpaLayout.DIAMOND_DOTS_SCALE_FACTOR, OpaLayout.DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getDeltaAnimatorX(mLeft, mDiamondInterpolator, -getPxVal(R.dimen.opa_diamond_translation), OpaLayout.DIAMOND_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mLeft, OpaLayout.DIAMOND_DOTS_SCALE_FACTOR, OpaLayout.DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorY(mLeft, OpaLayout.DIAMOND_DOTS_SCALE_FACTOR, OpaLayout.DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getDeltaAnimatorX(mRight, mDiamondInterpolator, getPxVal(R.dimen.opa_diamond_translation), OpaLayout.DIAMOND_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mRight, OpaLayout.DIAMOND_DOTS_SCALE_FACTOR, OpaLayout.DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorY(mRight, OpaLayout.DIAMOND_DOTS_SCALE_FACTOR, OpaLayout.DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorX(mWhite, OpaLayout.DIAMOND_HOME_SCALE_FACTOR, OpaLayout.DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        set.add(getScaleAnimatorY(mWhite, OpaLayout.DIAMOND_HOME_SCALE_FACTOR, OpaLayout.DIAMOND_ANIMATION_DURATION, mFastOutSlowInInterpolator));
        getLongestAnim(set).addListener((Animator.AnimatorListener)new AnimatorListenerAdapter() {
            public void onAnimationCancel(final Animator animator) {
                OpaLayout.this.mCurrentAnimators.clear();
            }

            public void onAnimationEnd(final Animator animator) {
                OpaLayout.this.startLineAnimation();
            }
        });
        return set;
    }

    private ArraySet<Animator> getLineAnimatorSet() {
        final ArraySet<Animator> set = new ArraySet<Animator>();
        if (mVertical) {
            set.add(getDeltaAnimatorY(mRed, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_x_trans_ry), OpaLayout.LINE_ANIMATION_DURATION_Y));
            set.add(getDeltaAnimatorX(mRed, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_y_translation), OpaLayout.LINE_ANIMATION_DURATION_X));
            set.add(getDeltaAnimatorY(mBlue, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_x_trans_bg), OpaLayout.LINE_ANIMATION_DURATION_Y));
            set.add(getDeltaAnimatorY(mYellow, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_x_trans_ry), OpaLayout.LINE_ANIMATION_DURATION_Y));
            set.add(getDeltaAnimatorX(mYellow, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_y_translation), OpaLayout.LINE_ANIMATION_DURATION_X));
            set.add(getDeltaAnimatorY(mGreen, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_x_trans_bg), OpaLayout.LINE_ANIMATION_DURATION_Y));
        } else {
            set.add(getDeltaAnimatorX(mRed, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_x_trans_ry), OpaLayout.LINE_ANIMATION_DURATION_Y));
            set.add(getDeltaAnimatorY(mRed, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_y_translation), OpaLayout.LINE_ANIMATION_DURATION_X));
            set.add(getDeltaAnimatorX(mBlue, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_x_trans_bg), OpaLayout.LINE_ANIMATION_DURATION_Y));
            set.add(getDeltaAnimatorX(mYellow, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_x_trans_ry), OpaLayout.LINE_ANIMATION_DURATION_Y));
            set.add(getDeltaAnimatorY(mYellow, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_y_translation), OpaLayout.LINE_ANIMATION_DURATION_X));
            set.add(getDeltaAnimatorX(mGreen, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_x_trans_bg), OpaLayout.LINE_ANIMATION_DURATION_Y));
        }
        set.add(getScaleAnimatorX(mWhite, 0.0f, OpaLayout.HOME_RESIZE_DURATION, mHomeDisappearInterpolator));
        set.add(getScaleAnimatorY(mWhite, 0.0f, OpaLayout.HOME_RESIZE_DURATION, mHomeDisappearInterpolator));
        getLongestAnim(set).addListener((Animator.AnimatorListener)new AnimatorListenerAdapter() {
            public void onAnimationCancel(final Animator animator) {
                OpaLayout.this.mCurrentAnimators.clear();
            }

            public void onAnimationEnd(final Animator animator) {
                OpaLayout.this.startCollapseAnimation();
            }
        });
        return set;
    }

    private ArraySet<Animator> getRetractAnimatorSet() {
        final ArraySet<Animator> set = new ArraySet<Animator>();
        set.add(getTranslationAnimatorX(mRed, mRetractInterpolator, OpaLayout.RETRACT_ANIMATION_DURATION));
        set.add(getTranslationAnimatorY(mRed, mRetractInterpolator, OpaLayout.RETRACT_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mRed, 1.0f, OpaLayout.RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getScaleAnimatorY(mRed, 1.0f, OpaLayout.RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getTranslationAnimatorX(mBlue, mRetractInterpolator, OpaLayout.RETRACT_ANIMATION_DURATION));
        set.add(getTranslationAnimatorY(mBlue, mRetractInterpolator, OpaLayout.RETRACT_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mBlue, 1.0f, OpaLayout.RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getScaleAnimatorY(mBlue, 1.0f, OpaLayout.RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getTranslationAnimatorX(mGreen, mRetractInterpolator, OpaLayout.RETRACT_ANIMATION_DURATION));
        set.add(getTranslationAnimatorY(mGreen, mRetractInterpolator, OpaLayout.RETRACT_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mGreen, 1.0f, OpaLayout.RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getScaleAnimatorY(mGreen, 1.0f, OpaLayout.RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getTranslationAnimatorX(mYellow, mRetractInterpolator, OpaLayout.RETRACT_ANIMATION_DURATION));
        set.add(getTranslationAnimatorY(mYellow, mRetractInterpolator, OpaLayout.RETRACT_ANIMATION_DURATION));
        set.add(getScaleAnimatorX(mYellow, 1.0f, OpaLayout.RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getScaleAnimatorY(mYellow, 1.0f, OpaLayout.RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getScaleAnimatorX(mWhite, 1.0f, OpaLayout.RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        set.add(getScaleAnimatorY(mWhite, 1.0f, OpaLayout.RETRACT_ANIMATION_DURATION, mRetractInterpolator));
        getLongestAnim(set).addListener((Animator.AnimatorListener)new AnimatorListenerAdapter() {
            public void onAnimationEnd(final Animator animator) {
                OpaLayout.this.mCurrentAnimators.clear();
                OpaLayout.this.mAnimationState = OpaLayout.ANIMATION_STATE_NONE;
            }
        });
        return set;
    }

    private float getPxVal(int id) {
        return getResources().getDimensionPixelOffset(id);
    }

    private Animator getDeltaAnimatorX(View v, Interpolator interpolator, float deltaX, int duration) {
        RenderNodeAnimator anim = new RenderNodeAnimator(8, (int) (v.getX() + deltaX));
        anim.setTarget(v);
        anim.setInterpolator(interpolator);
        anim.setDuration(duration);
        return anim;
    }

    private Animator getDeltaAnimatorY(View v, Interpolator interpolator, float deltaY, int duration) {
        RenderNodeAnimator anim = new RenderNodeAnimator(9, (int) (v.getY() + deltaY));
        anim.setTarget(v);
        anim.setInterpolator(interpolator);
        anim.setDuration(duration);
        return anim;
    }

    private Animator getScaleAnimatorX(View v, float factor, int duration, Interpolator interpolator) {
        RenderNodeAnimator anim = new RenderNodeAnimator(3, factor);
        anim.setTarget(v);
        anim.setInterpolator(interpolator);
        anim.setDuration(duration);
        return anim;
    }

    private Animator getScaleAnimatorY(View v, float factor, int duration, Interpolator interpolator) {
        RenderNodeAnimator anim = new RenderNodeAnimator(4, factor);
        anim.setTarget(v);
        anim.setInterpolator(interpolator);
        anim.setDuration(duration);
        return anim;
    }

    private Animator getTranslationAnimatorX(View v, Interpolator interpolator, int duration) {
        RenderNodeAnimator anim = new RenderNodeAnimator(0, 0);
        anim.setTarget(v);
        anim.setInterpolator(interpolator);
        anim.setDuration(duration);
        return anim;
    }

    private Animator getTranslationAnimatorY(View v, Interpolator interpolator, int duration) {
        RenderNodeAnimator anim = new RenderNodeAnimator(1, 0);
        anim.setTarget(v);
        anim.setInterpolator(interpolator);
        anim.setDuration(duration);
        return anim;
    }

    private Animator getLongestAnim(ArraySet<Animator> animators) {
        long longestDuration = -1;
        Animator longestAnim = null;

        for(int i=0; i < animators.size(); i++) {
            Animator a = (Animator) animators.valueAt(i);
            if(a.getTotalDuration() > longestDuration) {
                longestDuration = a.getTotalDuration();
                longestAnim = a;
            }
        }
        return longestAnim;
    }

    public void abortCurrentGesture() {
        mHome.abortCurrentGesture();
    }

    protected void onFinishInflate() {
        super.onFinishInflate();

        mRed = findViewById(R.id.red);
        mBlue = findViewById(R.id.blue);
        mYellow = findViewById(R.id.yellow);
        mGreen = findViewById(R.id.green);
        mWhite = (ImageView) findViewById(R.id.white);
        mHome = (KeyButtonView) findViewById(R.id.home_button);

        setOpaEnabled(true);

        hideAllOpa();
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!mOpaEnabled) {
            return false;
        }
        switch (ev.getAction()) {
            case 0: {
                if (!mCurrentAnimators.isEmpty()) {
                    if (mAnimationState != OpaLayout.ANIMATION_STATE_RETRACT) {
                        return false;
                    }
                    endCurrentAnimation();
                }
                mStartTime = SystemClock.elapsedRealtime();
                mLongClicked = false;
                mIsPressed = true;
                startDiamondAnimation();
                removeCallbacks(mCheckLongPress);
                postDelayed(mCheckLongPress, (long)ViewConfiguration.getLongPressTimeout());
                return false;
            }
            case 1:
            case 3: {
                if (mAnimationState == OpaLayout.ANIMATION_STATE_DIAMOND) {
                    final long elapsedRealtime = SystemClock.elapsedRealtime();
                    final long mStartTime = this.mStartTime;
                    removeCallbacks(mRetract);
                    postDelayed(mRetract, 100L - (elapsedRealtime - mStartTime));
                    removeCallbacks(mCheckLongPress);
                    return false;
                }
                int n;
                if (!mIsPressed || mLongClicked) {
                    n = 0;
                } else {
                    n = 1;
                }
                mIsPressed = false;
                if (n != 0) {
                    mRetract.run();
                    return false;
                }
                break;
            }
        }
        return false;
    }

    public void setCarMode(boolean carMode) {
        setOpaEnabled(!carMode);
    }

    public void setImageDrawable(Drawable drawable) {
        ((ImageView) mWhite).setImageDrawable(drawable);
    }

    public void setImageResource(int resId) {
        ((ImageView) mWhite).setImageResource(resId);
    }

    public void setVertical(boolean vertical) {
        mVertical = vertical;

        boolean quickStepEnabled = shouldShowSwipeUpUI();
        mWhite.setRotation(quickStepEnabled && vertical ? 270 : 0);

        if (mVertical) {
            mTop = mGreen;
            mBottom = mBlue;
            mRight = mYellow;
            mLeft = mRed;
            return;
        }
        mTop = mRed;
        mBottom = mYellow;
        mLeft = mBlue;
        mRight = mGreen;
    }

    public void setOnLongClickListener(View.OnLongClickListener l) {
        mHome.setOnLongClickListener(l);
    }

    public void setOnTouchListener(View.OnTouchListener l) {
        mHome.setOnTouchListener(l);
    }

    private boolean shouldShowSwipeUpUI(){
        if (mOverviewProxyService == null){
            mOverviewProxyService = Dependency.get(OverviewProxyService.class);
        }
        return mOverviewProxyService.shouldShowSwipeUpUI();
    }

    public void setOpaEnabled(boolean enabled) {
        boolean quickStepEnabled = shouldShowSwipeUpUI();
        final boolean opaToggle = Settings.System.getIntForUser(this.getContext().getContentResolver(),
            Settings.System.PIXEL_NAV_ANIMATION, 1, UserHandle.USER_CURRENT) == 1;
        final boolean b2 = (enabled || UserManager.isDeviceInDemoMode(getContext())) && opaToggle;
        mOpaEnabled = b2;
        if (quickStepEnabled || !b2) {
            hideAllOpa();
        } else {
            showAllOpa();
        }
    }

    private void hideAllOpa(){
        fadeOutButton(mBlue);
        fadeOutButton(mRed);
        fadeOutButton(mYellow);
        fadeOutButton(mGreen);
        updateIconColor();
    }

    private void showAllOpa(){
        fadeInButton(mBlue);
        fadeInButton(mRed);
        fadeInButton(mYellow);
        fadeInButton(mGreen);
        updateIconColor();
    }


    private void fadeInButton(View viewToFade){
        ObjectAnimator animator = ObjectAnimator.ofFloat(viewToFade, View.ALPHA, 0.0f, 1.0f);
        animator.setDuration(OpaLayout.OPA_FADE_IN_DURATION); //ms
        animator.start();
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                viewToFade.setVisibility(View.VISIBLE);
            }
        });
    }

    private void fadeOutButton(View viewToFade){
        ObjectAnimator animator = ObjectAnimator.ofFloat(viewToFade, View.ALPHA, 1.0f, 0.0f);
        animator.setDuration(OpaLayout.OPA_FADE_OUT_DURATION); //ms
        animator.start();
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                viewToFade.setVisibility(View.INVISIBLE);
            }
        });
    }

    public void setDarkIntensity(float intensity) {
        mIconTint = getColorForDarkIntensity(
                intensity, mLightModeFillColor, mDarkModeFillColor);
        updateIconColor();
    }

    private void updateIconColor() {
        int mIconColor = mIconTint;
        updateHomeDrawable(mIconColor);
    }

    private int getColorForDarkIntensity(float intensity, int lightColor, int darkColor) {
        return (int) ArgbEvaluator.getInstance().evaluate(intensity, lightColor, darkColor);
    }

    private boolean isLightColor(int color) {
        if (color == -3) {
            return false;
        } else if (color == Color.TRANSPARENT) {
            return false;
        } else if (color == Color.WHITE) {
            return true;
        }
        int[] rgb = { Color.red(color), Color.green(color), Color.blue(color) };
        int brightness = (int) Math.sqrt(rgb[0] * rgb[0] * .241 + rgb[1]
            * rgb[1] * .691 + rgb[2] * rgb[2] * .068);
        if (brightness >= 170) {
            return true;
        }
        return false;
    }

    private void updateHomeDrawable(int homeColor) {
        Resources res = getContext().getResources();
        boolean quickStepEnabled = shouldShowSwipeUpUI();
        int intHomeDrawable = quickStepEnabled ? R.drawable.ic_sysbar_home_quick_step : R.drawable.ic_sysbar_home;

        Drawable drawHomeIcon = res.getDrawable(intHomeDrawable);
        drawHomeIcon.setColorFilter(null);
        drawHomeIcon.setColorFilter(homeColor, PorterDuff.Mode.SRC_ATOP);

        if (isLightColor(homeColor)) {
            ShadowKeyDrawable withShadow = new ShadowKeyDrawable(drawHomeIcon.mutate());
            int offsetX = res.getDimensionPixelSize(R.dimen.nav_key_button_shadow_offset_x);
            int offsetY = res.getDimensionPixelSize(R.dimen.nav_key_button_shadow_offset_y);
            int radius = res.getDimensionPixelSize(R.dimen.nav_key_button_shadow_radius);
            int color = res.getColor(R.color.nav_key_button_shadow_color);
            withShadow.setShadowProperties(offsetX, offsetY, radius, color);
            drawHomeIcon = withShadow;
        }

        setImageDrawable(drawHomeIcon);
    }

    public void setDelayTouchFeedback(boolean delay) {
        mHome.setDelayTouchFeedback(delay);
    }
}
