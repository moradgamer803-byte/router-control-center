package com.zyvo.routercontrol;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zyvo.routercontrol.databinding.ActivityDashboardBinding;

import java.util.List;

/** Main hub: WAN status, WiFi, quick actions, router info. */
public class DashboardActivity extends AppCompatActivity {

    private ActivityDashboardBinding b;
    private RouterApi api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!RouterSession.get().isReady()) {
            goLogin();
            return;
        }
        api = RouterSession.get().api();
        b = ActivityDashboardBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.btnRefresh.setOnClickListener(v -> loadAll());
        b.btnDevices.setOnClickListener(v -> startActivity(new Intent(this, DevicesActivity.class)));
        b.btnWifiPass.setOnClickListener(v -> showPassword());
        b.btnReboot.setOnClickListener(v -> confirmReboot());
        b.btnRetry.setOnClickListener(v -> loadAll());

        loadAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (api != null) loadAll();
    }

    private void loadAll() {
        showState(1);
        MainActivity.BG.execute(() -> {
            try {
                RouterApi.Wan wan = api.readWan();
                RouterApi.Wifi wifi = api.readWifi();
                List<RouterApi.Host> hosts = api.readHosts();
                RouterApi.DevInfo dev = api.readDevInfo();
                MainActivity.UI.post(() -> {
                    fillWan(wan);
                    fillWifi(wifi);
                    fillInfo(dev, hosts.size());
                    showState(2);
                });
            } catch (Exception e) {
                MainActivity.UI.post(() -> {
                    showState(3);
                    b.errorDetail.setText("Could not read router data. " +
                            "Make sure you are still on the router WiFi, then retry.");
                });
            }
        });
    }

    private void fillWan(RouterApi.Wan wan) {
        b.wanIp.setText(wan.ip.isEmpty() ? "\u2014" : wan.ip);
        b.wanStatus.setText(wan.connected ? "CONNECTED" : wan.status.toUpperCase());
        b.wanStatus.setTextColor(getColor(wan.connected ? R.color.ok : R.color.danger));
        b.heroUptime.setText("Online for " + formatDuration(wan.uptimeSeconds));
        b.wanUser.setText(wan.username.isEmpty() ? "\u2014" : wan.username);
    }

    private void fillWifi(RouterApi.Wifi wifi) {
        b.heroSsid.setText(wifi.ssid.isEmpty() ? "\u2014" : wifi.ssid);
    }

    private void fillInfo(RouterApi.DevInfo dev, int hostCount) {
        b.statDevices.setText(String.valueOf(hostCount));
        b.statFirmware.setText(shortVersion(dev.firmware));
        b.infoModel.setText(dev.model.isEmpty() ? "TL-WR845N" : dev.model);
        b.infoHardware.setText(dev.hardware.isEmpty() ? "\u2014" : dev.hardware);
        b.infoFirmware.setText(dev.firmware.isEmpty() ? "\u2014" : dev.firmware);
        b.infoUptime.setText(formatDuration(dev.upTimeSeconds));
    }

    private static String shortVersion(String fw) {
        if (fw == null || fw.isEmpty()) return "\u2014";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("0\\.9\\.[0-9.]*\\s*[0-9.]*").matcher(fw);
        if (m.find()) {
            String s = m.group().trim();
            return s.length() > 14 ? s.substring(0, 14) : s;
        }
        return fw.length() > 14 ? fw.substring(0, 14) : fw;
    }

    private void showPassword() {
        MainActivity.BG.execute(() -> {
            try {
                RouterApi.Wifi wifi = api.readWifi();
                MainActivity.UI.post(() -> {
                    String psk = wifi.psk.isEmpty() ? "(not available)" : wifi.psk;
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("WiFi password \u2014 " + (wifi.ssid.isEmpty() ? "MORAD WIFI" : wifi.ssid))
                            .setMessage(psk)
                            .setPositiveButton("Copy", (d, w) -> {
                                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("wifi", psk));
                                Toast.makeText(this, "Password copied", Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Close", null)
                            .show();
                });
            } catch (Exception e) {
                MainActivity.UI.post(() -> Toast.makeText(this,
                        "Could not read WiFi password", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void confirmReboot() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Reboot the router?")
                .setMessage("Internet will go down for about a minute. Everyone on the WiFi is disconnected during the reboot.")
                .setPositiveButton("Reboot", (d, w) -> doReboot())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doReboot() {
        MainActivity.BG.execute(() -> {
            try {
                api.reboot();
                MainActivity.UI.post(() -> Toast.makeText(this,
                        "Router is rebooting \u2014 back in about a minute", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                MainActivity.UI.post(() -> Toast.makeText(this,
                        "Reboot command failed", Toast.LENGTH_SHORT).show());
            }
        });
    }

    public static String formatDuration(long seconds) {
        if (seconds <= 0) return "\u2014";
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    /** 1 = loading, 2 = content, 3 = error */
    private void showState(int state) {
        b.loadingBox.setVisibility(state == 1 ? View.VISIBLE : View.GONE);
        b.contentBox.setVisibility(state == 2 ? View.VISIBLE : View.GONE);
        b.errorBox.setVisibility(state == 3 ? View.VISIBLE : View.GONE);
    }

    private void goLogin() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
