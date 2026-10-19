package com.zyvo.routercontrol;

/** Holds the single active router connection for the whole app. */
public class RouterSession {

    private static RouterSession instance;

    public static synchronized RouterSession get() {
        if (instance == null) instance = new RouterSession();
        return instance;
    }

    private RouterApi api;

    public synchronized boolean connect(String host, String user, String pass) throws Exception {
        RouterClient client = new RouterClient(host, user, pass);
        if (!client.login()) {
            api = null;
            return false;
        }
        api = new RouterApi(client);
        return true;
    }

    public synchronized RouterApi api() {
        return api;
    }

    public synchronized boolean isReady() {
        return api != null;
    }

    public synchronized void clear() {
        api = null;
    }
}
