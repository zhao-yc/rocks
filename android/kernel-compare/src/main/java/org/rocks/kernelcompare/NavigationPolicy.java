package org.rocks.kernelcompare;

import java.net.URI;
import java.util.Locale;

/** 测试包只接受普通网页地址，不解释搜索词或执行外部协议。 */
final class NavigationPolicy {
    private NavigationPolicy() {}

    static String address(String input) {
        if (input == null) return null;
        final String text = input.trim();
        if (text.isEmpty() || text.chars().anyMatch(Character::isWhitespace)) return null;
        final String candidate = text.contains("://") || text.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")
                ? text : "https://" + text;
        return web(candidate) ? candidate : null;
    }

    static boolean web(String url) {
        try {
            final URI uri = URI.create(url);
            final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null
                    && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static boolean allowed(String url, String testUrl) {
        return url != null && (web(url) || url.equals(testUrl) || url.equals("about:blank"));
    }
}
