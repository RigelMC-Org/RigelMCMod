package org.rigelmc.webpanel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;

/**
 * Minimal read-only JSON dashboard - the JDK's own built-in {@link HttpServer}, zero new
 * dependency, deliberately not porting TFM's own bundled/adapted NanoHTTPD (the
 * unmaintained, vulnerable-by-default HTTPD was explicitly why this project chose a
 * rewrite over a fork in the first place). No endpoint here can change server state -
 * there is nothing to authenticate or CSRF-protect, by design (the user's explicit
 * decision for this feature): every handler only ever reads {@link
 * WebPanelSnapshotService#current()}, never touching Bukkit API or the database directly
 * from a request-handling thread.
 *
 * <p>Runs on its own small daemon-thread executor, entirely separate from the server's
 * main thread and from {@code RigelExecutors}' DB executor - a slow/stuck HTTP client can
 * never block gameplay or database access.</p>
 *
 * <p>Also serves a small static single-page dashboard (bundled resource {@code
 * webpanel/dashboard.html}) at every path not claimed by an {@code /api/*} context, so the
 * whole feature is reachable at just this one port/domain - no separate static-file
 * hosting needed. The page itself only ever calls back into this same server's {@code
 * /api/*} JSON endpoints (same-origin, so no CORS wrangling), which is also why fronting
 * this with a reverse proxy for TLS/a real domain is a single "proxy everything to this
 * port" rule rather than needing separate static + API upstreams.</p>
 */
public final class WebPanelServer {

    private final RigelMCMod plugin;
    private final WebPanelSnapshotService snapshotService;
    private final SchematicsService schematicsService;
    private HttpServer server;
    private ExecutorService requestExecutor;
    private byte[] dashboardHtml = new byte[0];

    public WebPanelServer(
            @NotNull RigelMCMod plugin, @NotNull WebPanelSnapshotService snapshotService,
            @NotNull SchematicsService schematicsService) {
        this.plugin = plugin;
        this.snapshotService = snapshotService;
        this.schematicsService = schematicsService;
    }

    public void start() {
        String bindAddress = plugin.rigelConfig().webPanelBindAddress();
        int port = plugin.rigelConfig().webPanelPort();
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[WebPanel] Failed to bind " + bindAddress + ":" + port, e);
            return;
        }

        requestExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "RigelMCMod-WebPanel");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(requestExecutor);

        dashboardHtml = loadDashboardHtml();

        server.createContext("/api/status", exchange -> handle(exchange, this::statusJson));
        server.createContext("/api/players", exchange -> handle(exchange, this::playersJson));
        server.createContext("/api/staff", exchange -> handle(exchange, this::staffJson));
        server.createContext("/api/bans", exchange -> handle(exchange, this::bansJson));
        server.createContext("/api/mutes", exchange -> handle(exchange, this::mutesJson));
        if (plugin.rigelConfig().webPanelSchematicsEnabled()) {
            // Registered as its own context (not folded into /api/schematics as a query
            // param dispatch) purely so HttpServer's own longest-prefix-match routing keeps
            // working the same way every other endpoint here already relies on.
            server.createContext("/api/schematics/list", exchange -> handle(exchange, this::schematicsJson));
            server.createContext("/api/schematics/download", this::handleSchematicDownload);
        }
        // Catch-all - HttpServer dispatches by longest matching prefix, so this only ever
        // serves paths the specific /api/* contexts above didn't already claim.
        server.createContext("/", this::handleDashboard);

        server.start();
        plugin.getLogger()
                .info("[WebPanel] Listening on " + bindAddress + ":" + port + " (read-only, GET-only endpoints).");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (requestExecutor != null) {
            requestExecutor.shutdown();
        }
    }

    /** Reads the bundled dashboard page once at startup - static content, never changes per-request. */
    private static byte[] loadDashboardHtml() {
        try (InputStream in = WebPanelServer.class.getClassLoader().getResourceAsStream("webpanel/dashboard.html")) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /** Serves the static single-page dashboard - GET-only, same read-only/no-auth rationale as every /api/* handler. */
    private void handleDashboard(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (dashboardHtml.length == 0) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, dashboardHtml.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(dashboardHtml);
            }
        } finally {
            exchange.close();
        }
    }

    /** Shared plumbing for every endpoint: GET-only, CORS-open (read-only, harmless to expose), JSON content type. */
    private void handle(HttpExchange exchange, java.util.function.Supplier<String> bodySupplier) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = bodySupplier.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    private String statusJson() {
        WebPanelSnapshot snapshot = snapshotService.current();
        return "{"
                + "\"generatedAt\":" + JsonUtil.number(snapshot.generatedAt()) + ","
                + "\"uptimeMillis\":" + JsonUtil.number(snapshot.uptimeMillis()) + ","
                + "\"tps\":" + JsonUtil.number(snapshot.tps()) + ","
                + "\"onlineCount\":" + JsonUtil.number(snapshot.onlineCount()) + ","
                + "\"maxPlayers\":" + JsonUtil.number(snapshot.maxPlayers()) + ","
                // Static config, not part of the periodically-rebuilt snapshot - read fresh
                // here, same as webPanelSchematicsEnabled() is read fresh at start().
                + "\"serverIp\":" + JsonUtil.string(plugin.rigelConfig().serverIpDomain())
                + "}";
    }

    private String playersJson() {
        List<WebPanelSnapshot.PlayerEntry> players = snapshotService.current().onlinePlayers();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < players.size(); i++) {
            WebPanelSnapshot.PlayerEntry entry = players.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
                    .append("\"name\":").append(JsonUtil.string(entry.name())).append(',')
                    .append("\"uuid\":").append(JsonUtil.string(entry.uuid())).append(',')
                    .append("\"rank\":").append(JsonUtil.string(entry.rank()))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private String staffJson() {
        List<WebPanelSnapshot.StaffEntry> staff = snapshotService.current().staff();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < staff.size(); i++) {
            WebPanelSnapshot.StaffEntry entry = staff.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
                    .append("\"name\":").append(JsonUtil.string(entry.name())).append(',')
                    .append("\"uuid\":").append(JsonUtil.string(entry.uuid())).append(',')
                    .append("\"rank\":").append(JsonUtil.string(entry.rank())).append(',')
                    .append("\"online\":").append(JsonUtil.bool(entry.online()))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private String bansJson() {
        List<WebPanelSnapshot.BanEntry> bans = snapshotService.current().recentBans();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < bans.size(); i++) {
            WebPanelSnapshot.BanEntry entry = bans.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
                    .append("\"type\":").append(JsonUtil.string(entry.type())).append(',')
                    .append("\"target\":").append(JsonUtil.string(entry.target())).append(',')
                    .append("\"reason\":").append(JsonUtil.string(entry.reason())).append(',')
                    .append("\"createdAt\":").append(JsonUtil.number(entry.createdAt())).append(',')
                    .append("\"expiresAt\":").append(JsonUtil.number(entry.expiresAt())).append(',')
                    .append("\"active\":").append(JsonUtil.bool(entry.active()))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private String mutesJson() {
        List<WebPanelSnapshot.MuteEntry> mutes = snapshotService.current().currentMutes();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < mutes.size(); i++) {
            WebPanelSnapshot.MuteEntry entry = mutes.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
                    .append("\"target\":").append(JsonUtil.string(entry.target())).append(',')
                    .append("\"reason\":").append(JsonUtil.string(entry.reason())).append(',')
                    .append("\"createdAt\":").append(JsonUtil.number(entry.createdAt())).append(',')
                    .append("\"expiresAt\":").append(JsonUtil.number(entry.expiresAt()))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private String schematicsJson() {
        List<SchematicsService.Entry> entries = schematicsService.list();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            SchematicsService.Entry entry = entries.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
                    .append("\"path\":").append(JsonUtil.string(entry.relativePath())).append(',')
                    .append("\"sizeBytes\":").append(JsonUtil.number(entry.sizeBytes())).append(',')
                    .append("\"modifiedAt\":").append(JsonUtil.number(entry.lastModifiedMillis()))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    /**
     * Serves one schematic file as a download - the only handler in this whole server that
     * isn't pure JSON, and the only one that reads from disk rather than the cached {@link
     * WebPanelSnapshot}. {@link SchematicsService#resolve} is the actual security boundary
     * (path-traversal rejection); this method never touches the filesystem path itself,
     * only the already-validated {@link Path} it returns.
     */
    private void handleSchematicDownload(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String requestedPath = queryParam(exchange, "path");
            Path resolved = requestedPath == null ? null : schematicsService.resolve(requestedPath);
            if (resolved == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            byte[] body = Files.readAllBytes(resolved);
            String downloadName = resolved.getFileName().toString();
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"" + downloadName.replace("\"", "") + "\"");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } finally {
            exchange.close();
        }
    }

    /** @return the URL-decoded value of query parameter {@code name}, or {@code null} if absent. */
    @Nullable
    private static String queryParam(HttpExchange exchange, String name) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!URLDecoder.decode(key, StandardCharsets.UTF_8).equals(name)) {
                continue;
            }
            String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
            return URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
        }
        return null;
    }
}
