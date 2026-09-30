/*
 * Copyright (C) 2000-2002 Ben Mazur (bmazur@sev.org)
 * Copyright (C) 2003-2025 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

/*
 * UnitLoadingDialog.java
 *  Created by Ryan McConnell on June 15, 2003
 */

package megamek.client.ui.dialogs;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.Serial;
import java.util.Objects;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import megamek.client.ui.GBC;
import megamek.client.ui.Messages;
import megamek.common.loaders.MekSummaryCache;

public class UnitLoadingDialog extends JDialog {
    @Serial
    private static final long serialVersionUID = -3454307876761238915L;

    // Determines how often to update the loading dialog.
    // Setting this too low causes noticeable loading delays.
    private static final int UPDATE_FREQUENCY = 50;

    private final JLabel lCacheCount = new JLabel();
    private final JLabel lFileCount = new JLabel();
    private final JLabel lZipCount = new JLabel();
    private final JProgressBar progressBar = new JProgressBar();
    private final MekSummaryCache mekSummaryCache;
    private final Timer updateTimer = new Timer(UPDATE_FREQUENCY, event -> updateCounts());
    private MekSummaryCache.Listener mekSummaryCacheListener;

    private volatile boolean loadingDone = false;

    public UnitLoadingDialog(JFrame frame) {
        this(frame, MekSummaryCache.getInstance());
    }

    public UnitLoadingDialog(JFrame frame, MekSummaryCache mekSummaryCache) {
        this(frame, mekSummaryCache, Messages.getString("UnitLoadingDialog.LoadingUnits"), false);
    }

    public UnitLoadingDialog(JFrame frame, MekSummaryCache mekSummaryCache, String loadingMessage,
          boolean waitForUpcomingLoad) {
        // Callers opening cache-dependent tools need to wait without blocking Swing's event queue.
        // Passive lobby loading uses showForBackgroundLoad() instead and defers constructing those tools.
        super(frame, Messages.getString("UnitLoadingDialog.pleaseWait"), true);
        this.mekSummaryCache = Objects.requireNonNull(mekSummaryCache);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        getContentPane().setLayout(new GridBagLayout());
        JLabel lLoading = new JLabel(loadingMessage);
        getContentPane().add(lLoading, GBC.eol());

        progressBar.setIndeterminate(true);
        getContentPane().add(progressBar, GBC.eop().fill(GridBagConstraints.HORIZONTAL));

        JLabel lCacheText = new JLabel(Messages.getString("UnitLoadingDialog.fromCache"));
        getContentPane().add(lCacheText, GBC.std());
        getContentPane().add(lCacheCount, GBC.eol());

        JLabel lFileText = new JLabel(Messages.getString("UnitLoadingDialog.fromFiles"));
        getContentPane().add(lFileText, GBC.std());
        getContentPane().add(lFileCount, GBC.eol());

        JLabel lZipText = new JLabel(Messages.getString("UnitLoadingDialog.fromZips"));
        getContentPane().add(lZipText, GBC.std());
        getContentPane().add(lZipCount, GBC.eol());

        updateCounts();
        // Leave room for growing counts without resizing the window during a rebuild.
        lCacheCount.setPreferredSize(new JLabel("000000").getPreferredSize());
        pack();
        setResizable(false);
        // move to middle of screen
        setLocationRelativeTo(frame);

        if (!waitForUpcomingLoad && mekSummaryCache.isInitialized()) {
            dispose();
            return;
        }

        startMonitoring(waitForUpcomingLoad);
    }

    /** Shows dismissible progress without preventing the user from configuring the lobby while units load. */
    public void showForBackgroundLoad() {
        setModal(false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setVisible(true);
    }

    @Override
    public void setVisible(boolean visible) {
        if (visible && loadingDone) {
            return;
        }
        super.setVisible(visible);
    }

    @Override
    public void dispose() {
        loadingDone = true;
        updateTimer.stop();
        unregisterListener();
        super.dispose();
    }

    private void startMonitoring(boolean waitForUpcomingLoad) {
        mekSummaryCacheListener = this::finishMonitoring;
        mekSummaryCache.addListener(mekSummaryCacheListener);

        // A normal load may finish after the constructor's initial isInitialized() check but before the listener is
        // registered. Its only completion event would then already be gone, leaving this indeterminate dialog open.
        // Do not apply this recheck to a dialog created before an explicit Refresh/Rebuild request: that cache can
        // legitimately still be initialized until the upcoming operation starts.
        if (shouldFinishMonitoringAfterRegistration(waitForUpcomingLoad, mekSummaryCache.isInitialized())) {
            finishMonitoring();
        }

        if (!loadingDone) {
            // Swing timers coalesce delayed ticks instead of filling the event queue with stale updates.
            updateTimer.start();
        }
    }

    static boolean shouldFinishMonitoringAfterRegistration(boolean waitForUpcomingLoad, boolean cacheInitialized) {
        return !waitForUpcomingLoad && cacheInitialized;
    }

    private void finishMonitoring() {
        loadingDone = true;
        SwingUtilities.invokeLater(this::dispose);
    }

    private void unregisterListener() {
        if (mekSummaryCacheListener != null) {
            mekSummaryCache.removeListener(mekSummaryCacheListener);
            mekSummaryCacheListener = null;
        }
    }

    private void updateCounts() {
        lCacheCount.setText(String.valueOf(mekSummaryCache.getCacheCount()));
        lFileCount.setText(String.valueOf(mekSummaryCache.getFileCount()));
        lZipCount.setText(String.valueOf(mekSummaryCache.getZipCount()));
    }
}
