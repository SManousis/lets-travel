package com.travelplan.common;

/** Helpers for the service base URLs read from configuration. */
public final class ServiceUrls {
    private ServiceUrls() {}

    /**
     * Removes trailing slashes so paths can be appended with a single "/". A plain loop rather
     * than a regex: it runs in linear time whatever the input looks like.
     */
    public static String withoutTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') end--;
        return url.substring(0, end);
    }
}
