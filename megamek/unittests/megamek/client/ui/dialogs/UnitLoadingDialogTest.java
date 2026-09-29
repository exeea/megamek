/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
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

package megamek.client.ui.dialogs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import megamek.common.loaders.MekSummaryCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UnitLoadingDialogTest {

    @Test
    void closesIfNormalCacheLoadCompletesDuringListenerRegistration() {
        assertTrue(UnitLoadingDialog.shouldFinishMonitoringAfterRegistration(false, true));
    }

    @Test
    void waitsForExplicitRefreshOrRebuildToBegin() {
        assertFalse(UnitLoadingDialog.shouldFinishMonitoringAfterRegistration(true, true));
        assertFalse(UnitLoadingDialog.shouldFinishMonitoringAfterRegistration(false, false));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void keepsUpdatingWhileStartupWaitsForCacheCompletion(boolean upcomingLoad) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        MekSummaryCache cache = mock(MekSummaryCache.class);
        AtomicBoolean initialized = new AtomicBoolean(upcomingLoad);
        AtomicInteger zipCount = new AtomicInteger();
        AtomicReference<MekSummaryCache.Listener> listener = new AtomicReference<>();
        when(cache.isInitialized()).thenAnswer(invocation -> initialized.get());
        when(cache.getZipCount()).thenAnswer(invocation -> zipCount.get());
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(cache).addListener(any());

        CountDownLatch progressUpdated = new CountDownLatch(1);
        UnitLoadingDialog dialog = onEdt(() -> {
            UnitLoadingDialog loading = new UnitLoadingDialog(null, cache, "Loading units...", upcomingLoad);
            for (Component component : loading.getContentPane().getComponents()) {
                if (component instanceof JLabel label) {
                    label.addPropertyChangeListener("text", event -> {
                        if ("11026".equals(event.getNewValue())) {
                            progressUpdated.countDown();
                        }
                    });
                }
            }
            return loading;
        });
        FutureTask<Void> showDialog = new FutureTask<>(() -> {
            dialog.setVisible(true);
            return null;
        });

        try {
            initialized.set(false);
            SwingUtilities.invokeLater(showDialog);
            onEdt(() -> {
                assertTrue(dialog.isShowing());
                assertFalse(showDialog.isDone(), "Cache-dependent startup must wait for the load to finish");
                // Closing the progress window must not release startup into blocking cache lookups.
                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                assertTrue(dialog.isShowing());
                return null;
            });
            zipCount.set(11026);
            assertTrue(progressUpdated.await(5, TimeUnit.SECONDS), "Swing must keep processing progress updates");
            assertFalse(showDialog.isDone());

            initialized.set(true);
            listener.get().doneLoading(); // The real cache sends this from its worker thread.
            showDialog.get(5, TimeUnit.SECONDS);
            onEdt(() -> {
                assertFalse(dialog.isDisplayable(), "Completion must release the dialog's native resources");
                return null;
            });
            verify(cache).removeListener(listener.get());
        } finally {
            onEdt(() -> {
                dialog.dispose();
                return null;
            });
        }
    }

    @Test
    void doesNotOpenWhenTheLoadFinishesDuringListenerRegistration() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        MekSummaryCache cache = mock(MekSummaryCache.class);
        when(cache.isInitialized()).thenReturn(false, true);
        assertDoesNotOpen(cache);
        verify(cache).removeListener(any());
    }

    @Test
    void doesNotOpenOrRegisterAListenerWhenCacheIsAlreadyLoaded() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        MekSummaryCache cache = mock(MekSummaryCache.class);
        when(cache.isInitialized()).thenReturn(true);
        assertDoesNotOpen(cache);
        verify(cache, never()).addListener(any());
    }

    private static void assertDoesNotOpen(MekSummaryCache cache) throws Exception {
        UnitLoadingDialog dialog = onEdt(() -> new UnitLoadingDialog(null, cache));
        try {
            onEdt(() -> {
                dialog.setVisible(true);
                assertFalse(dialog.isVisible());
                assertFalse(dialog.isDisplayable());
                return null;
            });
        } finally {
            onEdt(() -> {
                dialog.dispose();
                return null;
            });
        }
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}
