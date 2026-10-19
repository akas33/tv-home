package com.gathin.tvhome;

import android.content.Context;
import android.media.tv.TvInputInfo;

/**
 * Naming the TV's inputs, in one place, because both the Inputs menu and the dock need the same
 * answer. An input renamed in the TV's own settings keeps that name in a separate "custom label"
 * and leaves the built-in one empty, and some TVs report no label at all - this one reports none
 * for HDMI 1 - so the port number is read out of the input's id as a last resort.
 */
final class TvInputs {

    private TvInputs() {}

    /** The name to show for an input, after any rename in config.xml's input_labels. */
    static String label(Context context, TvInputInfo info) {
        return rename(context, raw(context, info));
    }

    private static String raw(Context context, TvInputInfo info) {
        try {
            CharSequence custom = info.loadCustomLabel(context);
            if (custom != null && custom.toString().trim().length() > 0) {
                return custom.toString().trim();
            }
        } catch (Exception ignored) {
            // older firmware: fall through
        }
        CharSequence built = info.loadLabel(context);
        String label = built == null ? "" : built.toString().trim();
        if (!label.isEmpty()) return label.matches("\\d+") ? "HDMI " + label : label;
        return portName(info);
    }

    /**
     * No label at all: take the port out of the id, e.g.
     * com.droidlogic.tvinput/.services.Hdmi1InputService/HW5 -> HDMI 1
     */
    private static String portName(TvInputInfo info) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(?i)hdmi\\D{0,4}(\\d+)").matcher(info.getId());
        if (m.find()) return "HDMI " + m.group(1);
        if (info.getType() == TvInputInfo.TYPE_HDMI) return "HDMI";
        return context_free_fallback(info);
    }

    private static String context_free_fallback(TvInputInfo info) {
        return info.getType() == TvInputInfo.TYPE_COMPOSITE ? "AV" : "Input";
    }

    /** Applies input_labels from config.xml, e.g. "HDMI 1=Jio". Spaces and case are ignored. */
    static String rename(Context context, String label) {
        String key = label.replaceAll("\\s", "");
        for (String pair : context.getResources().getStringArray(R.array.input_labels)) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (key.equalsIgnoreCase(pair.substring(0, eq).replaceAll("\\s", ""))) {
                return pair.substring(eq + 1).trim();
            }
        }
        return label;
    }
}
