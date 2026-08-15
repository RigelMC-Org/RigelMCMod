package org.rigelmc.announce;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rigelmc.RigelMCMod;
import org.rigelmc.core.PluginModule;
import org.rigelmc.core.RigelConfig;
import org.rigelmc.rank.PermissionGate;

/**
 * Scheduled rotating broadcaster ({@code announce.broadcast.messages}) plus a
 * {@code /announce} one-off command - see docs/architecture.md's {@code announce/}
 * module. "Configurable with colors" is MiniMessage: both the configured rotation
 * messages and {@code /announce}'s argument are parsed as MiniMessage, so operators can
 * use tags like {@code <red>}, {@code <bold>}, {@code <gradient:blue:aqua>}, etc.
 *
 * <p>User-requested: a bare {@code https://}/{@code http://} URL anywhere in an
 * announcement (either a configured rotation message or a live {@code /announce}
 * argument) is automatically made clickable ({@link #linkify}) - operators don't need to
 * hand-write a MiniMessage {@code <click:open_url:'...'>} tag themselves. Applied via
 * {@link Component#replaceText}, which matches against the already-parsed component
 * tree rather than the raw MiniMessage source string, so a URL can never be misread as
 * (or accidentally break) MiniMessage tag syntax, and any color/formatting tags already
 * wrapping the URL text in the source are preserved on the parts that aren't the link
 * itself.</p>
 */
public final class AnnounceModule implements PluginModule {

    private final PermissionGate permissionGate;
    private AnnouncerService announcerService;

    public AnnounceModule(@NotNull PermissionGate permissionGate) {
        this.permissionGate = permissionGate;
    }

    @Override
    public String id() {
        return "announce";
    }

    @Override
    public boolean isEnabled(RigelConfig config) {
        return config.isModuleEnabled(id());
    }

    @Override
    public void registerListeners(RigelMCMod plugin) {
        RigelConfig config = plugin.rigelConfig();
        this.announcerService = new AnnouncerService(config.announceMessages());

        if (!config.announceEnabled()) {
            return;
        }
        if (!announcerService.hasMessages()) {
            plugin.getLogger()
                    .info("announce module is enabled but announce.broadcast.messages is empty - nothing to say.");
            return;
        }

        long periodTicks = Math.max(config.announceInterval().toSeconds() * 20, 20);
        Bukkit.getScheduler()
                .runTaskTimer(
                        plugin,
                        () -> announcerService
                                .nextMessage()
                                .ifPresent(message -> broadcast(config.announcePrefix() + message)),
                        periodTicks,
                        periodTicks);
    }

    @Override
    public void contributeCommands(Commands registrar) {
        registrar.register(announceTree("announce"), "Broadcast a message to the whole server");
        // A separate independent top-level command, not a Brigadier alias - a plain
        // alias isn't guaranteed to win a bare-label collision against an
        // already-registered Essentials command of the same name (confirmed the hard
        // way for /nick - see nick.NickModule's javadoc for the full story), and
        // Essentials ships its own /broadcast. Registering a second real command node
        // is the same fix applied there.
        registrar.register(announceTree("broadcast"), "Alias of /announce");
    }

    private LiteralCommandNode<CommandSourceStack> announceTree(String rootLiteral) {
        return Commands.literal(rootLiteral)
                .requires(source -> !(source.getSender() instanceof Player player)
                        || permissionGate.hasAtLeastCached(player.getUniqueId(), "moderator"))
                .executes(ctx -> org.rigelmc.command.CommandUsage.show(
                        ctx.getSource().getSender(), "/" + rootLiteral + " <message>"))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            broadcast(StringArgumentType.getString(ctx, "message"));
                            return 1;
                        }))
                .build();
    }

    private static void broadcast(String miniMessageText) {
        Component component = MiniMessage.miniMessage().deserialize(miniMessageText);
        Bukkit.broadcast(linkify(component));
    }

    /**
     * Matches a {@code http(s)://} URL, deliberately excluding common trailing sentence
     * punctuation/closing brackets ({@code .,!?:;)]}) from the match itself, so "check out
     * https://example.com." doesn't swallow the sentence-ending period into the link. Also
     * excludes {@code <}/{@code >} throughout (not just at the end): this runs on the
     * already-MiniMessage-parsed component tree, and MiniMessage is non-strict by default -
     * an unresolvable/misspelled tag (e.g. a typo'd {@code </aqla>} for {@code </aqua>})
     * isn't rejected, it's left behind as literal text. Without this exclusion, a URL
     * sitting directly against one of those (no whitespace between) gets the stray tag text
     * swallowed into the "URL" - which then blows up much later at packet-encode time (an
     * {@code IllegalArgumentException} from {@code URI.create}, since {@link
     * ClickEvent#openUrl} never validates its argument) instead of failing at
     * broadcast-authoring time. The {@code URI.create} guard in {@link #linkify} below is
     * the second, independent layer of the same defense.
     */
    static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>]+[^\\s.,!?:;)\\]<>]");

    @NotNull
    private static Component linkify(@NotNull Component input) {
        return input.replaceText(TextReplacementConfig.builder()
                .match(URL_PATTERN)
                .replacement((matchResult, builder) -> {
                    String url = matchResult.group();
                    Component linkText =
                            Component.text(url).color(NamedTextColor.AQUA).decorate(TextDecoration.UNDERLINED);
                    try {
                        // ClickEvent.openUrl never validates - this is what Paper itself
                        // eventually does at packet-encode time, just done here instead so a
                        // bad match degrades to plain (still-visible) text rather than
                        // corrupting every packet this component is ever sent in.
                        java.net.URI.create(url);
                    } catch (IllegalArgumentException e) {
                        return linkText;
                    }
                    return linkText
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to open", NamedTextColor.GRAY)));
                })
                .build());
    }
}
