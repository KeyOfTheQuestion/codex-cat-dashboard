package local.codex.display;

final class UsageEndpointTest {
    public static void main(String[] args) {
        verify("", "", "http://127.0.0.1:8765/api/usage", "");
        verify("http://192.168.1.20:8871/", " trial-token ", "http://192.168.1.20:8871/api/usage", "trial-token");
        verify("https://usage.example.com/dashboard/", "", "https://usage.example.com/dashboard/api/usage", "");
        verify("https://[::1]:8443/api/usage/", "", "https://[::1]:8443/api/usage", "");
        rejects("file:///tmp/data", "");
        rejects("usage.example.com", "");
        rejects("https://user:secret@usage.example.com", "");
        rejects("https://usage.example.com/api/usage?token=value", "");
        rejects("http://127.0.0.1:99999", "");
        rejects("https://usage.example.com", "unexpected space");
        System.out.println("Usage endpoint tests passed.");
    }

    private static void verify(String address, String token, String expectedUrl, String expectedToken) {
        UsageEndpoint endpoint = UsageEndpoint.parse(address, token);
        if (!expectedUrl.equals(endpoint.apiUrl) || !expectedToken.equals(endpoint.token)) {
            throw new AssertionError("Unexpected endpoint or token");
        }
    }

    private static void rejects(String address, String token) {
        try {
            UsageEndpoint.parse(address, token);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected invalid configuration to be rejected.");
    }
}
