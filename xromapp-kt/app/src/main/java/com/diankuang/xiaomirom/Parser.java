package com.diankuang.xiaomirom;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 站点解析：与 Python 版 collect_index / parse_detail / parse_download_page 同逻辑。 */
public final class Parser {

    /** 标题尾部 "Fastboot 线刷包 & Recovery 卡刷包 ROM" */
    static final Pattern SUFFIX_RE =
        Pattern.compile("\\s*Fastboot\\s*线刷包\\s*&\\s*Recovery\\s*卡刷包\\s*ROM\\s*$");
    static final Pattern CODE_RE = Pattern.compile("\\s*\\(([^()]+)\\)");
    static final Pattern CODE_ONLY_RE = Pattern.compile("[A-Za-z0-9_\\-]+");
    static final Pattern OPEN_RE = Pattern.compile("window\\.open\\('([^']+)'");
    static final Pattern VER_DOT_RE = Pattern.compile("\\d+(\\.\\d+)?");

    static final String[] REGION_KEYS = {
        "国行版", "全球版", "欧洲版", "欧版", "国际版", "印度版", "印尼版",
        "印度尼西亚版", "俄罗斯版", "俄版", "土耳其版", "台湾版", "台版",
        "日本版", "韩国版", "韩版", "新加坡版", "马来西亚版", "泰国版",
        "越南版", "菲律宾版", "阿拉伯版", "独联体版", "香港版",
    };

    private Parser() { }

    // ============================================================ 目录页
    /** 一条地区页条目：完整标题 + 绝对 URL */
    public static class Item {
        public final String title, url;
        Item(String t, String u) { title = t; url = u; }
    }

    /** /series/ 一页拿到全站 机型→地区页 目录（dt=data-id + 后续 dd 里的 a[href]）。 */
    public static List<Item> parseIndex(String html) {
        Document doc = Jsoup.parse(html);
        List<Item> out = new ArrayList<Item>();
        Set<String> seen = new LinkedHashSet<String>();
        Elements dts = doc.select("dt[data-id]");
        for (Element dt : dts) {
            Element a = dt.selectFirst("a[href]");
            String dev = a != null ? a.text() : dt.text();
            // 地区链接在该 dt 之后、下一个 dt 之前的所有 dd 里
            Element sib = dt.nextElementSibling();
            while (sib != null && !"dt".equals(sib.tagName())) {
                if ("dd".equals(sib.tagName())) {
                    for (Element link : sib.select("a[href]")) {
                        String href = Http.urlJoin(link.attr("href"));
                        if (!href.contains("/rom/") || seen.contains(href)) continue;
                        seen.add(href);
                        String title = link.attr("title");
                        if (title.isEmpty()) title = dev + " " + link.text();
                        out.add(new Item(title.trim(), href));
                    }
                }
                sib = sib.nextElementSibling();
            }
        }
        return out;
    }

    /** 去 "Fastboot ... ROM" 后缀，只留 机型(代号) 地区 */
    static String cleanTitle(String title) {
        String t = title.replaceAll("\\s+", " ").trim();
        t = SUFFIX_RE.matcher(t).replaceAll("").trim();
        t = t.replaceAll("\\s*Fastboot\\s*线刷包.*$", "").trim();
        return t;
    }

    /** 机型分组键 + 地区标签：代号(...) 之前=机型，之后=地区（对齐 group_by_device）。 */
    public static String[] devAndRegion(String title) {
        String clean = cleanTitle(title);
        Matcher m = CODE_RE.matcher(clean);
        if (m.find()) {
            String dev = clean.substring(0, m.end()).trim();
            String region = clean.substring(m.end()).trim();
            return new String[]{dev, region.isEmpty() ? "默认" : region};
        }
        return new String[]{clean, "默认"};
    }

    // ============================================================ 详情页
    public static class Rom {
        public String device = "", codename = "", region = "", channel = "",
                type = "", version = "", android = "", size = "", date = "",
                fileName = "", detailUrl = "", downloadPage = "", fragment = "";
        public final List<String> cdnUrls = new ArrayList<String>();

