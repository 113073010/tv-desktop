package com.dsh.tvdesk.stock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.List;

/** 分时走势图：以昨收为基线，涨红跌绿，带半透明填充 */
public class MinuteChart extends View {

    private static final int UP = 0xFFFF4D4F;
    private static final int DOWN = 0xFF3FBF6F;
    private static final int GRID = 0xFF2A3242;
    private static final int BASE = 0xFF4A5468;

    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGrid = new Paint();
    private final Paint pBase = new Paint();
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<Float> mPrices;
    private List<String> mLabels;
    private float mPrevClose;
    private boolean mStretch = false;   // true=按点数铺满宽度（历史曲线用）
    private String mEmpty = "暂无分时数据";
    private float mDensity = 2f;

    public MinuteChart(Context c) {
        super(c);
        mDensity = c.getResources().getDisplayMetrics().density;
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(2.5f * mDensity);
        pLine.setStrokeJoin(Paint.Join.ROUND);
        pFill.setStyle(Paint.Style.FILL);
        pGrid.setColor(GRID);
        pGrid.setStrokeWidth(1f);
        pBase.setColor(BASE);
        pBase.setStrokeWidth(1f);
        pText.setColor(0xFF7C8494);
        pText.setTextSize(11f * mDensity);
    }

    public void setData(List<Float> prices, float prevClose) {
        mPrices = prices;
        mPrevClose = prevClose;
        mStretch = false;
        mEmpty = "暂无分时数据";
        invalidate();
    }

    /** 历史曲线：铺满整宽，基线取首个点 */
    public void setHistory(List<Float> prices) {
        setHistory(prices, null);
    }

    /** 历史曲线：带日期标签 */
    public void setHistory(List<Float> prices, List<String> labels) {
        mPrices = prices;
        mLabels = labels;
        mPrevClose = (prices != null && !prices.isEmpty()) ? prices.get(0) : 0f;
        mStretch = true;
        mEmpty = "暂无走势数据";
        invalidate();
    }

    /** 清空并显示占位文案（换标的等数据时用，避免残留上一条曲线） */
    public void clear(String emptyText) {
        mPrices = null;
        mLabels = null;
        mPrevClose = 0f;
        mStretch = true;
        mEmpty = emptyText == null ? "暂无走势数据" : emptyText;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        for (int i = 1; i <= 3; i++) {
            float y = h * i / 4f;
            cv.drawLine(0, y, w, y, pGrid);
        }

        if (mPrices == null || mPrices.size() < 2 || mPrevClose <= 0f) {
            pText.setColor(0xFF7C8494);
            float tw = pText.measureText(mEmpty);
            cv.drawText(mEmpty, (w - tw) / 2f, h / 2f, pText);
            return;
        }

        float maxDev = 0.001f;
        for (Float f : mPrices) {
            float dev = Math.abs(f - mPrevClose);
            if (dev > maxDev) maxDev = dev;
        }
        float top = mPrevClose + maxDev, bottom = mPrevClose - maxDev;
        float pad = 8 * mDensity;
        float usableH = h - pad * 2;
        float baseY = pad + (top - mPrevClose) / (top - bottom) * usableH;

        int total = mStretch ? mPrices.size() : Math.max(240, mPrices.size());
        float stepX = w / (float) Math.max(1, total - 1);

        if (mStretch) {
            // 日K模式：整体单色+填充，底部留日期区
            float dateH = 15 * mDensity;
            float chartH = usableH - dateH;
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (Float f : mPrices) { if (f < lo) lo = f; if (f > hi) hi = f; }
            if (hi - lo < 0.01f) { hi += 0.01f; lo -= 0.01f; }
            float range = hi - lo;
            float baseY0 = pad + chartH;

            Path line = new Path();
            Path fill = new Path();
            boolean started = false;
            float lastX = 0;
            for (int i = 0; i < mPrices.size(); i++) {
                float price = mPrices.get(i);
                float x = i * stepX;
                float y = pad + (hi - price) / range * chartH;
                if (!started) {
                    line.moveTo(x, y);
                    fill.moveTo(x, baseY0);
                    fill.lineTo(x, y);
                    started = true;
                } else {
                    line.lineTo(x, y);
                    fill.lineTo(x, y);
                }
                lastX = x;
            }

            boolean up = mPrices.get(mPrices.size() - 1) >= mPrices.get(0);
            int color = up ? UP : DOWN;

            fill.lineTo(lastX, baseY0);
            fill.close();
            pFill.setColor((color & 0x00FFFFFF) | 0x33000000);
            cv.drawPath(fill, pFill);

            pLine.setStrokeWidth(2.5f);
            pLine.setColor(color);
            cv.drawPath(line, pLine);

            // 日期标签：5个刻度（开始、1/4、1/2、3/4、结束）
            if (mLabels != null && mLabels.size() == mPrices.size()) {
                int n = mPrices.size();
                float dateY = baseY0 + dateH - 3 * mDensity;
                int[] idxs = new int[]{0, n / 4, n / 2, n * 3 / 4, n - 1};
                for (int k = 0; k < idxs.length; k++) {
                    String lab = mLabels.get(idxs[k]);
                    float tx = idxs[k] * stepX;
                    if (k == 0) tx = 0;
                    else if (k == idxs.length - 1) tx = w - pText.measureText(lab);
                    else tx -= pText.measureText(lab) / 2f;
                    cv.drawText(lab, tx, dateY, pText);
                }
            }
            return;
        }

        Path line = new Path();
        Path fill = new Path();
        boolean started = false;
        float lastX = 0;
        for (int i = 0; i < mPrices.size(); i++) {
            float price = mPrices.get(i);
            float x = i * stepX;
            float y = pad + (top - price) / (top - bottom) * usableH;
            if (!started) {
                line.moveTo(x, y);
                fill.moveTo(x, baseY);
                fill.lineTo(x, y);
                started = true;
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
            lastX = x;
        }

        boolean up = mPrices.get(mPrices.size() - 1) >= mPrevClose;
        int color = up ? UP : DOWN;

        fill.lineTo(lastX, baseY);
        fill.close();
        pFill.setColor((color & 0x00FFFFFF) | 0x33000000);
        cv.drawPath(fill, pFill);

        cv.drawLine(0, baseY, w, baseY, pBase);

        pLine.setColor(color);
        cv.drawPath(line, pLine);
    }
}
