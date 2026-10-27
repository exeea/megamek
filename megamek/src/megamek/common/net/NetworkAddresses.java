/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import megamek.codeUtilities.StringUtility;

/** The addresses other players use to reach this machine. Both lookups block, so call them off the UI thread. */
public final class NetworkAddresses {
    private static final String PUBLIC_ADDRESS_SERVICE = "https://checkip.amazonaws.com";
    private static final int TIMEOUT_MILLIS = 4000;

    private NetworkAddresses() {
    }

    /** This machine's address on its local network, or null when it has none. */
    public static String localAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (IOException ex) {
            return null;
        }
    }

    /** Up to a few seconds: this network's address on the internet, or null when it can't be found. */
    public static String publicAddress() {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(PUBLIC_ADDRESS_SERVICE).toURL()
                  .openConnection();
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            try (BufferedReader reader = new BufferedReader(
                  new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII))) {
                String line = reader.readLine();
                return StringUtility.isNullOrBlank(line) ? null : line.trim();
            } finally {
                connection.disconnect();
            }
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
    }
}
