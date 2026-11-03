package com.dsh.tvdesk.news;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 移植自 edge-tts 库 drm.py 的 Microsoft Edge 在线语音 DRM v2 签名。
 * 生成 Sec-MS-GEC 令牌（CS64 + 魔改 SHA-256），供电视端直连
 * speech.platform.bing.com 合成。已在 6 组 Python 参考向量上逐位对齐（见开发日志）。
 * 纯 Java 8 语法，可在 Android 5 (API 21) 运行。
 */
final class EdgeDrm {

    private static final long MASK = 0xFFFFFFFFL;

    private static final long[] ROUND = {
        0x428A2F98L,0x71374491L,0xB5C0FBCFL,0xE9B5DBA5L,0x3956C25BL,0x59F111F1L,0x923F82A4L,0xAB1C5ED5L,
        0xD807AA98L,0x12835B01L,0x243185BEL,0x550C7DC3L,0x72BE5D74L,0x80DEB1FEL,0x9BDC06A7L,0xC19BF174L,
        0xE49B69C1L,0xEFBE4786L,0x0FC19DC6L,0x240CA1CCL,0x2DE92C6FL,0x4A7484AAL,0x5CB0A9DCL,0x76F988DAL,
        0x983E5152L,0xA831C66DL,0xB00327C8L,0xBF597FC7L,0xC6E00BF3L,0xD5A79147L,0x06CA6351L,0x14292967L,
        0x27B70A85L,0x2E1B2138L,0x4D2C6DFCL,0x53380D13L,0x650A7354L,0x766A0ABBL,0x81C2C92EL,0x92722C85L,
        0xA2BFE8A1L,0xA81A664BL,0xC24B8B70L,0xC76C51A3L,0xD192E819L,0xD6990624L,0xF40E3585L,0x106AA070L,
        0x19A4C116L,0x1E376C08L,0x2748774CL,0x34B0BCB5L,0x391C0CB3L,0x4ED8AA4AL,0x5B9CCA4FL,0x682E6FF3L,
        0x748F82EEL,0x78A5636FL,0x84C87814L,0x8CC70208L,0x90BEFFFAL,0xA4506CEBL,0xBEF9A3F7L,0xC67178F2L
    };
    private static final long ADJUSTMENT = 0xC9DC06A7L;
    private static final long SIGMA0_ADJ = 0x059C0B80L;
    private static final long SIGMA1_ADJ = 0x000B92C0L;
    private static final long[] STATE = {
        0xFD9FBEA2L,0x91243CE7L,0xBE499094L,0xCD2E1E0AL,0x6C006F49L,0x04BD35ABL,0xFEB0A7E4L,0x03EFFBD2L
    };
    private static final long[] INIT = {
        0x6A09E667L,0xBB67AE85L,0x3C6EF372L,0xA54FF53AL,0x510E527FL,0x9B05688CL,0x1F83D9ABL,0x5BE0CD19L
    };
    private static final long[] SCHED = {
        0xEB24BF9EL,0x1558691FL,0xA0C09EE2L,0x0CFA32BAL,0xA622EE8CL,0xD1DD9491L,0xC0DD25A0L,0xF9E70AB6L,
        0xF0C31573L,0x7058CE4EL,0x7188E606L,0x00E5AB33L,0xD258B060L,0x72766564L
    };

    /** 微软固定 trusted client token（新版仍为此值）。 */
    private static final String TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final long WIN_EPOCH = 11644473600L;

    /** _SHA256_BLOCK_CONSTANTS = ROUND[:14] + (ADJUSTMENT,) + ROUND[15:]；仅 first_round==14 时使用。 */
    private static final long[] BLOCK;
    static {
        BLOCK = ROUND.clone();
        BLOCK[14] = ADJUSTMENT;
    }

    private static long rol16(long v){ return ((v<<16)|(v>>>16))&MASK; }
    private static long sig0(long v){ return ((v>>>7)^(v<<25)^(v>>>18)^(v<<14)^(v>>>3))&MASK; }
    private static long sig1(long v){ return ((v>>>17)^(v<<15)^(v>>>19)^(v<<13)^(v>>>10))&MASK; }
    private static long rotr(long v,int n){ return ((v>>>n)|(v<<(32-n)))&MASK; }

