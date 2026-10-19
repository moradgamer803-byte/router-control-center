package com.zyvo.routercontrol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** High-level router operations built on the proven wire formats. */
public class RouterApi {

    private final RouterClient client;

    public RouterApi(RouterClient client) {
        this.client = client;
    }

    public interface Progress {
        void step(String message);
    }

    // ------------------------------------------------------------ models

    public static class Block {
        public String stack = "";
        public final LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        public String f(String key) { return fields.get(key); }
    }

    public static class Wifi {
        public String stack = "";
        public String name = "";
        public String ssid = "";
        public String psk = "";
        public boolean filterEnabled;
        public boolean filterKnown;
        public String rule = "deny";
        public boolean found;
    }

    public static class Host {
        public String ip = "";
        public String mac = "";
        public String hostName = "";
        public boolean dhcp;
        public long leaseSeconds;
    }

    public static class MacEntry {
        public String stack = "";
        public String mac = "";
        public String description = "";
        public String hostName = "";
        public boolean enabled;
    }

    public static class Wan {
        public boolean connected;
        public String status = "Unknown";
        public String ip = "";
        public String gateway = "";
        public long uptimeSeconds;
        public String ifName = "";
        public String username = "";
    }

    public static class DevInfo {
        public String model = "";
        public String firmware = "";
        public String hardware = "";
        public long upTimeSeconds;
    }

    // ------------------------------------------------------------ parser

    private static final Pattern HEADER = Pattern.compile("^\\[([0-9,]+)\\][\\d,]*$");

