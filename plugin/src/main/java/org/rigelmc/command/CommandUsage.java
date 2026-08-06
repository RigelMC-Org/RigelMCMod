package org.rigelmc.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * Shared "show a clear usage message" helper, used as the {@code .executes()} fallback
 * on every command node that has subcommands/required arguments but was reached with
 * input that stops short of them. Without this, Brigadier's own error for that case is
 * Minecraft's generic "Unknown or incomplete command. See below for error" - technically
 * accurate but unhelpful (confirmed confusing in testing, same class of issue as the
 * earlier {@code /adminconfig} console-only UX fix). Every top-level RigelMCMod command
 * should have one of these wired at every point where input can stop incomplete.
 */
public final class CommandUsage {

    private CommandUsage() {}

    /** @return 0 (Brigadier's "did not run anything" convention), for direct use as a command result */
    public static int show(@NotNull CommandSender sender, @NotNull String usage) {
        sender.sendMessage(Component.text("Usage: " + usage, NamedTextColor.YELLOW));
        return 0;
    }
}