        /** 列表行摘要 */
        public String brief() {
            StringBuilder sb = new StringBuilder(version);
            if (!type.isEmpty()) sb.append("  ").append(type);
            if (!size.isEmpty()) sb.append("  ").append(size);
            if (!date.isEmpty()) sb.append("  ").append(date);
            if (!android.isEmpty()) sb.append("  Android ").append(android);
            return sb.toString();
        }
    }

    /** 从 h1 解析 机型/代号/地区（对齐 parse_title）。 */
    static String[] parseTitle(String h1) {
        String title = h1.replaceAll("\\s+", " ").trim();
        title = SUFFIX_RE.matcher(title).replaceAll("").trim();
        title = title.replaceAll("\\s*Fastboot\\s*线刷包.*$", "").trim();
        String device = title, codename = "", region = "";
        Matcher m = CODE_RE.matcher(title);
        if (m.find() && CODE_ONLY_RE.matcher(m.group(1).trim()).matches()) {
            codename = m.group(1).trim();
            device = title.substring(0, m.start()).trim();
            region = title.substring(m.end()).trim();
        }
        if (region.isEmpty()) {
            for (String k : REGION_KEYS) {
                if (title.contains(k)) { region = k; break; }
            }
        } else {
            String hit = "";
            for (String k : REGION_KEYS) {
                if (region.contains(k)) { hit = k; break; }
            }
            if (hit.isEmpty()) {
                int p = region.indexOf('(');
                region = (p > 0 ? region.substring(0, p) : region).trim();
            } else region = hit;
        }
        return new String[]{device, codename, region};
    }

    static String textOf(Element el) {
        return el == null ? "" : el.text().replaceAll("\\s+", " ").trim();
    }

    /** 详情页 → 版本记录列表（对齐 parse_detail：h2 区块 / h3 版本 / table 元数据 / p 下载链接）。 */
    public static List<Rom> parseDetail(String html, String pageUrl) {
        Document doc = Jsoup.parse(html);
        Element h1 = doc.selectFirst("h1");
        String h1t = h1 != null ? h1.text() : pageUrl;
        String[] hdr = parseTitle(h1t);
        String device = hdr[0], codename = hdr[1], region = hdr[2];

        Element body = doc.selectFirst(".rom-body");
        if (body == null) body = doc;
        if (codename.isEmpty()) {
            Element sl = body.selectFirst("a[href*=/series/]");
            if (sl != null) codename = sl.text().trim();
        }

        List<Rom> records = new ArrayList<Rom>();
        String channel = "", ptype = "";
        Rom cur = null;

        for (Element el : body.select("h2, h3, table, p")) {
            String tag = el.tagName();
            if ("h2".equals(tag)) {
                String t = el.text();
                channel = t.contains("稳定版") ? "稳定版"
                        : (t.contains("开发版") || t.contains("内测")) ? "开发版/内测版" : "";
                ptype = t.contains("Fastboot") ? "Fastboot 线刷包"
                      : t.contains("Recovery") ? "Recovery 卡刷包" : "";
                cur = null;
            } else if ("h3".equals(tag)) {
                // h3 的直接文本节点=版本号（badge span 排除在外）
                StringBuilder vb = new StringBuilder();
                for (org.jsoup.nodes.Node n : el.childNodes()) {
                    if (n instanceof org.jsoup.nodes.TextNode) {
                        String s = ((org.jsoup.nodes.TextNode) n).getWholeText().trim();
                        if (!s.isEmpty()) { if (vb.length() > 0) vb.append(' '); vb.append(s); }
                    }
                }
                String android = "";
                for (Element s : el.select("span")) {
                    String b = s.text().trim();
                    if (VER_DOT_RE.matcher(b).matches()) { android = b; break; }
                }
                cur = new Rom();
                cur.device = device; cur.codename = codename; cur.region = region;
                cur.channel = channel; cur.type = ptype;
                cur.version = vb.toString().trim();
                cur.android = android; cur.detailUrl = pageUrl;
                records.add(cur);
            } else if ("table".equals(tag) && cur != null) {
                Element tr = el.selectFirst("tbody tr");
                if (tr != null) {
                    Elements tds = tr.select("td");
                    int n = tds.size();
                    if (n >= 7) {
                        String v = textOf(tds.get(3)), a = textOf(tds.get(4)),
                               sz = textOf(tds.get(5)), d = textOf(tds.get(6));
                        if (!v.isEmpty()) cur.version = v;
                        if (!a.isEmpty()) cur.android = a;
                        if (!sz.isEmpty()) cur.size = sz;
                        if (!d.isEmpty()) cur.date = d;
                        String tp = textOf(tds.get(2));
                        if (tp.contains("Fastboot")) cur.type = "Fastboot 线刷包";
                        else if (tp.contains("Recovery")) cur.type = "Recovery 卡刷包";
                    }
                }
            } else if ("p".equals(tag) && cur != null) {
                Element a = null;
                for (Element cand : el.select("a[href]")) {
                    if (cand.attr("href").contains("/download/")) { a = cand; break; }
                }
                if (a != null) {
                    String href = a.attr("href");
                    int hp = href.indexOf('#');
                    cur.downloadPage = Http.urlJoin(hp < 0 ? href : href.substring(0, hp));
                    cur.fragment = hp < 0 ? "" : href.substring(hp + 1);
                    String txt = el.text();
                    int bar = txt.indexOf('|');
                    if (bar > 0) cur.fileName = txt.substring(0, bar).trim();
                }
            }
        }
        List<Rom> out = new ArrayList<Rom>();
        for (Rom r : records) if (!r.version.isEmpty()) out.add(r);
        return out;
    }

