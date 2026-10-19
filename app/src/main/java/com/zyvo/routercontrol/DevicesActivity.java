package com.zyvo.routercontrol;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zyvo.routercontrol.databinding.ActivityDevicesBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Device list with block / unblock via the router's MAC filter. */
public class DevicesActivity extends AppCompatActivity implements DeviceAdapter.Listener {

    public static class Device {
        public String name = "";
        public String ip = "";
        public String mac = "";
        public boolean blocked;
    }

    private ActivityDevicesBinding b;
    private RouterApi api;
    private DeviceAdapter adapter;
    private RouterApi.Wifi wifi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!RouterSession.get().isReady()) {
            finish();
            return;
        }
        api = RouterSession.get().api();
        b = ActivityDevicesBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.btnBack.setOnClickListener(v -> finish());
        b.btnRetry.setOnClickListener(v -> load());

        adapter = new DeviceAdapter(this);
        b.deviceList.setLayoutManager(new LinearLayoutManager(this));
        b.deviceList.setAdapter(adapter);

        b.filterSwitch.setOnClickListener(v -> toggleFilter());

        load();
    }

    // ---------------------------------------------------------------- load

    private void load() {
        showState(1);
        b.filterSwitch.setEnabled(false);
        MainActivity.BG.execute(() -> {
            try {
                RouterApi.Wifi w = api.readWifi();
                List<RouterApi.Host> hosts = api.readHosts();
                List<RouterApi.MacEntry> table = api.readMacTable(w.stack);

                Map<String, RouterApi.MacEntry> blockedByMac = new LinkedHashMap<>();
                for (RouterApi.MacEntry e : table) blockedByMac.put(e.mac.toUpperCase(), e);

                List<Device> devices = new ArrayList<>();
                Map<String, Boolean> seen = new LinkedHashMap<>();
                for (RouterApi.Host h : hosts) {
                    Device d = new Device();
                    d.name = h.hostName.isEmpty() ? "Unknown device" : h.hostName;
                    d.ip = h.ip;
                    d.mac = h.mac.toUpperCase();
                    d.blocked = blockedByMac.containsKey(d.mac);
                    seen.put(d.mac, true);
                    devices.add(d);
                }
                for (RouterApi.MacEntry e : table) {
                    if (seen.containsKey(e.mac)) continue;
                    Device d = new Device();
                    d.name = !e.description.trim().isEmpty() ? e.description
                            : (e.hostName.isEmpty() ? "Blocked device" : e.hostName);
                    d.ip = "not connected";
                    d.mac = e.mac;
                    d.blocked = true;
                    devices.add(d);
                }

                MainActivity.UI.post(() -> {
                    wifi = w;
                    fillFilterBanner();
                    adapter.setItems(devices);
                    showState(2);
                    b.emptyBox.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
                    b.deviceList.setVisibility(devices.isEmpty() ? View.GONE : View.VISIBLE);
                    b.filterSwitch.setEnabled(true);
                });
            } catch (Exception e) {
                MainActivity.UI.post(() -> {
                    showState(3);
                    b.errorDetail.setText("Could not load the device list. Check the WiFi connection and retry.");
                });
            }
        });
    }

    private void fillFilterBanner() {
        if (wifi == null) return;
        boolean on = wifi.filterKnown && wifi.filterEnabled;
        b.filterStatus.setText(on ? "Device blocking: ON" : "Device blocking: OFF");
        b.filterStatus.setTextColor(getColor(on ? R.color.ok : R.color.text_secondary));
        b.filterSwitch.setChecked(on);
    }

    // ---------------------------------------------------------------- actions

    private void toggleFilter() {
        // CompoundButton toggles BEFORE firing onClick, so isChecked() is the NEW desired state.
        final boolean turnOn = b.filterSwitch.isChecked();
        String msg = turnOn
                ? "Turn device blocking ON? Every device in the blocked list loses internet. The WiFi may blink for a few seconds."
                : "Turn device blocking OFF? All blocked devices get internet back immediately.";
        new MaterialAlertDialogBuilder(this)
                .setTitle(turnOn ? "Enable device blocking?" : "Disable device blocking?")
                .setMessage(msg)
                .setPositiveButton(turnOn ? "Enable" : "Disable", (d, w) -> {
                    b.statusLine.setText("Applying\u2026 WiFi may blink");
                    MainActivity.BG.execute(() -> {
                        try {
                            api.setFilter(wifi.stack, turnOn);
                            MainActivity.UI.post(() -> {
                                b.statusLine.setText("");
                                load();
                            });
                        } catch (Exception e) {
                            MainActivity.UI.post(() -> {
                                b.statusLine.setText("");
                                Toast.makeText(this, "Command failed \u2014 try again", Toast.LENGTH_SHORT).show();
                                load();
                            });
                        }
                    });
                })
                .setNegativeButton("Cancel", (d, w) -> b.filterSwitch.setChecked(wifi.filterKnown && wifi.filterEnabled))
                .show();
    }

    @Override
    public void onBlock(Device d) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Block " + d.name + "?")
                .setMessage("This device loses internet until you unblock it. The WiFi may blink for a few seconds while the rule is applied.")
                .setPositiveButton("Block", (dlg, w) -> runBlock(d))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onUnblock(Device d) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Unblock " + d.name + "?")
                .setMessage("This device gets internet access back.")
                .setPositiveButton("Unblock", (dlg, w) -> runUnblock(d))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runBlock(Device d) {
        b.statusLine.setText("Blocking " + d.name + "\u2026");
        MainActivity.BG.execute(() -> {
            try {
                boolean verified = api.blockDevice(d.mac, d.name,
                        msg -> MainActivity.UI.post(() -> b.statusLine.setText(msg)));
                MainActivity.UI.post(() -> {
                    b.statusLine.setText("");
                    Toast.makeText(this, verified ? d.name + " is now blocked" : "Sent, but could not verify \u2014 check the list",
                            Toast.LENGTH_LONG).show();
                    load();
                });
            } catch (Exception e) {
                MainActivity.UI.post(() -> {
                    b.statusLine.setText("");
                    Toast.makeText(this, "Block failed \u2014 " + shortMsg(e), Toast.LENGTH_LONG).show();
                    load();
                });
            }
        });
    }

    private void runUnblock(Device d) {
        b.statusLine.setText("Unblocking " + d.name + "\u2026");
        MainActivity.BG.execute(() -> {
            try {
                boolean verified = api.unblockDevice(d.mac,
                        msg -> MainActivity.UI.post(() -> b.statusLine.setText(msg)));
                MainActivity.UI.post(() -> {
                    b.statusLine.setText("");
                    Toast.makeText(this, verified ? d.name + " is unblocked" : "Sent, but could not verify \u2014 check the list",
                            Toast.LENGTH_LONG).show();
                    load();
                });
            } catch (Exception e) {
                MainActivity.UI.post(() -> {
                    b.statusLine.setText("");
                    Toast.makeText(this, "Unblock failed \u2014 " + shortMsg(e), Toast.LENGTH_LONG).show();
                    load();
                });
            }
        });
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return "try again";
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    /** 1 = loading, 2 = content, 3 = error */
    private void showState(int state) {
        b.loadingBox.setVisibility(state == 1 ? View.VISIBLE : View.GONE);
        b.contentBox.setVisibility(state == 2 ? View.VISIBLE : View.GONE);
        b.errorBox.setVisibility(state == 3 ? View.VISIBLE : View.GONE);
    }
}
