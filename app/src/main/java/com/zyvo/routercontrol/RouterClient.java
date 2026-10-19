package com.zyvo.routercontrol;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * TP-Link TL-WR845N web-UI client (port of the proven python client.py).
 * Flow: getParm -> AES login (RSA-nopad signed) -> 4-block bootstrap -> data calls.
 * All calls are blocking; run them on a background thread.
 */
public class RouterClient {

    private static final String BOOTSTRAP =
            "[IGD_DEV_INFO#0,0,0,0,0,0#0,0,0,0,0,0]0,4\r\nmodelName\r\ndescription\r\nX_TP_isFD\r\nX_TP_ProductVersion\r\n" +
            "[ETH_SWITCH#0,0,0,0,0,0#0,0,0,0,0,0]1,1\r\nnumberOfVirtualPorts\r\n" +
            "[MULTIMODE#0,0,0,0,0,0#0,0,0,0,0,0]2,1\r\nmode\r\n" +
            "[/cgi/info#0,0,0,0,0,0#0,0,0,0,0,0]3,0\r\n";

    private final String host;
    private final String user;
    private final String pass;
    private String cookie = "";

    private BigInteger nn;
    private BigInteger ee;
    private long seq;
    private String key;
    private String iv;
    private String h;

    private boolean loggedIn = false;

    public RouterClient(String host, String user, String pass) {
        this.host = host;
        this.user = user;
        this.pass = pass;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    // ---------------------------------------------------------------- login

    public boolean login() throws Exception {
        String gp = post("/cgi?8", "[/cgi/getParm#0,0,0,0,0,0#0,0,0,0,0,0]0,0\r\n", false);
        nn = new BigInteger(must(gp, "var nn=\"([0-9A-Fa-f]+)\""), 16);
        ee = new BigInteger(must(gp, "var ee=\"([0-9A-Fa-f]+)\""), 16);
        seq = Long.parseLong(must(gp, "var seq=\"(\\d+)\""));
        key = rand16();
        iv = rand16();
        h = md5Hex(user + pass);

        String plain = "8\r\n[/cgi/login#0,0,0,0,0,0#0,0,0,0,0,0]0,2\r\n"
                + "username=" + user + "\r\npassword=" + pass + "\r\n";
        String db = aesEnc(plain);
        String sg = rsaNoPad("key=" + key + "&iv=" + iv + "&h=" + h + "&s=" + (seq + db.length()));
        String resp = post("/cgi_gdpr?8", "sign=" + sg + "\r\ndata=" + db + "\r\n", false);
        post("/cgi?1&1&1&8", BOOTSTRAP, true);

        String dec = aesDec(resp);
        loggedIn = dec.contains("[cgi]0");
        return loggedIn;
    }

    // ---------------------------------------------------------------- data call

    /** Send one data operation (GL/GS/ACT line) and return the decrypted reply. */
    public String call(String line) throws Exception {
        String db = aesEnc(line);
        String sg = rsaNoPad("h=" + h + "&s=" + (seq + db.length()));
        String resp = post("/cgi_gdpr", "sign=" + sg + "\r\ndata=" + db + "\r\n", true);
        loggedIn = true;
        return aesDec(resp);
    }

    // ---------------------------------------------------------------- HTTP

    private String post(String path, String body, boolean mainFrameReferer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://" + host + path).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(12000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/plain");
            conn.setRequestProperty("Referer", "http://" + host + "/" + (mainFrameReferer ? "mainFrame.htm" : ""));
            if (!cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            byte[] payload = body.getBytes(StandardCharsets.US_ASCII);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.getOutputStream().write(payload);

            int status = conn.getResponseCode();
            InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            updateCookie(conn);
            if (status != 200) throw new RouterException("Router replied HTTP " + status);
            return text;
        } finally {
            conn.disconnect();
        }
    }

    private void updateCookie(HttpURLConnection conn) {
        Map<String, List<String>> headers = conn.getHeaderFields();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                for (String s : e.getValue()) {
                    Matcher m = Pattern.compile("JSESSIONID=([^;]+)").matcher(s);
                    if (m.find()) {
                        String v = m.group(1).trim();
                        cookie = v.equals("deleted") ? "" : "JSESSIONID=" + v;
                    }
                }
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.US_ASCII);
    }

    // ---------------------------------------------------------------- crypto

    private String rsaNoPad(String s) {
        byte[] in = s.getBytes(StandardCharsets.US_ASCII);
        StringBuilder out = new StringBuilder();
        int nb = 64;
        for (int i = 0; i < in.length; i += nb) {
            byte[] chunk = new byte[nb];
            int len = Math.min(nb, in.length - i);
            System.arraycopy(in, i, chunk, 0, len);
            BigInteger c = new BigInteger(1, chunk).modPow(ee, nn);
            String hex = c.toString(16);
            while (hex.length() < 128) hex = "0" + hex;
            out.append(hex);
        }
        return out.toString();
    }

    private String aesEnc(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "AES"),
                new IvParameterSpec(iv.getBytes(StandardCharsets.US_ASCII)));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    private String aesDec(String b64) throws Exception {
        String clean = b64.replaceAll("\\s", "");
        if (clean.isEmpty()) return "";
        byte[] raw = Base64.decode(clean, Base64.DEFAULT);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "AES"),
                new IvParameterSpec(iv.getBytes(StandardCharsets.US_ASCII)));
        byte[] pt = c.doFinal(raw);
        return new String(pt, StandardCharsets.UTF_8);
    }

    private static String md5Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String rand16() {
        String ms = String.valueOf(System.currentTimeMillis());
        StringBuilder sb = new StringBuilder(ms);
        SecureRandom r = new SecureRandom();
        while (sb.length() < 16) sb.append((char) ('0' + r.nextInt(10)));
        return sb.substring(0, 16);
    }

    private static String must(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) throw new RouterException("Unexpected router response (getParm)");
        return m.group(1);
    }

    public static class RouterException extends RuntimeException {
        public RouterException(String msg) { super(msg); }
    }
}