    // ============================================================ 下载页
    /** /download/ 页 → 按锚点区块取直链（对齐 parse_download_page，含关键字回退/合并/版本过滤）。 */
    public static List<String> parseDownloadPage(String html, String fragment, String version) {
        Document doc = Jsoup.parse(html);
        Map<String, List<String>> sections = new LinkedHashMap<String, List<String>>();
        String cur = null;
        for (Element el : doc.select("h2, button, a")) {
            if ("h2".equals(el.tagName())) {
                String hid = el.id();
                if (hid != null && !hid.isEmpty()) {
                    cur = hid;
                    if (!sections.containsKey(cur)) sections.put(cur, new ArrayList<String>());
                }
            } else if (cur != null) {
                Matcher m = OPEN_RE.matcher(el.attr("onclick"));
                if (m.find()) sections.get(cur).add(m.group(1));
            }
        }

        List<String> urls = new ArrayList<String>();
        if (!fragment.isEmpty() && sections.containsKey(fragment)) {
            urls.addAll(sections.get(fragment));
        }
        boolean merged = false;
        if (urls.isEmpty() && !fragment.isEmpty()) {
            String key = fragment.contains("fastboot") ? "fastboot"
                       : fragment.contains("recovery") ? "recovery" : "";
            if (!key.isEmpty()) {
                for (Map.Entry<String, List<String>> e : sections.entrySet()) {
                    if (e.getKey().contains(key)) { urls.addAll(e.getValue()); break; }
                }
            }
        }
        if (urls.isEmpty()) {
            merged = sections.size() > 1;
            for (List<String> v : sections.values()) urls.addAll(v);
        }

        if (version != null && !version.isEmpty()) {
            List<String> filtered = new ArrayList<String>();
            for (String u : urls) if (u.contains(version)) filtered.add(u);
            if (!filtered.isEmpty()) urls = filtered;
        }

        Set<String> seen = new LinkedHashSet<String>(urls);
        List<String> out = new ArrayList<String>(seen);
        int mergedCap = 5 * Math.max(sections.size(), 1);
        int cap = merged ? mergedCap : 5;
        if (out.size() > cap) out = new ArrayList<String>(out.subList(0, cap));
        return out;
    }

    /** 从直链猜文件名（末段路径）。 */
    public static String fileNameOf(String url) {
        String u = url;
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        int s = u.lastIndexOf('/');
        return s >= 0 ? u.substring(s + 1) : u;
    }
}
