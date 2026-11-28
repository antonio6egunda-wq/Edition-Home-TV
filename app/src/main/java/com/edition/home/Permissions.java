package com.edition.home;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

/** Pede de uma vez as permissões de execução do app (Android 6 ou mais novo). */
public final class Permissions {

    public static final int REQ_ALL = 7100;

    private static final String[] RUNTIME = {
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    private Permissions() {
    }

    /** Pede as permissões que ainda faltam. Em Android 5 não há pedido, o app já tem tudo do manifesto. */
    public static void requestAll(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String p : RUNTIME) {
            if (activity.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            activity.requestPermissions(missing.toArray(new String[0]), REQ_ALL);
        }
    }
}
