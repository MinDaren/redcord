package com.song.redcord;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.service.wallpaper.WallpaperService;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.graphics.drawable.RoundedBitmapDrawable;
import android.support.v4.graphics.drawable.RoundedBitmapDrawableFactory;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.song.redcord.bean.Her;
import com.song.redcord.bean.Me;
import com.song.redcord.interfaces.RequestCallback;
import com.song.redcord.util.ColorUtil;
import com.song.redcord.util.Pref;
import com.song.redcord.util.ScreenUtil;
import com.song.redcord.util.TAG;

/**
 * 锁屏时间过长,会导致无法继续定位,需要重新拉起定位服务
 * 见高德文档
 * 目前手机设备在长时间黑屏或锁屏时CPU会休眠，
 * 这导致定位SDK不能正常进行位置更新。
 * https://lbs.amap.com/api/android-location-sdk/guide/android-location/getlocation
 */
public class LiveWallpaper extends WallpaperService {
    private static final ColorMatrixColorFilter NIGHT_COLOR_FILTER = new ColorMatrixColorFilter(new ColorMatrix(new float[]{
        1 / 2f, 1 / 2f, 1 / 2f, 0, 0,
        1 / 3f, 1 / 3f, 1 / 3f, 0, 0,
        1 / 4f, 1 / 4f, 1 / 4f, 0, 0,
        0, 0, 0, 1, 0,
    }));

    @Override
    public Engine onCreateEngine() {
        return new RedcordEngine();
    }

    private class RedcordEngine extends Engine implements AMapLocationListener {
        private static final int MAX_DISTANCE = 300;
        private static final int OFFSET = 5;
        private static final long START_LOCATION_INTERVAL = 30000;
        private static final long LOCATION_INTERVAL = 30000;
        private static final long STOP_DELAY_INTERVAL =  2000;
        private int leftX, leftY;
        private int rightX, rightY;
        private int centerX, centerY;
        private int startX, startY;
        private int endX, endY;
        private int mapStartX, mapStartY;
        private int mapEndX, mapEndY;
        private Paint paint;
        private Paint tipsTextPaint;

        private boolean isVisible;
        private boolean isTouch;
        private boolean isLeft;
        private Me me = new Me(Pref.get().getId());
        private Handler locationHandler = new Handler();
        private AMapLocationClient locationClient;
        private AMapLocationClientOption locationOption;
        private AMapLocation aMapLocation;
        private long lastLocationTime;

