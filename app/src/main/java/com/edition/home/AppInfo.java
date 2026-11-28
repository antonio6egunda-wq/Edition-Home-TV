package com.edition.home;

/** Dados básicos de um aplicativo instalado. */
public final class AppInfo {

    public final String packageName;
    public final String label;

    public AppInfo(String packageName, String label) {
        this.packageName = packageName;
        this.label = label;
    }
}