    public static List<Block> parse(String reply) {
        List<Block> blocks = new ArrayList<>();
        Block cur = null;
        for (String raw : reply.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = HEADER.matcher(line);
            if (m.matches()) {
                cur = new Block();
                cur.stack = m.group(1);
                blocks.add(cur);
                continue;
            }
            if (line.startsWith("[cgi]") || line.startsWith("[error]")) continue;
            int eq = line.indexOf('=');
            if (eq > 0 && cur != null) {
                cur.fields.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return blocks;
    }

    private static String pick(Map<String, String> f, String... keys) {
        for (String k : keys) {
            for (Map.Entry<String, String> e : f.entrySet()) {
                if (e.getKey().equalsIgnoreCase(k)) return e.getValue();
            }
        }
        return "";
    }

    // ------------------------------------------------------------ reads

    /** Reads LAN_WLAN (all fields) and returns the 2.4GHz entry. */
    public Wifi readWifi() throws Exception {
        String reply = client.call("5\r\n[LAN_WLAN#0,0,0,0,0,0#0,0,0,0,0,0]0,0\r\n");
        Wifi wifi = new Wifi();
        Block chosen = null;
        Block fallback = null;
        for (Block b : parse(reply)) {
            if (pick(b.fields, "SSID").isEmpty() && pick(b.fields, "name").isEmpty()) continue;
            if (fallback == null) fallback = b;
            String band = pick(b.fields, "X_TP_Band");
            if (band.contains("2.4")) { chosen = b; break; }
            if (chosen == null && !band.contains("5")) chosen = b;
        }
        if (chosen == null) chosen = fallback;
        if (chosen != null) {
            Block b = chosen;
            wifi.found = true;
            wifi.stack = b.stack;
            wifi.name = pick(b.fields, "name");
            wifi.ssid = pick(b.fields, "SSID");
            wifi.psk = pick(b.fields, "X_TP_PreSharedKey");
            String enabled = pick(b.fields, "MACAddressControlEnabled");
            String rule = pick(b.fields, "X_TP_MACAddressControlRule");
            if (!enabled.isEmpty()) {
                wifi.filterKnown = true;
                wifi.filterEnabled = "1".equals(enabled);
            }
            if (!rule.isEmpty()) wifi.rule = rule;
        }
        if (!wifi.found) throw new RouterClient.RouterException("WiFi info not found in router reply");
        return wifi;
    }

    public List<Host> readHosts() throws Exception {
        String reply = client.call("5\r\n[LAN_HOST_ENTRY#0,0,0,0,0,0#0,0,0,0,0,0]0,0\r\n");
        List<Host> out = new ArrayList<>();
        for (Block b : parse(reply)) {
            String mac = pick(b.fields, "MACAddress");
            if (mac.isEmpty()) continue;
            Host x = new Host();
            x.mac = mac;
            x.ip = pick(b.fields, "IPAddress");
            x.hostName = pick(b.fields, "hostName");
            String t = pick(b.fields, "X_TP_ConnType");
            x.dhcp = "1".equals(t);
            try { x.leaseSeconds = Long.parseLong(pick(b.fields, "leaseTimeRemaining")); } catch (Exception ignore) {}
            out.add(x);
        }
        return out;
    }

    public List<MacEntry> readMacTable(String wlanStack) throws Exception {
        String reply = client.call("6\r\n[LAN_WLAN_MACTABLEENTRY#0,0,0,0,0,0#" + wlanStack
                + "]0,4\r\nEnabled\r\nMACAddress\r\nDescription\r\nHostName\r\n");
        List<MacEntry> out = new ArrayList<>();
        for (Block b : parse(reply)) {
            String mac = pick(b.fields, "MACAddress");
            if (mac.isEmpty()) continue;
            MacEntry e = new MacEntry();
            e.stack = b.stack;
            e.mac = mac.toUpperCase();
            e.description = pick(b.fields, "Description");
            e.hostName = pick(b.fields, "HostName");
            e.enabled = "1".equals(pick(b.fields, "Enabled"));
            out.add(e);
        }
        return out;
    }

    public DevInfo readDevInfo() throws Exception {
        String reply = client.call("1\r\n[IGD_DEV_INFO#0,0,0,0,0,0#0,0,0,0,0,0]0,3\r\n"
                + "softwareVersion\r\nhardwareVersion\r\nupTime\r\n");
        DevInfo d = new DevInfo();
        for (Block b : parse(reply)) {
            d.model = pick(b.fields, "modelName");
            d.firmware = pick(b.fields, "softwareVersion");
            d.hardware = pick(b.fields, "hardwareVersion");
            try { d.upTimeSeconds = Long.parseLong(pick(b.fields, "upTime")); } catch (Exception ignore) {}
        }
        return d;
    }

    public Wan readWan() throws Exception {
        String reply = client.call("5\r\n[WAN_PPP_CONN#0,0,0,0,0,0#1,1,0,0,0,0]0,0\r\n");
        Wan w = new Wan();
        Block best = null;
        for (Block b : parse(reply)) {
            String en = pick(b.fields, "enable");
            String nm = pick(b.fields, "name");
            if (nm.isEmpty()) continue;
            if ("1".equals(en) && !nm.contains("v6")) { best = b; break; }
            if (best == null && "1".equals(en)) best = b;
        }
        if (best == null) return w;
        w.status = pick(best.fields, "connectionStatus");
        w.connected = w.status.toLowerCase().contains("connected");
        w.ip = pick(best.fields, "externalIPAddress");
        w.gateway = pick(best.fields, "defaultGateway");
        w.ifName = pick(best.fields, "X_TP_IfName");
        w.username = pick(best.fields, "username");
        try { w.uptimeSeconds = Long.parseLong(pick(best.fields, "uptime")); } catch (Exception ignore) {}
        return w;
    }

    // ------------------------------------------------------------ writes

    public void setFilter(String wlanStack, boolean on) throws Exception {
        String reply = client.call("2\r\n[LAN_WLAN#" + wlanStack
                + "#0,0,0,0,0,0]0,1\r\nMACAddressControlEnabled=" + (on ? "1" : "0") + "\r\n");
        expectOk(reply);
    }

    public void setRule(String wlanStack, String rule) throws Exception {
        String reply = client.call("2\r\n[LAN_WLAN#" + wlanStack
                + "#0,0,0,0,0,0]0,1\r\nX_TP_MACAddressControlRule=" + rule + "\r\n");
        expectOk(reply);
    }

    public void addMacEntry(String wlanStack, String mac, String hostName, String desc) throws Exception {
        String reply = client.call("3\r\n[LAN_WLAN_MACTABLEENTRY#0,0,0,0,0,0#" + wlanStack
                + "]0,4\r\nEnabled=1\r\nDescription=" + desc + "\r\nMACAddress=" + mac + "\r\nHostName=" + hostName + "\r\n");
        expectOk(reply);
    }

    public void delMacEntry(String entryStack) throws Exception {
        String reply = client.call("4\r\n[LAN_WLAN_MACTABLEENTRY#" + entryStack
                + "#0,0,0,0,0,0]0,0\r\n");
        expectOk(reply);
    }

    public void reboot() throws Exception {
        String reply = client.call("7\r\n[ACT_REBOOT#0,0,0,0,0,0#0,0,0,0,0,0]0,0\r\n");
        expectOk(reply);
    }

    private static void expectOk(String reply) {
        if (reply.contains("[error]")) {
            Matcher m = Pattern.compile("\\[error\\](\\d+)").matcher(reply);
            String code = m.find() ? m.group(1) : "?";
            if (!"0".equals(code)) {
                throw new RouterClient.RouterException("Router refused the command (error " + code + ")");
            }
        }
    }

    // ------------------------------------------------------------ block flow

    /** Block a device: deny-rule + filter on + MAC entry, then verify by read-back. */
    public boolean blockDevice(String mac, String displayName, Progress cb) throws Exception {
        Wifi wifi = readWifi();
        cb.step("Preparing device blocking\u2026");
        if (!"deny".equalsIgnoreCase(wifi.rule)) {
            setRule(wifi.stack, "deny");
            cb.step("Blocking rule set\u2026");
        }
        if (!wifi.filterKnown || !wifi.filterEnabled) {
            cb.step("Switching device blocking on (WiFi may blink)\u2026");
            setFilter(wifi.stack, true);
        }
        cb.step("Adding device\u2026");
        addMacEntry(wifi.stack, mac.toUpperCase(), wifi.name, sanitize(displayName));
        cb.step("Verifying\u2026");
        for (MacEntry e : readMacTable(wifi.stack)) {
            if (e.mac.equalsIgnoreCase(mac)) return true;
        }
        return false;
    }

    /** Unblock a device: remove its MAC entry, then verify by read-back. */
    public boolean unblockDevice(String mac, Progress cb) throws Exception {
        Wifi wifi = readWifi();
        cb.step("Finding device\u2026");
        String stack = null;
        for (MacEntry e : readMacTable(wifi.stack)) {
            if (e.mac.equalsIgnoreCase(mac)) { stack = e.stack; break; }
        }
        if (stack == null) return true; // already not blocked
        cb.step("Removing device\u2026");
        delMacEntry(stack);
        cb.step("Verifying\u2026");
        for (MacEntry e : readMacTable(wifi.stack)) {
            if (e.mac.equalsIgnoreCase(mac)) return false;
        }
        return true;
    }

    public static String sanitize(String s) {
        if (s == null) return "zyvo";
        String t = s.replaceAll("[^A-Za-z0-9_.-]", "_").trim();
        if (t.isEmpty()) t = "zyvo";
        return t.length() > 30 ? t.substring(0, 30) : t;
    }
}
