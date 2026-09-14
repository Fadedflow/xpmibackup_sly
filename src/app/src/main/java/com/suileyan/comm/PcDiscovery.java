package com.suileyan.comm;

import android.os.SystemClock;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 局域网 / USB 自动发现电脑端 mibackpc。
 *
 * 三路并发探测，任一路命中即报告（去重后合并）：
 *  1. 局域网：UDP 广播探测码到 8322，mibackpc 单播回 JSON（手机只发广播 + 收单播，
 *     不需要组播锁等特殊权限）
 *  2. USB：adb reverse 建立后手机访问 127.0.0.1:8321/miback/info
 *  3. 上次连接过的地址：覆盖路由器重启后 IP 漂移、UDP 被路由器/AP 隔离吞掉的场景
 */
public final class PcDiscovery {

    /** 电脑端发现应答端口（与 mibackpc serveDiscovery 固定对齐） */
    public static final int DISCOVER_PORT = 8322;
    /** USB 通道默认端口（mibackpc 默认 8321，adb reverse 双端同端口） */
    public static final int USB_PORT = 8321;

    private static final String MAGIC = "MIBACKPC_DISCOVER_V1";

    /** 一台发现的电脑 */
    public static final class PcInfo {
        public final String host;
        public final int port;
        public final String name;
        public final boolean usb; // true = USB 通道（127.0.0.1），false = 局域网

        public PcInfo(String host, int port, String name, boolean usb) {
            this.host = host;
            this.port = port;
            this.name = name;
            this.usb = usb;
        }

        /** WebDAV base（与 BackupFragment.normalizePcUrl 产物一致，PC 端固定挂载 /dav/） */
        public String davUrl() {
            return "http://" + host + ":" + port + "/dav/";
        }

        public boolean sameAs(PcInfo o) {
            return o != null && host.equals(o.host) && port == o.port;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PcInfo && sameAs((PcInfo) o);
        }

        @Override
        public int hashCode() {
            return host.hashCode() * 31 + port;
        }
    }

    private PcDiscovery() {
    }

    /**
     * 同步扫描（约 1.5~2.5s，必须在后台线程调用）。永不抛异常，失败返回空列表。
     */
    public static List<PcInfo> scan() {
        List<PcInfo> out = new ArrayList<>();
        long deadline = SystemClock.elapsedRealtime() + 2500;
        Thread udp = new Thread(() -> udpScan(out, 1500), "pc-scan-udp");
        udp.setDaemon(true);
        udp.start();

        // USB：仅当 adb reverse 已建立时 127.0.0.1 才有应答，短超时快速失败
        probeHttp("127.0.0.1", USB_PORT, true, out, deadline);
        // 上次地址：恢复 config.ini 中记录的 host:port（兼容旧手动配置 pc_backup_addr；
        // 历史值可能是裸 IP:端口，也可能带 scheme / 路径，统一剥掉再解析）
        for (String key : new String[]{"pc_paired_addr", "pc_backup_addr"}) {
            var addr = ConfigHelp.getString(key, "");
            if (addr.isEmpty()) continue;
            addr = addr.replaceFirst("^https?://", "");
            int slash = addr.indexOf('/');
            if (slash >= 0) addr = addr.substring(0, slash);
            if (addr.isEmpty()) continue;
            var host = addr;
            var port = 0;
            int colon = addr.lastIndexOf(':');
            if (colon > 0) {
                host = addr.substring(0, colon);
                try {
                    port = Integer.parseInt(addr.substring(colon + 1));
                } catch (Exception ignored) {
                }
            }
            if (port <= 0 || port > 65535) port = USB_PORT;
            probeHttp(host, port, false, out, deadline);
        }

        try {
            udp.join(Math.max(500, deadline - SystemClock.elapsedRealtime()));
        } catch (InterruptedException ignored) {
        }
        return out;
    }

    /** UDP 广播探测：发探测码到全局广播 + 各网卡定向广播，收 1.5s 单播应答 */
    private static void udpScan(List<PcInfo> out, long windowMs) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(200);
            byte[] magic = MAGIC.getBytes(StandardCharsets.US_ASCII);
            var targets = new ArrayList<InetAddress>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (var ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (var a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        var b = a.getAddress();
                        targets.add(InetAddress.getByAddress(new byte[]{b[0], b[1], b[2], (byte) 0xFF}));
                    }
                }
            }
            for (var t : targets) {
                try {
                    s.send(new DatagramPacket(magic, magic.length, t, DISCOVER_PORT));
                } catch (Exception ignored) {
                }
            }
            long deadline = SystemClock.elapsedRealtime() + windowMs;
            var buf = new byte[512];
            while (SystemClock.elapsedRealtime() < deadline) {
                var p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                var info = parseInfo(new String(buf, 0, p.getLength(), StandardCharsets.UTF_8),
                        p.getAddress().getHostAddress(), false);
                if (info != null) addIfNew(out, info);
            }
        } catch (Exception e) {
            LogHelp.w("XpMiBackup", "pc udp scan failed", e);
        }
    }

    /** HTTP 探测 /miback/info（USB 与上次地址通道共用） */
    private static void probeHttp(String host, int port, boolean usb, List<PcInfo> out, long deadline) {
        if (SystemClock.elapsedRealtime() > deadline) return;
        try {
            var conn = (java.net.HttpURLConnection) new URL(
                    "http://" + host + ":" + port + "/miback/info").openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setInstanceFollowRedirects(false);
            var info = parseInfo(readAll(conn), host, usb);
            conn.disconnect();
            if (info != null) addIfNew(out, info);
        } catch (Exception ignored) {
        }
    }

    private static String readAll(java.net.HttpURLConnection conn) {
        try (var in = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream()) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 解析发现应答：{"service":"mibackpc","name":…,"tcp":…} */
    private static PcInfo parseInfo(String body, String fallbackHost, boolean usb) {
        try {
            var o = new JSONObject(body);
            if (!"mibackpc".equals(o.optString("service"))) return null;
            var host = fallbackHost;
            var port = o.optInt("tcp", usb ? USB_PORT : 0);
            if (port <= 0) return null;
            return new PcInfo(host, port, o.optString("name", "PC"), usb);
        } catch (Exception e) {
            return null;
        }
    }

    private static void addIfNew(List<PcInfo> out, PcInfo info) {
        if (!out.contains(info)) out.add(info);
    }
}
