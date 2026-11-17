package local.codex.display;

import java.net.URI;
import java.util.Locale;

final class UsageEndpoint {
    static final String DEFAULT_ADDRESS = "http://127.0.0.1:8765";
    final String address;
    final String token;
    final String apiUrl;

    private UsageEndpoint(String address, String token, String apiUrl) {
        this.address = address;
        this.token = token;
        this.apiUrl = apiUrl;
    }

    static UsageEndpoint parse(String input, String key) {
        String address = input.trim();
        String token = key.trim();
        if (address.isEmpty()) {
            address = DEFAULT_ADDRESS;
        }
        URI uri;
        try {
            uri = URI.create(address);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("请输入有效的 HTTP 或 HTTPS 地址。");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null
            || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawUserInfo() != null
            || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("请输入 HTTP 或 HTTPS 地址，令牌请单独填写。");
        }
        if (!token.isEmpty() && !token.matches("[A-Za-z0-9._~+/=-]+")) {
            throw new IllegalArgumentException("令牌含有无效字符。");
        }
        String path = uri.getRawPath().replaceAll("/+$", "");
        if (!path.endsWith("/api/usage")) {
            path += "/api/usage";
        }
        return new UsageEndpoint(address, token, scheme + "://" + uri.getRawAuthority() + path);
    }
}