        private final Handler handler = new Handler();
        private final Runnable updateDisplay = new Runnable() {
            @Override
            public void run() {

                if (leftX < centerX - MAX_DISTANCE) {
                    isLeft = false;
                } else if (leftX > centerX + MAX_DISTANCE) {
                    isLeft = true;
                }
                if (isLeft) {
                    leftX -= OFFSET;
                } else {
                    leftX += OFFSET;
                }
                rightX = 2 * centerX - leftX;
                doDraw();
            }
        };
        private RoundedBitmapDrawable bitmapDrawable;
        private final SimpleTarget<Bitmap> mapBitmapTarget = new SimpleTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                bitmapDrawable = RoundedBitmapDrawableFactory.create(LiveWallpaper.this.getResources(), resource);
            }
        };

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            // 圈和线
            paint = new Paint();
            paint.setAntiAlias(true);
            // 文字
            tipsTextPaint = new Paint();
            tipsTextPaint.setAntiAlias(true);
            tipsTextPaint.setColor(Color.WHITE);
            tipsTextPaint.setTextSize(24);
            tipsTextPaint.setTextAlign(Paint.Align.CENTER);
            tipsTextPaint.setAntiAlias(true);
            tipsTextPaint.setDither(true);
            // 视图
            centerX = ScreenUtil.getWidth(App.get()) / 2;
            centerY = ScreenUtil.getHeight(App.get()) / 2;
            startX = centerX;
            startY = -10;
            endX = centerX;
            endY = centerY - 250;
            leftX = startX - 250;
            leftY = centerY / 2 - 120;
            rightX = startX + 250;
            rightY = leftY;
            // 地图
            mapStartX = centerX - 350;
            mapStartY = centerY - 350;
            mapEndX = centerX + 350;
            mapEndY = centerY + 350;

            me.pull(new RequestCallback() {
                @Override
                public void onSuccess() {
                    final Her her = new Her(me.getLoverId());
                    me.setLover(her);
                    her.pull(new RequestCallback() {
                        @Override
                        public void onSuccess() {
                            if (aMapLocation != null) {
                                me.setLocation(aMapLocation);
                                me.push();
                            }
                            her.adjustDownloadMapBitmap(LiveWallpaper.this, mapBitmapTarget);
                        }

                        @Override
                        public void onFail() {

                        }
                    });
                }

                @Override
                public void onFail() {

                }
            });
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            isVisible = visible;
            if (visible) {
                startLocation();
                doDraw();
            } else {
                stopLocationDelay();
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            stopLocation();
        }

        private void doDraw() {
            SurfaceHolder surfaceHolder = getSurfaceHolder();
            if (surfaceHolder == null) {
                return;
            }

            Canvas canvas = null;
            try {
                canvas = surfaceHolder.lockCanvas();
                if (canvas == null) {
                    return;
                }

                int curMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                boolean night = curMode == Configuration.UI_MODE_NIGHT_YES;
                int bgColor;
                if (night) {
                    bgColor = ColorUtil.getNightColor();
                } else {
                    float f = (leftX - centerX + MAX_DISTANCE) / (float) (2 * MAX_DISTANCE);
                    f = Math.min(1 , Math.max(f, 0));
                    bgColor = ColorUtil.getColor(f);
                }

                // 画背景
                canvas.drawColor(bgColor);

                // 画地图
                if (bitmapDrawable != null) {
                    if (night) {
                        bitmapDrawable.setColorFilter(NIGHT_COLOR_FILTER);
                    } else {
                        bitmapDrawable.clearColorFilter();
                    }
                    bitmapDrawable.setCornerRadius(20);
                    bitmapDrawable.setBounds(new Rect(mapStartX, mapStartY, mapEndX, mapEndY));
                    bitmapDrawable.draw(canvas);
                }

                // 画距离
                Her her = me.getLover();
                if (her != null && !TextUtils.isEmpty(her.getLineDistance())) {
                    canvas.drawText("相距 : " + her.getLineDistance() + "  |  " + her.getUpdateTime(), centerX, mapEndY + 120, tipsTextPaint);
                    canvas.drawText("位置 : " + her.getAddress(), centerX, mapEndY + 170, tipsTextPaint);
                }

                // 画二阶贝塞尔曲线
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(10);
                paint.setColor(getColor(R.color.colorAccent));
                Path path = new Path();
                path.moveTo(startX, startY);
                path.cubicTo(leftX, leftY, rightX, rightY, endX, endY);
                canvas.drawPath(path, paint);

                // 画点
                paint.setStyle(Paint.Style.FILL);
                paint.setStrokeWidth(0);
                paint.setColor(bgColor);
                canvas.drawCircle(endX, endY, 16, paint);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(endX, endY, 7, paint);

            } catch (Exception | OutOfMemoryError e) {
                e.printStackTrace();
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            handler.removeCallbacks(updateDisplay);
            if (isVisible && !isTouch) {
                // Wait one frame, and redraw
                handler.postDelayed(updateDisplay, 33);
            }
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    isTouch = true;
                    leftX = (int) event.getX();
                    rightX = 2 * centerX - leftX;
                    doDraw();
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    isTouch = false;
                    doDraw();
                    break;
            }

        }

        @Override
        public void onLocationChanged(final AMapLocation location) {
            Log.i(TAG.V, " ~~~~~~~~~~ 壁纸定位完成 ~~~~~~~~~~~ " + location);

            // 位置改变
            if (location != null && location.getErrorCode() == 0) {
                this.aMapLocation = location;
                me.setLocation(location);
                me.push();
            }

            final Her her = me.getLover();
            if (her == null) {
                return;
            }

            her.pull(new RequestCallback() {
                @Override
                public void onSuccess() {
                    her.adjustDownloadMapBitmap(LiveWallpaper.this, mapBitmapTarget);
                }

                @Override
                public void onFail() {

                }
            });
        }

        private void startLocation() {
            if (System.currentTimeMillis() - lastLocationTime < START_LOCATION_INTERVAL) {
                return;
            }
            lastLocationTime = System.currentTimeMillis();

            stopLocation();
            locationHandler.removeCallbacksAndMessages(null);
            locationClient = new AMapLocationClient(LiveWallpaper.this);
            locationOption = new AMapLocationClientOption();
            locationOption.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            locationOption.setInterval(LOCATION_INTERVAL);
            locationClient.setLocationListener(this);
            locationClient.setLocationOption(locationOption);
            locationClient.startLocation();
        }

        private void stopLocation() {
            if (locationClient != null) {
                locationClient.stopLocation();
                locationClient.onDestroy();
                locationClient = null;
            }
        }

        private void stopLocationDelay() {
            locationHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    stopLocation();
                }
            }, STOP_DELAY_INTERVAL);
        }
    }

}