    /** SHA-256 压缩：fr==14 时用 BLOCK 常量表（关键差异，勿用 ROUND）。 */
    private static long[] shaRounds(long[] state, long[] w, int fr){
        long a=state[0],b=state[1],c=state[2],d=state[3],e=state[4],f=state[5],g=state[6],h=state[7];
        long[] K = (fr==14) ? BLOCK : ROUND;
        for(int i=fr;i<64;i++){
            long S1=(rotr(e,6)^rotr(e,11)^rotr(e,25))&MASK;
            long ch=(g^(e&(f^g)))&MASK;
            long t1=(h+S1+ch+K[i]+w[i])&MASK;
            long S0=(rotr(a,2)^rotr(a,13)^rotr(a,22))&MASK;
            long maj=((a&b)|(c&(a|b)))&MASK;
            long t2=(S0+maj)&MASK;
            long nh=(t1+t2)&MASK;
            h=g;g=f;f=e;e=(d+t1)&MASK;d=c;c=b;b=a;a=nh;
        }
        return new long[]{a,b,c,d,e,f,g,h};
    }

    /** 消息扩展（使用魔改 sigma，非标准 SHR 组合）。 */
    private static void expand(long[] w, int first){
        for(int i=first;i<64;i++){
            long x=w[i-2], y=w[i-15];
            long s1=(x>>>17)^(x<<15)^(x>>>19)^(x<<13)^(x>>>10);
            long s0=(y>>>7)^(y<<25)^(y>>>18)^(y<<14)^(y>>>3);
            w[i]=(s1+w[i-7]+s0+w[i-16])&MASK;
        }
    }

    private static long be(byte[] d,int s,int e){ long v=0; for(int i=s;i<e;i++) v=(v<<8)|(d[i]&0xFFL); return v; }
    private static long le(byte[] d,int s,int e){ long v=0; for(int i=e-1;i>=s;i--) v=(v<<8)|(d[i]&0xFFL); return v; }

    /** 由 data 前 7 字节构造 SHA-256 初始状态。 */
    private static long[] shaInit(byte[] data){
        long[] w = new long[64];
        for(int i=0;i<14;i++) w[i]=0;
        w[14]=be(data,0,3); w[15]=be(data,3,7);
        for(int idx=16; idx<30; idx++){
            long v=SCHED[idx-16];
            if(idx-2>=14) v += (idx==16)? (sig1(w[idx-2])^SIGMA1_ADJ) : sig1(w[idx-2]);
            if(idx-7>=14) v += w[idx-7];
            if(idx-15>=14) v += (idx==29)? (sig0(w[idx-15])^SIGMA0_ADJ) : sig0(w[idx-15]);
            w[idx]=v&MASK;
        }
        w[30]=(sig1(w[28])+w[23]+sig0(w[15])+w[14]+ADJUSTMENT-ROUND[14])&MASK;
        expand(w,31);
        long[] work=shaRounds(STATE,w,14);
        long[] out=new long[8];
        for(int i=0;i<8;i++) out[i]=(INIT[i]+work[i])&MASK;
        return out;
    }

    /** 魔改 SHA-256 摘要。 */
    private static byte[] shaDigest(byte[] data){
        int length=57+data.length;
        ByteArrayOutputStream pad=new ByteArrayOutputStream();
        pad.write(data,7,data.length-7);
        pad.write(0x80);
        int zeros=((55-length)%64+64)%64;
        for(int i=0;i<zeros;i++) pad.write(0);
        long lenBits=(long)length*8;
        for(int i=7;i>=0;i--) pad.write((int)((lenBits>>>(8*i))&0xFF));
        byte[] padded=pad.toByteArray();
        long[] state=shaInit(java.util.Arrays.copyOf(data,7));
        for(int off=0; off<padded.length; off+=64){
            long[] w=new long[64];
            for(int i=0;i<16;i++) w[i]=be(padded,off+i*4,off+i*4+4);
            expand(w,16);
            long[] work=shaRounds(state,w,0);
            for(int i=0;i<8;i++) state[i]=(state[i]+work[i])&MASK;
        }
        byte[] out=new byte[32];
        for(int i=0;i<8;i++) for(int j=0;j<4;j++) out[i*4+j]=(byte)((state[i]>>>(8*(3-j)))&0xFF);
        return out;
    }

