package org.rigelmc.appeal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rigelmc.RigelMCMod;
import org.rigelmc.identity.IpHasher;
import org.rigelmc.punish.appeal.Appeal;
import org.rigelmc.punish.appeal.AppealService;
import org.rigelmc.punish.ban.Ban;

/**
 * The public ban-appeal form - {@code appeals.rigelmc.org} in production, its own dedicated
 * subdomain (not a path under the main site/web panel domain), fronted by an operator-
 * managed reverse proxy (this process only ever binds locally/plainly, see {@code
 * RigelConfig#appealBindAddress}). Its own {@link HttpServer} instance, deliberately
 * separate from {@code webpanel.WebPanelServer} - that server's entire "no auth, no
 * CSRF-protection" design rests on nothing ever writing anything; this one's {@code POST /}
 * does, so it gets its own scoped abuse-prevention (per-IP rate limiting, input length caps,
 * active-ban validation - see {@code punish.appeal.AppealService#submit}) rather than
 * diluting that other server's own stated invariant.
 *
 * <p>Routes are registered at the domain root ({@code /}, {@code /status}) rather than
 * under an {@code /appeal} prefix - since this has its own dedicated subdomain, there's
 * nothing else on it to namespace against, and it means the reverse proxy config is a
 * single "forward this whole subdomain to this port" rule with no path rewriting needed.</p>
 *
 * <p>Same JDK-built-in {@link HttpServer} approach as {@code WebPanelServer}, own daemon-
 * thread executor. Templates ({@code appeal/form.html}, {@code appeal/message.html},
 * {@code appeal/status.html}) are loaded once at startup as {@link String}s (not the
 * cached {@code byte[]} {@code WebPanelServer} uses for its single static page) since every
 * response here needs per-request {@code %%PLACEHOLDER%%} substitution - simple string
 * replacement rather than pulling in a templating dependency for three small pages.</p>
 */
public final class AppealWebServer {

    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final RigelMCMod plugin;
    private final AppealService appealService;
    private final IpHasher ipHasher;
    private HttpServer server;
    private ExecutorService requestExecutor;
    private String formTemplate = "";
    private String messageTemplate = "";
    private String statusTemplate = "";
    private String searchTemplate = "";

    public AppealWebServer(@NotNull RigelMCMod plugin, @NotNull AppealService appealService, @NotNull IpHasher ipHasher) {
        this.plugin = plugin;
        this.appealService = appealService;
        this.ipHasher = ipHasher;
    }

    public void start() {
        String bindAddress = plugin.rigelConfig().appealBindAddress();
        int port = plugin.rigelConfig().appealPort();
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[Appeal] Failed to bind " + bindAddress + ":" + port, e);
            return;
        }

        requestExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "RigelMCMod-Appeal");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(requestExecutor);

        formTemplate = loadTemplate("form.html");
        messageTemplate = loadTemplate("message.html");
        statusTemplate = loadTemplate("status.html");
        searchTemplate = loadTemplate("search.html");

        server.createContext("/status", this::handleStatus);
        // HttpServer dispatches by longest matching prefix, so "/status" above claims
        // that path first despite "/" below also matching it as a prefix.
        server.createContext("/", this::handleAppeal);

        server.start();
        plugin.getLogger().info("[Appeal] Listening on " + bindAddress + ":" + port + ".");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (requestExecutor != null) {
            requestExecutor.shutdown();
        }
    }

    private void handleAppeal(HttpExchange exchange) throws IOException {
        try {
            switch (exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT)) {
                case "GET" -> handleAppealForm(exchange);
                case "POST" -> handleAppealSubmit(exchange);
                default -> exchange.sendResponseHeaders(405, -1);
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * Two ways to arrive at the actual appeal form, user-requested to simplify the flow
     * for anyone who didn't follow an exact link off their kick screen: a direct {@code
     * case} reference (unchanged - what the kick screen itself links to), or a {@code
     * username} to search by (resolved to that player's current active ban via {@link
     * AppealService#findActiveBanReferenceByUsername}). Neither present at all shows a
     * small search box instead of guessing.
     */
    private void handleAppealForm(HttpExchange exchange) throws IOException {
        String caseParam = queryParam(exchange, "case");
        String username = queryParam(exchange, "username");

        String reference;
        if (caseParam != null && !caseParam.isBlank()) {
            reference = caseParam;
        } else if (username != null && !username.isBlank()) {
            Optional<String> resolved;
            try {
                resolved = appealService.findActiveBanReferenceByUsername(username);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[Appeal] Failed to look up username for the appeal search", e);
                sendSearch(exchange, "Could not look that up right now. Please try again shortly.");
                return;
            }
            if (resolved.isEmpty()) {
                sendSearch(exchange, "No active ban found for username '" + esc(username) + "'.");
                return;
            }
            reference = resolved.get();
        } else {
            sendSearch(exchange, null);
            return;
        }

        Optional<Ban> ban;
        try {
            ban = appealService.findBanByReference(reference);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[Appeal] Failed to look up ban reference for the appeal form", e);
            sendMessage(exchange, 500, "Something Went Wrong",
                    "<p>Could not look up that ban right now. Please try again shortly.</p>");
            return;
        }
        if (ban.isEmpty()) {
            sendMessage(exchange, 404, "Ban Not Found",
                    "<p>We couldn't find a ban matching that reference. If you believe this is an error, "
                            + "reach out to staff directly.</p>");
            return;
        }
        if (!ban.get().isCurrentlyInEffect(System.currentTimeMillis())) {
            sendMessage(exchange, 409, "This Ban Is No Longer Active",
                    "<p>This ban has already expired or been lifted, so there's nothing to appeal.</p>");
            return;
        }

        Map<String, String> values = new HashMap<>();
        values.put("CASE", esc(reference));
        values.put("BAN_SUMMARY", esc(banSummary(ban.get())));
        values.put("ERROR", "");
        values.put("MAX_LENGTH", String.valueOf(plugin.rigelConfig().appealMaxMessageLength()));
        sendHtml(exchange, 200, substitute(formTemplate, values));
    }

    private void handleAppealSubmit(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseFormBody(exchange);
        String reference = form.getOrDefault("case", "");
        String message = form.getOrDefault("message", "");
        String contact = form.get("contact");
        String submitterIpHash = ipHasher.hash(clientIp(exchange));

        AppealService.SubmitOutcome outcome;
        try {
            outcome = appealService.submit(reference, message, contact, submitterIpHash);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[Appeal] Failed to submit an appeal", e);
            sendMessage(exchange, 500, "Something Went Wrong",
                    "<p>Your appeal could not be submitted right now. Please try again shortly.</p>");
            return;
        }

        switch (outcome.result()) {
            case SUBMITTED -> sendMessage(exchange, 200, "Appeal Submitted",
                    "<p>Your appeal has been sent to staff for review.</p>"
                            + "<p>Reference id: <strong>#" + outcome.appeal().id() + "</strong> - "
                            + "you can check its status any time at <a href=\"/status?id="
                            + outcome.appeal().id() + "\">/status</a>.</p>");
            case ALREADY_PENDING -> sendMessage(exchange, 409, "Appeal Already Submitted",
                    "<p>There's already a pending appeal on file for this ban. Please wait for staff to review it.</p>");
            case BAN_NOT_FOUND -> sendMessage(exchange, 404, "Ban Not Found",
                    "<p>We couldn't find a ban matching that reference.</p>");
            case BAN_NOT_ACTIVE -> sendMessage(exchange, 409, "This Ban Is No Longer Active",
                    "<p>This ban has already expired or been lifted, so there's nothing to appeal.</p>");
            case MESSAGE_INVALID -> sendMessage(exchange, 400, "Appeal Message Required",
                    "<p>Please write a short explanation before submitting.</p>");
            case RATE_LIMITED -> sendMessage(exchange, 429, "Please Slow Down",
                    "<p>Too many appeals have been submitted from your network recently. Please try again later.</p>");
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String idParam = queryParam(exchange, "id");
            Map<String, String> values = new HashMap<>();
            values.put("ID_VALUE", esc(idParam == null ? "" : idParam));
            values.put("RESULT", idParam == null || idParam.isBlank() ? "" : statusResultBlock(idParam));
            sendHtml(exchange, 200, substitute(statusTemplate, values));
        } finally {
            exchange.close();
        }
    }

    private String statusResultBlock(String idParam) {
        long id;
        try {
            id = Long.parseLong(idParam.strip());
        } catch (NumberFormatException e) {
            return "<div class=\"card\"><p class=\"muted\">That doesn't look like a valid appeal id.</p></div>";
        }
        Optional<Appeal> appeal;
        try {
            appeal = appealService.findById(id);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[Appeal] Failed to look up appeal status", e);
            return "<div class=\"card\"><p class=\"muted\">Could not look up that appeal right now.</p></div>";
        }
        if (appeal.isEmpty()) {
            return "<div class=\"card\"><p class=\"muted\">No appeal found with that id.</p></div>";
        }
        String pillClass = switch (appeal.get().status()) {
            case PENDING -> "pill-pending";
            case APPROVED -> "pill-approved";
            case DENIED -> "pill-denied";
        };
        return "<div class=\"card\"><p>Appeal #" + appeal.get().id() + ": "
                + "<span class=\"pill " + pillClass + "\">" + esc(appeal.get().status().name()) + "</span></p></div>";
    }

    @NotNull
    private static String banSummary(Ban ban) {
        String target = ban.targetLastName() != null
                ? ban.targetLastName()
                : (ban.targetUuid() != null ? ban.targetUuid().toString() : "IP ban");
        String expiry = ban.isPermanent() ? "Permanent" : EXPIRY_FORMAT.format(Instant.ofEpochMilli(ban.expiresAt()));
        return target + " - " + ban.type() + " - " + expiry + "\nReason: " + ban.reason();
    }

    // ---- plumbing -------------------------------------------------------------------------

    private void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void sendMessage(HttpExchange exchange, int status, String title, String bodyHtml) throws IOException {
        Map<String, String> values = new HashMap<>();
        values.put("TITLE", esc(title));
        values.put("BODY", bodyHtml); // already-built HTML, not escaped
        sendHtml(exchange, status, substitute(messageTemplate, values));
    }

    /** The username-search landing page - {@code error} is shown above the form if non-null, plain otherwise. */
    private void sendSearch(HttpExchange exchange, @Nullable String error) throws IOException {
        Map<String, String> values = new HashMap<>();
        values.put("ERROR", error == null ? "" : "<p class=\"error\">" + error + "</p>");
        sendHtml(exchange, 200, substitute(searchTemplate, values));
    }

    private static String substitute(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("%%" + entry.getKey() + "%%", entry.getValue());
        }
        return result;
    }

    private static String loadTemplate(String name) {
        try (InputStream in = AppealWebServer.class.getClassLoader().getResourceAsStream("appeal/" + name)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * @return the request's client IP - honors {@code appeal.client-ip-header} (first
     *     comma-separated entry, the closest hop to the actual client - the standard
     *     {@code X-Forwarded-For} shape, which is also what Cloudflare's own {@code
     *     CF-Connecting-IP} happens to be a single-value special case of) since this
     *     server is deployed behind an operator-managed reverse proxy/CDN by design (see
     *     class javadoc) - user-reported real deployment: Cloudflare in front of this,
     *     which needs {@code CF-Connecting-IP} specifically rather than the generic
     *     header most other reverse proxies set, hence this being configurable rather
     *     than a hardcoded header name. Without reading the right one, the appeal-
     *     submission rate limiter would see every visitor as the proxy's own single IP
     *     and rate-limit the whole userbase together after the very first submission.
     *     Falls back to the raw socket address if the configured header is blank/absent -
     *     trusting a client-supplied header at all is safe here specifically because this
     *     server only ever binds locally/plainly and is unreachable except through that
     *     same trusted proxy.
     */
    @NotNull
    private String clientIp(HttpExchange exchange) {
        String headerName = plugin.rigelConfig().appealClientIpHeader();
        String headerValue = headerName.isBlank() ? null : exchange.getRequestHeaders().getFirst(headerName);
        if (headerValue != null && !headerValue.isBlank()) {
            return headerValue.split(",")[0].strip();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    @Nullable
    private static String queryParam(HttpExchange exchange, String name) {
        return firstValue(exchange.getRequestURI().getRawQuery(), name);
    }

    /** Reads and URL-decodes an {@code application/x-www-form-urlencoded} POST body. */
    private static Map<String, String> parseFormBody(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        String body = new String(raw, StandardCharsets.UTF_8);
        Map<String, String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }

    @Nullable
    private static String firstValue(@Nullable String rawQuery, String name) {
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

    /** Minimal HTML-escaping for values interpolated into a template outside of already-built HTML blocks. */
    @NotNull
    private static String esc(@NotNull String input) {
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br>");
    }
}
