package com.dsh.tvdesk.stock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * 油价调价曲线：
 *   · 横向 = 每次调价（等距），纵向 = 元/升
 *   · 线段按涨跌上色：涨红、跌绿
 *   · 左侧标注 3 个价格刻度，标出最高点 / 最低点数值
 *   · 首点画一条虚线作基准
 */
public class HistoryChart extends View {

    private static final int UP = 0xFFFF4D4F;
    private static final int DOWN = 0xFF3FBF6F;
    private static final int GRID = 0xFF2A3242;
    private static final int BASE = 0xFF4A5468;
    private static final int TEXT = 0xFF8C96A8;

    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGrid = new Paint();
    private final Paint pBase = new Paint();
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<Float> mPrices;
    private java.util.List<String> mLabels;   // 与价格等长的日期标签
    private float mDp = 2f;

    public HistoryChart(Context c) {
        super(c);
        mDp = c.getResources().getDisplayMetrics().density;
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(3f * mDp);
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pLine.setStrokeJoin(Paint.Join.ROUND);

        pGrid.setColor(GRID);
        pGrid.setStrokeWidth(1f);

        pBase.setColor(BASE);
        pBase.setStrokeWidth(1f);
        pBase.setPathEffect(new DashPathEffect(new float[]{6 * mDp, 6 * mDp}, 0));

        pText.setColor(TEXT);
        pText.setTextSize(11f * mDp);

        pDot.setStyle(Paint.Style.FILL);
    }

    public void setData(List<Float> prices) {
        mPrices = prices;
        mLabels = null;
        invalidate();
    }

    /** 带日期标签的曲线 */
    public void setData(List<Float> prices, java.util.List<String> labels) {
        mPrices = prices;
        mLabels = labels;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (mPrices == null || mPrices.size() < 2) {
            pText.setColor(TEXT);
            cv.drawText("暂无走势数据", w / 2f - 40 * mDp, h / 2f, pText);
            return;
        }

        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (Float f : mPrices) {
            if (f < lo) lo = f;
            if (f > hi) hi = f;
        }
        if (hi - lo < 0.02f) {          // 防止全平时除零
            hi += 0.02f;
            lo -= 0.02f;
        }

        float leftPad = 46 * mDp;       // 左侧留给价格刻度
        float rightPad = 8 * mDp;
        float topPad = 18 * mDp;
        float bottomPad = 26 * mDp;

        float plotW = w - leftPad - rightPad;
        float plotH = h - topPad - bottomPad;
        float vRange = hi - lo;

        // 三条水平网格 + 左侧刻度
        for (int i = 0; i <= 2; i++) {
            float frac = i / 2f;                       // 0=顶 1=底
            float y = topPad + plotH * frac;
            cv.drawLine(leftPad, y, w - rightPad, y, pGrid);
            float val = hi - vRange * frac;
            pText.setColor(TEXT);
            cv.drawText(String.format(Locale.US, "%.2f", val), 4 * mDp, y + 4 * mDp, pText);
        }

        int n = mPrices.size();
        float stepX = plotW / (n - 1);
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = leftPad + i * stepX;
            float v = mPrices.get(i);
            ys[i] = topPad + (hi - v) / vRange * plotH;
        }

        // 首点基准虚线
        cv.drawLine(leftPad, ys[0], w - rightPad, ys[0], pBase);

        // 逐段上色：涨红跌绿
        for (int i = 1; i < n; i++) {
            float prev = mPrices.get(i - 1);
            float cur = mPrices.get(i);
            int color = cur > prev + 0.0001f ? UP : (cur < prev - 0.0001f ? DOWN : 0xFFAAB0BC);
            pLine.setColor(color);
            cv.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], pLine);
        }

        // 最高点 / 最低点标注
        int iMax = 0, iMin = 0;
        for (int i = 1; i < n; i++) {
            if (mPrices.get(i) > mPrices.get(iMax)) iMax = i;
            if (mPrices.get(i) < mPrices.get(iMin)) iMin = i;
        }
        drawMarker(cv, xs[iMax], ys[iMax], mPrices.get(iMax), UP, "最高", true);
        drawMarker(cv, xs[iMin], ys[iMin], mPrices.get(iMin), DOWN, "最低", false);

        // 底部日期：首 / 中 / 末
        if (mLabels != null && mLabels.size() >= 2) {
            pText.setColor(0xCCE8ECF2);
            pText.setTextSize(12f * mDp);
            int m = mLabels.size();
            // 首 / 中 / 末，按比例映射到价格索引
            int[] pos = {0, m / 2, m - 1};
            for (int k = 0; k < pos.length; k++) {
                int pi = (int) Math.round(pos[k] * (n - 1.0) / Math.max(1, m - 1));
                if (pi < 0) pi = 0;
                if (pi >= n) pi = n - 1;
                String lb = mLabels.get(pos[k]);
                if (lb == null || lb.length() == 0) continue;
                float tw = pText.measureText(lb);
                float tx;
                if (k == 0) tx = leftPad;
                else if (k == pos.length - 1) tx = getWidth() - rightPad - tw;
                else tx = xs[pi] - tw / 2f;
                if (tx < leftPad) tx = leftPad;
                cv.drawText(lb, tx, getHeight() - 4 * mDp, pText);
            }
            pText.setTextSize(11f * mDp);
        }

        // 末端点（当前价）
        pDot.setColor(0xFFFFD479);
        cv.drawCircle(xs[n - 1], ys[n - 1], 4 * mDp, pDot);
        pText.setColor(0xFFFFD479);
        String curTxt = String.format(Locale.US, "%.2f", mPrices.get(n - 1));
        float tw = pText.measureText(curTxt);
        float tx = xs[n - 1] - tw / 2f;
        if (tx < leftPad) tx = leftPad;
        if (tx + tw > w - rightPad) tx = w - rightPad - tw;
        cv.drawText(curTxt, tx, ys[n - 1] - 8 * mDp, pText);
    }

    private void drawMarker(Canvas cv, float x, float y, float val, int color, String label, boolean above) {
        pDot.setColor(color);
        cv.drawCircle(x, y, 4.5f * mDp, pDot);

        pText.setColor(color);
        String txt = label + " " + String.format(Locale.US, "%.2f", val);
        float tw = pText.measureText(txt);
        float tx = x - tw / 2f;
        int w = getWidth();
        int h = getHeight();
        if (tx < 40 * mDp) tx = 40 * mDp;
        if (tx + tw > w - 6 * mDp) tx = w - 6 * mDp - tw;

        // 上方放不下就翻到下方，下方也放不下就钳到边界内
        float aboveY = y - 10 * mDp;
        float belowY = y + 18 * mDp;
        float ty;
        if (above) {
            ty = aboveY < 14 * mDp ? belowY : aboveY;
        } else {
            ty = belowY > h - 18 * mDp ? aboveY : belowY;
        }
        if (ty > h - 18 * mDp) ty = h - 18 * mDp;   // 让出底部日期行
        if (ty < 14 * mDp) ty = 14 * mDp;
        cv.drawText(txt, tx, ty, pText);
    }
}
