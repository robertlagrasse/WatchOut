/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WatchyMcWatchFace extends CanvasWatchFaceService{
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     *
     * Change this to alter how often the watch updates the face
     *
     */

    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.MILLISECONDS.toMillis(1000);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }


    private static class EngineHandler extends Handler {
        private final WeakReference<WatchyMcWatchFace.Engine> mWeakReference;

        public EngineHandler(WatchyMcWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WatchyMcWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        Calendar mTime = Calendar.getInstance();

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        Calendar mCalendar;
        String DEGREE  = "\u00b0";

        long mMinTemp = 0, mMaxTemp = 0, mCondition = 0, mDate =0;

        Bitmap mBackgroundBitmap;
        Bitmap mBackgroundScaledBitmap;

        private GoogleApiClient googleApiClient;

        private int specW, specH;
        private View myLayout;
        private TextView min, date, max, hour, minute;
        private View midLine, buffer;
        private LinearLayout bottom;
        private final Point displaySize = new Point();
        private ImageView weatherIcon;

        /**
         *
         * Make an adjustment if we change timezones.
         *
         */

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (mBackgroundScaledBitmap == null
                    || mBackgroundScaledBitmap.getWidth() != width
                    || mBackgroundScaledBitmap.getHeight() != height) {
                mBackgroundScaledBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                        width, height, true /* filter */);
            }
            super.onSurfaceChanged(holder, format, width, height);
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            /**
             * Where am I? Where's my stuff? What day is it?
             * */

            Resources resources = WatchyMcWatchFace.this.getResources();
            mCalendar = Calendar.getInstance();

            /**
             * The layout for my watch face is defined in XML. The file is called watch_this
             * */

            LayoutInflater inflater =
                    (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            myLayout = inflater.inflate(R.layout.watch_this, null);

            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            display.getSize(displaySize);

            specW = View.MeasureSpec.makeMeasureSpec(displaySize.x,
                    View.MeasureSpec.EXACTLY);
            specH = View.MeasureSpec.makeMeasureSpec(displaySize.y,
                    View.MeasureSpec.EXACTLY);

            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            /**
             * Get a reference to all of the UI elements we'll play with
             * */

            hour = (TextView) myLayout.findViewById(R.id.tvHour);
            minute = (TextView) myLayout.findViewById(R.id.tvMinute);
            date = (TextView) myLayout.findViewById(R.id.tvDate);
            max = (TextView) myLayout.findViewById(R.id.tvMax);
            min = (TextView) myLayout.findViewById(R.id.tvMin);
            weatherIcon = (ImageView) myLayout.findViewById(R.id.ivWeather);

            midLine = myLayout.findViewById(R.id.midLine);
            bottom = (LinearLayout) myLayout.findViewById(R.id.bottomContainer);
            buffer = myLayout.findViewById(R.id.buffer);

            /**
             * Arts and crafts
             * */

            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchyMcWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(false)
                    .build());

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            Drawable backgroundDrawable = resources.getDrawable(R.drawable.ic_clear, null);
            mBackgroundBitmap = ((BitmapDrawable) backgroundDrawable).getBitmap();


            /**
             * Set the googleApiClient so we can receive data from the app on the mobile
             * */

            googleApiClient = new GoogleApiClient.Builder(WatchyMcWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            releaseGoogleApiClient();
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                googleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {

                releaseGoogleApiClient();
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }
        private void releaseGoogleApiClient() {
            if (googleApiClient != null && googleApiClient.isConnected()) {
                Wearable.DataApi.removeListener(googleApiClient, onDataChangedListener);
                googleApiClient.disconnect();
            }
        }


        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WatchyMcWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WatchyMcWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WatchyMcWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            hour.setText(new SimpleDateFormat("HH").format(mTime.getTime()));
            minute.setText(new SimpleDateFormat("mm").format(mTime.getTime()));
            date.setText(new SimpleDateFormat("E  d MMM y").format(mTime.getTime()));
            min.setText(String.valueOf(mMinTemp) + DEGREE);
            max.setText(String.valueOf(mMaxTemp) + DEGREE);

            /**
             * Turn off the views we don't want to see in ambient mode.
             * I arranged the layout so the time would center when the
             * remining views are GONE.
             * */

            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
                midLine.setVisibility(GONE);
                bottom.setVisibility(GONE);
                date.setVisibility(GONE);
                buffer.setVisibility(GONE);
            } else {
                canvas.drawColor(getResources().getColor(R.color.colorPrimary));
                midLine.setVisibility(VISIBLE);
                bottom.setVisibility(VISIBLE);
                date.setVisibility(VISIBLE);
                buffer.setVisibility(VISIBLE);
            }

            myLayout.measure(specW, specH);
            myLayout.layout(0, 0, myLayout.getMeasuredWidth(), myLayout.getMeasuredHeight());
            myLayout.draw(canvas);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(googleApiClient, onDataChangedListener);
            Wearable.DataApi.getDataItems(googleApiClient).setResultCallback(onConnectedResultCallback);
        }

        @Override
        public void onConnectionSuspended(int i) {
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        }

        private final DataApi.DataListener onDataChangedListener = new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEvents) {
                for (DataEvent event : dataEvents) {
                    if (event.getType() == DataEvent.TYPE_CHANGED) {
                        DataItem item = event.getDataItem();
                        processConfigurationFor(item);
                    }
                }

                dataEvents.release();
                invalidate();
            }
        };

        private void processConfigurationFor(DataItem item) {
            if ("/weather".equals(item.getUri().getPath())) {
                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                if (dataMap.containsKey("max")) {
                    mMaxTemp = dataMap.getLong("max");
                }
                if (dataMap.containsKey("min")) {
                    mMinTemp = dataMap.getLong("min");
                }
                if (dataMap.containsKey("con")) {
                    mCondition = dataMap.getLong("con");
                    if (mCondition >= 200 && mCondition <= 232) {
                        weatherIcon.setImageResource(R.drawable.ic_storm);
                    } else if (mCondition >= 300 && mCondition <= 321) {
                        weatherIcon.setImageResource(R.drawable.ic_light_rain);
                    } else if (mCondition >= 500 && mCondition <= 504) {
                        weatherIcon.setImageResource(R.drawable.ic_rain);
                    } else if (mCondition == 511) {
                        weatherIcon.setImageResource(R.drawable.ic_snow);
                    } else if (mCondition >= 520 && mCondition <= 531) {
                        weatherIcon.setImageResource(R.drawable.ic_rain);
                    } else if (mCondition >= 600 && mCondition <= 622) {
                        weatherIcon.setImageResource(R.drawable.ic_snow);
                    } else if (mCondition >= 701 && mCondition <= 761) {
                        weatherIcon.setImageResource(R.drawable.ic_fog);
                    } else if (mCondition == 761 || mCondition == 781) {
                        weatherIcon.setImageResource(R.drawable.ic_storm);
                    } else if (mCondition == 800) {
                        weatherIcon.setImageResource(R.drawable.ic_clear);
                    } else if (mCondition == 801) {
                        weatherIcon.setImageResource(R.drawable.ic_light_clouds);
                    } else if (mCondition >= 802 && mCondition <= 804) {
                        weatherIcon.setImageResource(R.drawable.ic_cloudy);
                    }
                }
                if (dataMap.containsKey("date")) {
                    mDate = dataMap.getLong("date");
                }
            }
        }

        private final ResultCallback<DataItemBuffer> onConnectedResultCallback = new ResultCallback<DataItemBuffer>() {
            @Override
            public void onResult(DataItemBuffer dataItems) {
                for (DataItem item : dataItems) {
                    processConfigurationFor(item);
                }
                dataItems.release();
                invalidate();
            }
        };
    }
}
