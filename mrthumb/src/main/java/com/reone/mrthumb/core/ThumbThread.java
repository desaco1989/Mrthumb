package com.reone.mrthumb.core;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.reone.mrthumb.Mrthumb;
import com.reone.mrthumb.cache.DispersionManager;
import com.reone.mrthumb.cache.ThumbCache;
import com.reone.mrthumb.listener.ProcessListener;
import com.reone.mrthumb.retriever.MediaMetadataRetrieverCompat;
import com.reone.mrthumb.type.RetrieverType;
import com.reone.tbufferlib.BuildConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by wangxingsheng on 2018/5/19.
 */
public class ThumbThread {
    private MediaMetadataRetrieverCompat mmr;
    private int maxSize;
    private int cacheCount;
    private int thumbnailWidth;
    private int thumbnailHeight;
    private long duration;
    private String mUrl;
    private Map<String, String> mHeaders;
    private DispersionManager dispersionThumbCache;
    private ProcessListener processListener;

    public ThumbThread(int maxSize) {
        this.maxSize = maxSize;
        initBufferArray();
    }

    public void setMediaMedataRetriever(@RetrieverType int retrieverType, long duration) {
        this.mmr = new MediaMetadataRetrieverCompat(retrieverType);
        this.duration = duration;
        log("ThumbnailBuffer mmr = " + mmr + " duration = " + duration);
    }

    public void execute(final String url, final Map<String, String> headers, int thumbnailWidth, int thumbnailHeight) throws IllegalAccessException {
        log("ThumbnailBuffer url = " + url);
        log("ThumbnailBuffer headers = " + headers);
        this.thumbnailWidth = thumbnailWidth;
        this.thumbnailHeight = thumbnailHeight;
        if (url == null || mmr == null) {
            throw new IllegalAccessException("url or mmr is null");
        }
        if (url.equals(mUrl)) return;
        release();
        initBufferArray();
        mUrl = url;
        mHeaders = headers;
        if (!initThread.isInterrupted()) {
            initThread.interrupt();
        }
        cacheCount = 0;
        initThread.start();
    }

    /**
     * 初始化buffer数组
     */
    private void initBufferArray() {
        ThumbCache.getInstance().setCacheMax(maxSize);
        initThumbnailDispersions(maxSize);
    }

    private Thread initThread = new Thread() {
        @Override
        public void run() {
            log("ThumbnailBuffer start buffer " + mUrl + " headers " + mHeaders);
            long startBufferTime = SystemClock.elapsedRealtime();
            try {
                if (mHeaders == null) {
                    mmr.setDataSource(mUrl, new HashMap<String, String>());
                } else {
                    mmr.setDataSource(mUrl, mHeaders);
                }
                mmr.extractMetadata(MediaMetadataRetrieverCompat.METADATA_KEY_DURATION);
                if (Mrthumb.obtain().isEnable()) {
                    if (Mrthumb.obtain().isDispersionBuffer()) {
                        dispersionBuffer();
                    } else {
                        orderBuffer();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            log("ThumbnailBuffer end buffer at " + (SystemClock.elapsedRealtime() - startBufferTime) + "/n" + mUrl);
        }
    };

    private void log(String log) {
        if (BuildConfig.DEBUG) {
            Log.d(ThumbThread.class.getSimpleName(), log);
        }
    }

    /**
     * 通过百分比获取缩略图
     *
     * @param percentage 选择时间点占总时长的百分比
     * @return 缩略图
     */
    public Bitmap getThumbnail(float percentage) {
        Bitmap bitmap;
        if (Mrthumb.obtain().isDispersionBuffer()) {
            bitmap = getDispersionThumbnail(percentage);
        } else {
            bitmap = getOrderBitmap(percentage);
        }
        logBitmapSize(bitmap);
        return bitmap;
    }

    /**
     * 顺序填充缩略图
     */
    private void orderBuffer() {
        for (int i = 0; i < maxSize; i++) {
            if (ThumbCache.getInstance().hasThumbnail(i)) return;
            try {
                long time = i * duration / maxSize;
                Bitmap thumbnail = mmr.getScaledFrameAtTime(time * 1000, MediaMetadataRetrieverCompat.OPTION_CLOSEST,
                        thumbnailWidth, thumbnailHeight);
                ThumbCache.getInstance().set(i, thumbnail);
                log("ThumbnailBuffer order buffer i = " + i);
                if (processListener != null) {
                    processListener.onProcess(i, ++cacheCount, maxSize, time, duration);
                }
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * 顺序方式获取
     *
     * @param percentage 选择时间点占总时长的百分比
     * @return 缩略图
     */
    private Bitmap getOrderBitmap(float percentage) {
        int index = (int) ((maxSize - 1) * percentage);
        log("ThumbnailBuffer percentage = " + percentage + " index = " + index);
        return ThumbCache.getInstance().get(index);
    }

    /**
     * 分散填充缩略图
     */
    private void dispersionBuffer() {
        if (dispersionThumbCache != null) {
            dispersionThumbCache.start();
        }
    }

    /**
     * 分散获取缩略图
     */
    private Bitmap getDispersionThumbnail(float percentage) {
        if (dispersionThumbCache == null) return null;
        int index = (int) ((maxSize - 1) * percentage);
        return dispersionThumbCache.get(index);
    }

    /**
     * 初始化分散式缩略图数组
     */
    private void initThumbnailDispersions(final int maxSize) {
        dispersionThumbCache = new DispersionManager(maxSize) {
            @Override
            public Bitmap getIndex(int index) {
                Bitmap bitmap = null;
                try {
                    long time = index * duration / maxSize;
                    log("ThumbnailBuffer dispersions record buffer i = " + index + " at time:" + time);
                    bitmap = mmr.getScaledFrameAtTime(time * 1000, MediaMetadataRetrieverCompat.OPTION_CLOSEST,
                            thumbnailWidth, thumbnailHeight);
                    if (processListener != null) {
                        processListener.onProcess(index, ++cacheCount, maxSize, time, duration);
                    }
                } catch (Exception ignore) {
                }
                return bitmap;
            }
        };
    }

    public void release() {
        if (initThread != null) {
            initThread.interrupt();
        }
        ThumbCache.getInstance().release();
        if (dispersionThumbCache != null) {
            dispersionThumbCache.release();
            dispersionThumbCache = null;
        }
        mUrl = null;
        mHeaders = null;
    }

    public void setProcessListener(ProcessListener processListener) {
        this.processListener = processListener;
    }

    private void logBitmapSize(Bitmap bitmap) {
        if (bitmap == null) return;
        log("ThumbnailBuffer bitmap size " + bitmap.getByteCount());
    }
}
