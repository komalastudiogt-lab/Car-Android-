package com.carapk.manager;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
  WebView web;
  File picked;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    web = new WebView(this);
    setContentView(web);
    web.getSettings().setJavaScriptEnabled(true);
    web.addJavascriptInterface(new Bridge(), "Native");
    web.loadUrl("file:///android_asset/index.html");
  }

  void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }
  void toast(String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }
  void go(Intent i) {
    runOnUiThread(() -> { try { startActivity(i); } catch (Exception e) { toast("No disponible en este equipo"); } });
  }

  String info(File f) {
    try {
      PackageManager pm = getPackageManager();
      PackageInfo pi = pm.getPackageArchiveInfo(f.getPath(), 0);
      JSONObject o = new JSONObject();
      if (pi == null) { o.put("err", "Archivo APK no válido"); return o.toString(); }
      pi.applicationInfo.sourceDir = f.getPath();
      pi.applicationInfo.publicSourceDir = f.getPath();
      o.put("n", pm.getApplicationLabel(pi.applicationInfo).toString());
      o.put("p", pi.packageName);
      o.put("v", pi.versionName == null ? "" : pi.versionName);
      try { o.put("old", pm.getPackageInfo(pi.packageName, 0).versionName); } catch (Exception e) {}
      return o.toString();
    } catch (Exception e) { return "{\"err\":\"No se pudo leer el APK\"}"; }
  }

  void walk(File d, int depth, JSONArray out, Set<String> seen) throws Exception {
    if (depth > 4) return;
    File[] fs = d.listFiles();
    if (fs == null) return;
    for (File f : fs) {
      if (f.isDirectory()) {
        if (!f.getName().equals("Android") && !f.getName().startsWith(".")) walk(f, depth + 1, out, seen);
      } else if (f.getName().toLowerCase().endsWith(".apk") && seen.add(f.getName() + f.length())) {
        JSONObject o = new JSONObject();
        o.put("n", f.getName()); o.put("p", f.getPath()); o.put("s", f.length());
        out.put(o);
      }
    }
  }

  @Override protected void onActivityResult(int req, int res, Intent d) {
    if (req == 1 && res == RESULT_OK && d != null && d.getData() != null) {
      final Uri u = d.getData();
      new Thread(() -> {
        try {
          File out = new File(getCacheDir(), "pick.apk");
          try (InputStream in = getContentResolver().openInputStream(u); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
          }
          picked = out;
          js("onPicked(" + info(out) + ")");
        } catch (Exception e) { toast("No se pudo leer el archivo"); }
      }).start();
    }
  }

  @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) { js("onPerm()"); }

  class Bridge {
    @JavascriptInterface public String getApps(boolean system) {
      PackageManager pm = getPackageManager();
      JSONArray arr = new JSONArray();
      for (ApplicationInfo a : pm.getInstalledApplications(0)) {
        boolean sys = (a.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        if ((sys && !system) || a.packageName.equals(getPackageName())) continue;
        try {
          PackageInfo pi = pm.getPackageInfo(a.packageName, 0);
          JSONObject o = new JSONObject();
          o.put("n", pm.getApplicationLabel(a).toString());
          o.put("p", a.packageName);
          o.put("v", pi.versionName == null ? "" : pi.versionName);
          o.put("l", pm.getLaunchIntentForPackage(a.packageName) != null);
          arr.put(o);
        } catch (Exception e) {}
      }
      return arr.toString();
    }

    @JavascriptInterface public void scanApks() {
      if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 33
          && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1));
        return;
      }
      new Thread(() -> {
        try {
          JSONArray out = new JSONArray();
          Set<String> seen = new HashSet<>();
          walk(Environment.getExternalStorageDirectory(), 0, out, seen);
          File[] st = new File("/storage").listFiles();
          if (st != null) for (File f : st) if (!f.getName().equals("self")) walk(f, 0, out, seen);
          js("onScan(" + out + ")");
        } catch (Exception e) { js("onScan([])"); }
      }).start();
    }

    @JavascriptInterface public void pickApk() {
      runOnUiThread(() -> {
        try {
          Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
          startActivityForResult(i, 1);
        } catch (Exception e) {
          try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
            startActivityForResult(i, 1);
          } catch (Exception e2) { toast("Este equipo no tiene explorador de archivos. Usa 'Buscar APK'."); }
        }
      });
    }

    @JavascriptInterface public String selectApk(String path) {
      picked = new File(path);
      return info(picked);
    }

    @JavascriptInterface public void install() {
      if (picked == null || !picked.exists()) { toast("Primero elige un APK"); return; }
      if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
        toast("Activa 'Permitir instalar apps' para Car APK Manager y vuelve a pulsar Instalar");
        go(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        return;
      }
      Intent i = new Intent(Intent.ACTION_VIEW);
      Uri u = Build.VERSION.SDK_INT >= 24
          ? FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", picked)
          : Uri.fromFile(picked);
      i.setDataAndType(u, "application/vnd.android.package-archive");
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      go(i);
    }

    @JavascriptInterface public void openApp(String pkg) {
      Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
      if (i != null) go(i); else toast("Esta app no tiene pantalla de inicio");
    }

    @JavascriptInterface public void uninstall(String pkg) {
      go(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
    }

    @JavascriptInterface public void appInfo(String pkg) {
      go(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    @JavascriptInterface public void shareApk(String pkg) {
      try {
        String src = getPackageManager().getApplicationInfo(pkg, 0).sourceDir;
        File dir = new File(getCacheDir(), "share"); dir.mkdirs();
        File out = new File(dir, pkg + ".apk");
        try (InputStream in = new FileInputStream(src); OutputStream os = new FileOutputStream(out)) {
          byte[] buf = new byte[65536]; int n;
          while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("application/vnd.android.package-archive");
        s.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", out));
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        go(Intent.createChooser(s, "Guardar / compartir APK"));
      } catch (Exception e) { toast("No se pudo exportar el APK"); }
    }

    @JavascriptInterface public void settings(String w) {
      Intent i;
      switch (w) {
        case "unknown": i = Build.VERSION.SDK_INT >= 26
            ? new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))
            : new Intent(Settings.ACTION_SECURITY_SETTINGS); break;
        case "home": i = new Intent(Settings.ACTION_HOME_SETTINGS); break;
        case "apps": i = new Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS); break;
        default: i = new Intent(Settings.ACTION_SETTINGS);
      }
      go(i);
    }
  }
}
