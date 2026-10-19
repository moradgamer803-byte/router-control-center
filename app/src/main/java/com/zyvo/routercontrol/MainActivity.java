package com.zyvo.routercontrol;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.appcompat.app.AppCompatActivity;

import com.zyvo.routercontrol.databinding.ActivityLoginBinding;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** First screen: connect to the router. */
public class MainActivity extends AppCompatActivity {

    public static final ExecutorService BG = Executors.newSingleThreadExecutor();
    public static final Handler UI = new Handler(Looper.getMainLooper());

    private ActivityLoginBinding b;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        prefs = getSharedPreferences("router", MODE_PRIVATE);
        b.inputIp.setText(prefs.getString("host", "192.168.0.1"));
        b.inputUser.setText(prefs.getString("user", "admin"));
        b.inputPass.setText(prefs.getString("pass", ""));

        b.btnConnect.setOnClickListener(v -> connect());
    }

    private void connect() {
        String host = b.inputIp.getText().toString().trim();
        String user = b.inputUser.getText().toString().trim();
        String pass = b.inputPass.getText().toString();
        if (host.isEmpty() || user.isEmpty()) {
            showError("Please fill the router IP and username.");
            return;
        }
        hideKeyboard();
        setLoading(true);
        showError(null);
        BG.execute(() -> {
            try {
                boolean ok = RouterSession.get().connect(host, user, pass);
                UI.post(() -> {
                    setLoading(false);
                    if (ok) {
                        prefs.edit()
                                .putString("host", host)
                                .putString("user", user)
                                .putString("pass", pass)
                                .apply();
                        startActivity(new Intent(this, DashboardActivity.class));
                        finish();
                    } else {
                        showError("Login failed \u2014 wrong username or password?");
                    }
                });
            } catch (Exception e) {
                UI.post(() -> {
                    setLoading(false);
                    showError("Could not reach the router. Make sure you are connected to its WiFi.");
                });
            }
        });
    }

    private void setLoading(boolean loading) {
        b.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        b.btnConnect.setEnabled(!loading);
        b.btnConnect.setText(loading ? "Connecting\u2026" : "Connect");
    }

    private void showError(String msg) {
        if (msg == null) {
            b.errorText.setVisibility(View.GONE);
        } else {
            b.errorText.setText(msg);
            b.errorText.setVisibility(View.VISIBLE);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View focus = getCurrentFocus();
        if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }
}
