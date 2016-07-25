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

package pablogventura.fugit;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.TextView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class FugitWatchFace extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     * se actualiza cada un segundo
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<FugitWatchFace.Engine> mWeakReference;

        public EngineHandler(FugitWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            FugitWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mHourPaint;
        Paint mDatePaint;
        Paint mMeteoPaint;
        Paint mAstroPaint;
        Calendar mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime = Calendar.getInstance();
            }
        };
        int mTapCount;

        float hXOffset;
        float hYOffset;
        float mXOffset;
        float mYOffset;

        SimpleDateFormat fDiaMes;
        SimpleDateFormat fDiaSemana;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(FugitWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = FugitWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.minutes_y_offset);
            mXOffset = resources.getDimension(R.dimen.minutes_x_offset_round);
            hYOffset = resources.getDimension(R.dimen.hours_y_offset);
            hXOffset = resources.getDimension(R.dimen.hours_x_offset_round);


            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mHourPaint = new Paint();
            mHourPaint = createTextPaint(resources.getColor(R.color.digital_text), resources.getDimension(R.dimen.digital_text_size_round));
            mHourPaint.setStyle(Paint.Style.FILL);
            fDiaMes = new SimpleDateFormat("d 'de' MMMM",Locale.getDefault());
            fDiaSemana = new SimpleDateFormat("EEEE",Locale.getDefault());
            mDatePaint = new Paint();
            mDatePaint = createTextPaint(Color.WHITE, resources.getDimension(R.dimen.size_date));
            mDatePaint.setTypeface(Typeface.SANS_SERIF);
            mAstroPaint = new Paint();
            mAstroPaint.setAntiAlias(true);

            mMeteoPaint = createTextPaint(resources.getColor(R.color.digital_text), resources.getDimension(R.dimen.digital_text_size_round));
            Typeface meteoTF =Typeface.createFromAsset(getAssets(),"fonts/weathericons-regular-webfont.ttf");
            mMeteoPaint.setTypeface(meteoTF);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }
        private Paint createTextPaint(int textColor, float textSize) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(Typeface.SERIF);
            paint.setTextSize(textSize);
            paint.setAntiAlias(true);


            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeMiter(10);

            paint.setStrokeWidth(3);



            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.

                mTime = Calendar.getInstance();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            FugitWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            FugitWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
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
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = FugitWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }
        private void dia(Canvas canvas, double porcentaje){
            Shader shader = new LinearGradient(0, 0, 0, 320*5/6, Color.rgb(0,255,255), Color.rgb(0,150,255), Shader.TileMode.CLAMP);
            mBackgroundPaint.setShader(shader);
            porcentaje = (porcentaje * 300) -150;
            canvas.drawRect(0, 0, 320, 320, mBackgroundPaint);

            Bitmap bmpsol = BitmapFactory.decodeResource(getResources(),
                    R.drawable.sol);
            canvas.save();
            canvas.rotate((float) porcentaje , 320/2, 320*6/10);
            canvas.drawBitmap(bmpsol, 320/2-50/2, 0, mAstroPaint);
            canvas.restore();
        }
        private void noche(Canvas canvas, double porcentaje){
            Shader shader = new LinearGradient(0, 0, 0, 320*5/6, Color.rgb(0,0,0), Color.rgb(0,0,50), Shader.TileMode.CLAMP);
            mBackgroundPaint.setShader(shader);
            porcentaje = (porcentaje * 300) -150;
            canvas.drawRect(0, 0, 320, 320, mBackgroundPaint);

            Bitmap bmpluna = BitmapFactory.decodeResource(getResources(),
                    R.drawable.luna);
            canvas.save();
            canvas.rotate((float) porcentaje , 320/2, 320*6/10);
            canvas.drawBitmap(bmpluna, 320/2-50/2, 0, mAstroPaint);
            canvas.restore();
        }
        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            //actualizo la hora
            mTime = Calendar.getInstance();


            noche(canvas, 0.4);

            // Draw the background.

            //genero las cadenas para la fecha
            Date fecha = mTime.getTime();
            String sDiaMes = fDiaMes.format(fecha);
            String sDiaSemana = fDiaSemana.format(fecha);
            String hours = String.format(Locale.getDefault(), "%02d", mTime.HOUR);
            String minutes = String.format(Locale.getDefault(), "%02d", mTime.MINUTE);

            // genero los arcos para escribir alrededor
            Path mArcoSuperior = new Path();
            Path mArcoInferior = new Path();
            //moto 360 320x290px 241x218dp
            RectF oval = new RectF(0,0,320,320);
            mArcoSuperior.addArc(oval, -180, 180);
            mArcoInferior.addArc(oval, 180, -180);
            int largoArco = 456; //2*pi * (320-30)/2 / 2;


            // escribo alrededor la fecha
            mDatePaint.setStyle(Paint.Style.STROKE);
            mDatePaint.setColor(Color.BLACK);
            mHourPaint.setStyle(Paint.Style.STROKE);
            mHourPaint.setColor(Color.BLACK);
            canvas.drawTextOnPath(sDiaMes, mArcoSuperior, largoArco-mDatePaint.measureText(sDiaMes) - 53, 30, mDatePaint);

            //canvas.drawTextOnPath(sDiaMes, mArcoSuperior, largoArco-mDatePaint.measureText(sDiaMes) - 53, 30, mDatePaint);
            canvas.drawTextOnPath(sDiaSemana, mArcoInferior, largoArco-mDatePaint.measureText(sDiaSemana), -13, mDatePaint);

            // escribo la hora y el titileo del ..

            canvas.save();
            canvas.rotate(-17, hXOffset, hYOffset);

            canvas.drawText(hours, hXOffset, hYOffset, mHourPaint);
            canvas.drawText(minutes, mXOffset-15, mYOffset-15, mHourPaint);
            canvas.restore();


            mDatePaint.setStyle(Paint.Style.FILL);
            mDatePaint.setColor(Color.WHITE);
            mHourPaint.setStyle(Paint.Style.FILL);
            mHourPaint.setColor(Color.WHITE);
            canvas.drawTextOnPath(sDiaMes, mArcoSuperior, largoArco-mDatePaint.measureText(sDiaMes) - 53, 30, mDatePaint);

            //canvas.drawTextOnPath(sDiaMes, mArcoSuperior, largoArco-mDatePaint.measureText(sDiaMes) - 53, 30, mDatePaint);
            canvas.drawTextOnPath(sDiaSemana, mArcoInferior, largoArco-mDatePaint.measureText(sDiaSemana), -13, mDatePaint);

            // escribo la hora y el titileo del ..

            canvas.save();
            canvas.rotate(-17, hXOffset, hYOffset);

            canvas.drawText(hours, hXOffset, hYOffset, mHourPaint);
            canvas.drawText(minutes, mXOffset-15, mYOffset-15, mHourPaint);
            canvas.restore();

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
    }
}
