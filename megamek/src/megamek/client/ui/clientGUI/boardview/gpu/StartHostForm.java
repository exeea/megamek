/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import megamek.MMConstants;
import megamek.codeUtilities.StringUtility;

/** The start screen's Host and Connect page rules: which inputs are wrong, and the addresses friends connect to. */
final class StartHostForm {
    /** The same service the Network information dialog asks; only asked when the player chooses to show it. */
    private static final String PUBLIC_ADDRESS_SERVICE = "https://checkip.amazonaws.com";
    private static final int TIMEOUT_MILLIS = 4000;

    private StartHostForm() {
    }

    /** The port in {@code text}, or -1 when it is not a number in the allowed range. */
    static int port(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return (value >= MMConstants.MIN_PORT && value <= MMConstants.MAX_PORT) ? value : -1;
        } catch (NumberFormatException | NullPointerException ex) {
            return -1;
        }
    }

    /** Whether the online options start open: they do when the last game changed the password or the port. */
    static boolean onlineOpen(String lastPassword, int lastPort) {
        return !StringUtility.isNullOrBlank(lastPassword) || lastPort != MMConstants.DEFAULT_PORT;
    }

    /** Blocks: this machine's address on its local network, or null when it has none. */
    static String localAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (IOException ex) {
            return null;
        }
    }

    /** Blocks, up to a few seconds: this network's address on the internet, or null when it can't be found. */
    static String publicAddress() {
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
