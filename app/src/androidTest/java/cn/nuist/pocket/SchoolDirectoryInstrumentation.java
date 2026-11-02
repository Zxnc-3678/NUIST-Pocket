package cn.nuist.pocket;

import android.app.Instrumentation;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only school directory probe. Reports building names/codes and static route paths only.
 * Cookies stay inside the target app process and are never written to disk or diagnostics. */
public class SchoolDirectoryInstrumentation {
    private final Instrumentation instrumentation;
    private final String buildingFilter;
    private String schoolCookie;
    private String userAgent;
    public SchoolDirectoryInstrumentation(Instrumentation instrumentation, String buildingFilter) { this.instrumentation = instrumentation; this.buildingFilter = buildingFilter; }
    private static final String BASE = "https://jwxt.nuist.edu.cn/jwapp/sys/kxjas/";
    public void run() {
        Bundle result = new Bundle();
        StringBuilder report = new StringBuilder();
        try {
            instrumentation.runOnMainSync(() -> {
                schoolCookie = CookieManager.getInstance().getCookie(BASE);
                userAgent = WebSettings.getDefaultUserAgent(instrumentation.getTargetContext());
            });
            String entry = exchange(BASE + "*default/index.do", false, null);
            report.append("roomEntryLoaded=true\n");
            staticRoutes(entry, report);
            java.util.Map<String, String> expected = new java.util.LinkedHashMap<>();
            expected.put("滨江楼", "1-301"); expected.put("揽江楼", "1-302"); expected.put("阅江楼", "1-303"); expected.put("临江楼", "1-331");
            java.util.Set<String> confirmed = new java.util.HashSet<>();
            for (int page = 1; page <= 5; page++) {
                String body = exchange(BASE + "modules/kxjas/jxlcx.do", true, "pageSize=10&pageNumber=" + page);
                JSONObject root = new JSONObject(body);
                JSONObject data = root.optJSONObject("datas");
                report.append("directoryModules=").append(data == null ? "missing" : data.names()).append('\n');
                JSONObject module = data == null ? null : data.optJSONObject("jxlcx");
                if (module == null) break;
                report.append("total=").append(module.optInt("totalSize", -1)).append('\n');
                JSONArray rows = module.optJSONArray("rows");
                if (rows == null) break;
                if (rows.length() > 0) report.append("rowFields=").append(rows.getJSONObject(0).names()).append('\n');
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    // An explicit allowlist excludes account, user and session fields.
                    JSONObject safe = new JSONObject();
                    for (String key : new String[]{"JXLMC", "JXLDM", "MC", "DM", "XXXQDM_DISPLAY"}) {
                        if (row.has(key)) safe.put(key, row.get(key));
                    }
                    report.append(safe).append('\n');
                    String name = row.optString("JXLMC");
                    if (expected.containsKey(name)) {
                        if (!expected.get(name).equals(row.optString("JXLDM")) || !"校本部".equals(row.optString("XXXQDM_DISPLAY")) || !confirmed.add(name)) throw new java.io.IOException();
                    }
                }
                if (rows.length() == 0 || page * 10 >= module.optInt("totalSize", 0)) break;
            }
            if (confirmed.size() != 4) throw new java.io.IOException();
            for (java.util.Map.Entry<String, String> building : expected.entrySet()) if (buildingFilter == null || buildingFilter.equals(building.getValue())) checkRooms(building.getKey(), building.getValue(), report);
            result.putString("stream", report.toString());
            schoolCookie = null;
            instrumentation.finish(-1, result);
        } catch (Throwable error) {
            String reason = error instanceof SecurityException && ("LoginRequired".equals(error.getMessage()) || "UnexpectedOrigin".equals(error.getMessage())) ? " " + error.getMessage() : "";
            report.append("Directory probe stopped: ").append(error.getClass().getSimpleName()).append(reason).append('\n');
            schoolCookie = null;
            result.putString("stream", report.toString());
            instrumentation.finish(0, result);
        }
    }
    private void checkRooms(String title, String code, StringBuilder report) throws Exception {
        java.time.LocalDate date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        int week = Math.floorDiv((int) java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(2026, 8, 31), date), 7) + 1;
        if (week < 1 || week > 30) throw new java.io.IOException();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int total = -1;
        for (int page = 1; page <= 100; page++) {
            JSONArray query = new JSONArray();
            for (String[] pair : new String[][]{{"JXLDM", code}, {"XNXQDM", "2026-2027-1"}, {"ZC", String.valueOf(week)}, {"XQ", String.valueOf(date.getDayOfWeek().getValue())}, {"RQ", date.toString()}, {"*order", "+LC,+JASMC"}}) {
                query.put(new JSONObject().put("name", pair[0]).put("value", pair[1]).put("linkOpt", "AND").put("builder", "*order".equals(pair[0]) ? "m_value_equal" : "equal"));
            }
            java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
            fields.put("XNXQDM", "2026-2027-1"); fields.put("ZC", String.valueOf(week)); fields.put("XQ", String.valueOf(date.getDayOfWeek().getValue())); fields.put("RQ", date.toString());
            fields.put("querySetting", query.toString()); fields.put("*order", "+LC,+JASMC"); fields.put("pageSize", "10"); fields.put("pageNumber", String.valueOf(page));
            StringBuilder form = new StringBuilder();
            for (java.util.Map.Entry<String, String> field : fields.entrySet()) {
                if (form.length() > 0) form.append('&');
                form.append(java.net.URLEncoder.encode(field.getKey(), "UTF-8")).append('=').append(java.net.URLEncoder.encode(field.getValue(), "UTF-8"));
            }
            JSONObject root = new JSONObject(exchange(BASE + "modules/kxjas/cxjsqk.do", true, form.toString()));
            if (!"0".equals(root.optString("code"))) throw new java.io.IOException();
            JSONObject module = root.getJSONObject("datas").getJSONObject("cxjsqk");
            int currentTotal = module.getInt("totalSize");
            if (total != -1 && total != currentTotal) throw new java.io.IOException();
            total = currentTotal;
            JSONArray rows = module.getJSONArray("rows");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (!code.equals(row.getString("JXLDM")) || !seen.add(row.getString("JASDM"))) throw new java.io.IOException();
            }
            if (seen.size() > total) throw new java.io.IOException();
            if (seen.size() == total) {
                String summary = "ROOMS_VERIFIED " + title + " " + code + " total=" + total + " pages=" + page + " date=" + date + "\n";
                report.append(summary);
                Bundle progress = new Bundle(); progress.putString("stream", summary); instrumentation.sendStatus(1, progress);
                return;
            }
            if (rows.length() == 0) throw new java.io.IOException();
        }
        throw new java.io.IOException();
    }
    private static void staticRoutes(String body, StringBuilder report) {
        Matcher matches = Pattern.compile("[A-Za-z0-9_/*.-]*jxlcx\\.do").matcher(body.replace("\\/", "/"));
        while (matches.find()) report.append("directoryRoute=").append(matches.group()).append('\n');
        Matcher scripts = Pattern.compile("<script[^>]+src=[\"']([^\"'?]+)").matcher(body);
        while (scripts.find()) report.append("script=").append(scripts.group(1)).append('\n');
        Matcher paths = Pattern.compile("[A-Za-z0-9_/*.-]+\\.(?:js|html)").matcher(body.replace("\\/", "/"));
        java.util.Set<String> unique = new java.util.LinkedHashSet<>();
        while (paths.find()) if (unique.size() < 40) unique.add(paths.group());
        for (String path : unique) report.append("staticPath=").append(path).append('\n');
    }
    private String exchange(String initial, boolean post, String form) throws Exception {
        String current = initial;
        for (int redirect = 0; redirect < 8; redirect++) {
            URI uri = URI.create(current);
            if (!"https".equals(uri.getScheme()) || !"jwxt.nuist.edu.cn".equals(uri.getHost()) || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) throw new SecurityException("UnexpectedOrigin");
            HttpURLConnection http = (HttpURLConnection) new URL(current).openConnection();
            try {
                http.setInstanceFollowRedirects(false); http.setConnectTimeout(5000); http.setReadTimeout(7000);
                http.setRequestProperty("User-Agent", userAgent);
                http.setRequestProperty("Referer", BASE + "*default/index.do");
                if (post || !initial.contains("*default/index.do")) http.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                if (schoolCookie != null) http.setRequestProperty("Cookie", schoolCookie);
                if (post) {
                    http.setRequestMethod("POST"); http.setDoOutput(true);
                    http.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                    http.setRequestProperty("Origin", "https://jwxt.nuist.edu.cn");
                    http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                    try (java.io.OutputStream output = http.getOutputStream()) { output.write(form.getBytes(StandardCharsets.UTF_8)); }
                }
                int status = http.getResponseCode();
                Bundle progress = new Bundle(); progress.putString("stream", "School HTTP host=" + uri.getHost() + " status=" + status + "\n"); instrumentation.sendStatus(1, progress);
                if (status >= 300 && status < 400) {
                    if (post) throw new SecurityException("LoginRequired");
                    current = uri.resolve(http.getHeaderField("Location")).toString();
                    continue;
                }
                if (status != 200) throw new java.io.IOException();
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream input = http.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > 4 * 1024 * 1024) throw new java.io.IOException();
                        output.write(buffer, 0, count);
                    }
                }
                byte[] bytes = output.toByteArray();
                String body = new String(bytes, StandardCharsets.UTF_8);
                if (body.contains("统一身份认证平台")) throw new SecurityException("LoginRequired");
                return body;
            } finally { http.disconnect(); }
        }
        throw new java.io.IOException();
    }
}
