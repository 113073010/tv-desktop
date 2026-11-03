import java.lang.reflect.Method;
import java.util.List;

/**
 * splitChunks() 的离线自测：重点保证小数点不会被当成断句符。
 *
 * 用法（在仓库根目录、先跑过 plugin-news/build.ps1 之后）：
 *
 *   set SDK=tools\android-sdk
 *   set JDK=tools\jdk\jdk-17.0.20.1+1
 *   %JDK%\bin\javac -encoding UTF-8 ^
 *       -cp "plugin-news\build\classes;plugin-news\build\stubs.jar;%SDK%\platforms\android-34\android.jar" ^
 *       -d tmp\chunktest plugin-news\test\TestSplitChunks.java
 *   %JDK%\bin\java -cp "tmp\chunktest;plugin-news\build\classes;plugin-news\build\stubs.jar;%SDK%\platforms\android-34\android.jar" ^
 *       TestSplitChunks
 *
 * 背景见 docs/DEVICE.md「语音朗读：小数点被“断句”吞掉」。
 */
public class TestSplitChunks {

    static Method split;
    static int failed = 0;

    @SuppressWarnings("unchecked")
    static List<String> run(String text) throws Exception {
        return (List<String>) split.invoke(null, text);
    }

    static String rep(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    /**
     * 把长文切段后，number 必须完整地出现在某一段里；
     * 否则就会变成 “3.” + “5”，微软语音会把点当句号吞掉。
     */
    static void keepIntact(String name, String text, String number) throws Exception {
        List<String> cs = run(text);
        boolean found = false;
        for (String c : cs) {
            if (c.contains(number)) { found = true; break; }
        }
        boolean tooLong = false;
        for (String c : cs) if (c.length() > 120) tooLong = true;
        boolean ok = found && !tooLong;
        if (!ok) failed++;
        System.out.println((ok ? "ok   " : "FAIL ") + name
                + "  段数=" + cs.size() + (found ? "" : "  <== " + number + " 被切断")
                + (tooLong ? "  <== 有段超过 120 字" : ""));
    }

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("com.dsh.tvdesk.news.NewsFragment");
        split = c.getDeclaredMethod("splitChunks", String.class);
        split.setAccessible(true);

        String longNoPunct = rep("新闻内容很长没有标点", 7);
        String tail = rep("后面还有很多内容", 6);

        // 没有任何标点，走 80 字硬切，也必须绕开数字
        keepIntact("硬切碰到 3.5", rep("一二三四五六七八九十", 8) + "同比增长3.5倍" + rep("天气很好", 10), "3.5");
        keepIntact("小数点靠近标点切点", "第一段内容结束了。" + rep("新闻内容很长没有标点", 6) + "涨幅达到3.5个百分点，" + tail, "3.5");
        keepIntact("时间 12:30", longNoPunct + "会议定在12:30开始" + tail, "12:30");
        keepIntact("版本 v1.2.3", longNoPunct + "版本v1.2.3已发布" + tail, "v1.2.3");
        keepIntact("千分位 1,234.5", longNoPunct + "成交1,234.5亿元" + tail, "1,234.5");
        keepIntact("百分数 12.75%", rep("一二三四五六七八九十", 4) + "下降了12.75%" + tail, "12.75%");
        keepIntact("网址不断开", longNoPunct + "详见www.example.com/page" + tail, "www.example.com");

        // 短文本不能被多切
        List<String> cs = run("今天天气不错，明天也是好天气。");
        boolean ok = cs.size() == 1;
        if (!ok) failed++;
        System.out.println((ok ? "ok   " : "FAIL ") + "短句不切  段数=" + cs.size());

        System.out.println(failed == 0 ? "\n全部通过" : "\n失败 " + failed + " 项");
        if (failed != 0) System.exit(1);
    }
}