    /** CS64 摘要，key 为 digest 小端读取的两字。返回 {cs0, cs1}。 */
    private static long[] cs64(long[] key, byte[] data){
        long ka=key[0]|1, kb=key[1]|1;
        long FACTOR=0x82893933L, ADJ0=0xF2F90B1AL, ADJ1=0x0B1A0000L;
        long[] coef={0xE82E91B1L,0x44DE24C6L,0x3BEB65BCL,0xC89C5933L,0x8D1EC211L,0x314EF5D5L,0xE92491B1L,0xF0A5B9D5L,0x06064CF3L,0xDA79FA60L,0xBED20560L,0x89F9C9F8L,0x35FA38C6L,0xCB49E3C6L};
        long[] offs={0xB32700D0L,0x88DFF51DL,0x29514038L,0xB328CBDFL,0xD405B4D5L,0x26E55F3CL,0x607801C6L,0x82B2C1FBL,0xFA51A3DAL,0x207CC464L,0x3C3C74F3L,0xD2D14B61L,0xBB04A2C1L,0xC5626167L};
        long f0=(ka*FACTOR)&MASK, f1=(kb*FACTOR)&MASK;
        long[] terms=new long[14], offsets=new long[14];
        for(int i=0;i<14;i++){ terms[i]=(coef[i]*((i%2==0)?f0:f1))&MASK; offsets[i]=offs[i]; }
        byte[] d2=new byte[data.length+1]; System.arraycopy(data,0,d2,1,data.length);
        int wc=(d2.length+3)/4; if(wc%2==1) wc++;
        long[] w=new long[wc];
        for(int i=0;i<wc;i++){
            long v=0; for(int j=0;j<4;j++){ int bpos=i*4+j; if(bpos<d2.length) v|=(d2[bpos]&0xFFL)<<(8*j);}
            w[i]=v&MASK;
        }
        int n=wc;
        long[] terms2=new long[14+n], offsets2=new long[14+n];
        System.arraycopy(terms,0,terms2,0,14);
        System.arraycopy(offsets,0,offsets2,0,14);
        for(int idx=0;idx<n;idx++){
            long word=w[idx];
            long term=(word*((idx%2==0)?ka:kb))&MASK;
            long offset=(rol16(word)*((idx%2==0)?0xEF0569FBL:0xC31713DBL))&MASK;
            if(idx==0){ term=(term+ADJ0*f0)&MASK; offset=(offset+ADJ1)&MASK; }
            terms2[14+idx]=term&MASK; offsets2[14+idx]=offset&MASK;
        }
        long swapAcc=0, swapLast=0, state=0, acc=0;
        int total=14+n;
        for(int idx=0; idx<total; idx+=2){
            long x=terms2[idx], y=terms2[idx+1];
            long t=rol16((offsets2[idx]+x)&MASK);
            long q=(t*0x76F50F34L)&MASK;
            swapAcc+=(rol16(q)*0xC3EFEA97L+q)&MASK;
            t=rol16((offsets2[idx+1]+y)&MASK);
            q=(t*0x1881CE3CL)&MASK;
            swapLast=(rol16(q)*0x35BD1EC9L+q)&MASK;
            swapAcc=(swapAcc+swapLast)&MASK;
            state=(state*ka+x)&MASK;
            state=(rol16(state)*0xCF98B111L)&MASK;
            state=(rol16(state)*0x87085B9FL)&MASK;
            state=(rol16(state)*0x12CEB96DL)&MASK;
            state=(rol16(state)*0x257E1D83L)&MASK;
            acc+=state;
            t=(state*kb+y)&MASK;
            t=(rol16(t)*0xA27416F5L)&MASK;
            t=(rol16(t)*0xD38396FFL)&MASK;
            state=(rol16((rol16(t)*0x7C932B89L)&MASK)*0xBFA49F69L)&MASK;
            acc=(acc+state)&MASK;
        }
        return new long[]{swapLast^state, swapAcc^acc};
    }

    private static String hex(byte[] b){
        StringBuilder sb=new StringBuilder();
        for(byte x: b) sb.append(String.format("%02X", x&0xFF));
        return sb.toString();
    }
    private static String leHex(long v){
        StringBuilder sb=new StringBuilder();
        for(int i=0;i<4;i++) sb.append(String.format("%02X", (v>>>(8*i))&0xFF));
        return sb.toString();
    }

    /** token hash：返回 "CS64 大写 SHA-256 大写"（空格分隔）。 */
    public static String tokenHash(String s){
        byte[] data=s.getBytes(StandardCharsets.US_ASCII);
        byte[] digest=shaDigest(data);
        long[] key= new long[]{le(digest,0,4), le(digest,4,8)};
        long[] cs=cs64(key,data);
        return leHex(cs[0])+leHex(cs[1])+" "+hex(digest);
    }

    /** 生成 Sec-MS-GEC v2 令牌 "<16hex>-<64hex>"。unix 为 UTC 秒（double）。 */
    public static String generateSecMsGecV2(double unix){
        double ticks=unix+WIN_EPOCH;
        ticks-=ticks%300;
        ticks*=10000000.0;
        String s=String.format("%.0f", ticks)+TOKEN;
        byte[] data=s.getBytes(StandardCharsets.US_ASCII);
        byte[] digest=shaDigest(data);
        long[] key=new long[]{le(digest,0,4), le(digest,4,8)};
        long[] cs=cs64(key,data);
        return leHex(cs[0])+leHex(cs[1])+"-"+hex(digest);
    }
}
