package work.diffdev.ccpython.net;

import dan200.computercraft.core.apis.http.options.Action;
import dan200.computercraft.core.apis.http.options.AddressRule;
import dan200.computercraft.core.apis.http.options.Options;
import work.diffdev.ccpython.PythonConfig;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * CC's HTTP address rules applied to Python's requests and websockets: the same allow/deny list (by default private
 * and local addresses are denied), limits and on/off switches as Lua's http API, unless the mod config overrides them.
 * The host is resolved here, so call this on the worker thread, never the computer thread.
 */
final class NetRules {
    private NetRules() {}

    record Checked(URI uri, InetSocketAddress address, Options options) {}

    static final class Denied extends Exception {
        Denied(String message) {
            super(message, null, false, false);
        }
    }

    static Checked check(String url, boolean websocket) throws Denied {
        PythonConfig config = PythonConfig.get();
        if (websocket ? !config.websocketEnabled() : !config.httpEnabled()) {
            throw new Denied(websocket ? "Websocket connections are disabled" : "HTTP requests are disabled");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new Denied("URL malformed");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean ssl;
        if (websocket) {
            if (!scheme.equals("ws") && !scheme.equals("wss")) throw new Denied("Invalid scheme '" + scheme + "'");
            ssl = scheme.equals("wss");
        } else {
            if (!scheme.equals("http") && !scheme.equals("https")) throw new Denied("Invalid protocol '" + scheme + "'");
            ssl = scheme.equals("https");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new Denied("URL malformed");

        int port = uri.getPort() < 0 ? (ssl ? 443 : 80) : uri.getPort();
        InetSocketAddress address = new InetSocketAddress(host, port);
        if (address.isUnresolved()) throw new Denied("Unknown host");
        if (address.getAddress() instanceof Inet6Address inet6 && (inet6.getScopedInterface() != null || inet6.getScopeId() != 0)) {
            throw new Denied("Scoped address not permitted");
        }

        Options options = AddressRule.apply(config.httpRules(), host, address);
        // CC says only "Domain not permitted"; the address helps when DNS hands out a private one (fake-IP proxies).
        if (options.action() == Action.DENY) {
            throw new Denied("Domain not permitted (" + host + " -> " + address.getAddress().getHostAddress() + ")");
        }
        return new Checked(uri, address, options);
    }
}
