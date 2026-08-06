package org.rigelmc.webpanel;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal hand-rolled JSON writing - no new dependency, matches this whole feature's own
 * "zero new dependency" design (see {@link WebPanelServer}'s javadoc). Only handles what
 * the panel's own simple, flat data shapes need - not a general-purpose JSON library.
 */
final class JsonUtil {

    private JsonUtil() {}

    @NotNull
    static String string(@Nullable String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    @NotNull
    static String number(@Nullable Number value) {
        return value == null ? "null" : value.toString();
    }

    @NotNull
    static String bool(boolean value) {
        return Boolean.toString(value);
    }
}
