/*
 * Copyright (C) 2000-2005 Ben Mazur (bmazur@sev.org)
 * Copyright (C) 2013 Edward Cullen (eddy@obsessedcomputers.co.uk)
 * Copyright (C) 2002-2026 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.clientGUI;

import static megamek.common.compute.Compute.d6;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Vector;
import java.util.zip.GZIPInputStream;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.filechooser.FileFilter;

import jakarta.annotation.Nullable;
import megamek.MMConstants;
import megamek.MegaMek;
import megamek.SuiteConstants;
import megamek.Version;
import megamek.client.Client;
import megamek.client.IClient;
import megamek.client.SBFClient;
import megamek.client.bot.AIType;
import megamek.client.bot.BotClient;
import megamek.client.bot.BotFactory;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.ui.swing.BotGUI;
import megamek.client.ui.BugReportMessages;
import megamek.client.ui.CopySystemDataAction;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow;
import megamek.client.ui.clientGUI.tooltip.PilotToolTip;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Palette;
import megamek.client.ui.design.swing.TokenMotion;
import megamek.client.ui.dialogs.LicensingDialog;
import megamek.client.ui.dialogs.MMAboutDialog;
import megamek.client.ui.dialogs.ScenarioDialog;
import megamek.client.ui.dialogs.UnitLoadingDialog;
import megamek.client.ui.dialogs.buttonDialogs.BotConfigDialog;
import megamek.client.ui.dialogs.buttonDialogs.CommonSettingsDialog;
import megamek.client.ui.dialogs.buttonDialogs.GameOptionsDialog;
import megamek.client.ui.dialogs.buttonDialogs.NetworkInformationDialog;
import megamek.client.ui.dialogs.clientDialogs.PlanetaryConditionsDialog;
import megamek.client.ui.dialogs.gameConnectionDialogs.ConnectDialog;
import megamek.client.ui.dialogs.gameConnectionDialogs.HostDialog;
import megamek.client.ui.dialogs.helpDialogs.HelpDialog;
import megamek.client.ui.dialogs.helpDialogs.MMReadMeHelpDialog;
import megamek.client.ui.dialogs.randomArmy.AbstractRandomArmyDialog;
import megamek.client.ui.dialogs.randomArmy.MMMainMenuRandomArmyDialog;
import megamek.client.ui.dialogs.scenario.ScenarioChooserDialog;
import megamek.client.ui.dialogs.unitSelectorDialogs.MainMenuUnitBrowserDialog;
import megamek.client.ui.enums.DialogResult;
import megamek.client.ui.panels.skinEditor.SkinEditorMainGUIPanel;
import megamek.client.ui.util.MegaMekController;
import megamek.client.ui.util.UIUtil;
import megamek.codeUtilities.StringUtility;
import megamek.common.Configuration;
import megamek.common.KeyBindParser;
import megamek.common.Player;
import megamek.common.compute.Compute;
import megamek.common.equipment.SensorChoiceHandler;
import megamek.common.game.Game;
import megamek.common.game.GameType;
import megamek.common.game.IGame;
import megamek.common.interfaces.PlanetaryConditionsUsing;
import megamek.common.jacksonAdapters.BotParser;
import megamek.common.loaders.MekFileParser;
import megamek.common.loaders.MekSummaryCache;
import megamek.common.options.IBasicOption;
import megamek.common.options.IOption;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceChangeEvent;
import megamek.common.preference.PreferenceManager;
import megamek.common.scenario.Scenario;
import megamek.common.scenario.ScenarioLoader;
import megamek.common.util.EmailService;
import megamek.common.util.fileUtils.MegaMekFile;
import megamek.common.weapons.handlers.WeaponOrderHandler;
import megamek.logging.MMLogger;
import megamek.server.IGameManager;
import megamek.server.Server;
import megamek.server.sbf.SBFGameManager;
import megamek.server.totalWarfare.TWGameManager;
import megamek.utilities.xml.MMXMLUtility;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class MegaMekGUI implements IPreferenceChangeListener {
    private static final MMLogger LOGGER = MMLogger.create(MegaMekGUI.class);

    private static final String FILENAME_ICON_16X16 = "megamek-icon-16x16.png";
    private static final String FILENAME_ICON_32X32 = "megamek-icon-32x32.png";
    private static final String FILENAME_ICON_48X48 = "megamek-icon-48x48.png";
    private static final String FILENAME_ICON_256X256 = "megamek-icon-256x256.png";

    private JFrame frame;
    private IClient client;
    private Server server;
    private IGameManager gameManager;
    private CommonSettingsDialog settingsDialog;
    private AbstractRandomArmyDialog randomArmyDialog;
    private NetworkInformationDialog networkInformationDialog;

    private static MegaMekController controller;

    public void start(boolean show) {
        createGUI(show);
    }

    /**
     * Construct a MegaMek and, when {@code show}, open the start screen. The main frame itself stays hidden: it owns
     * the forms the start screen opens, and the start screen's native window adopts them.
     */
    private void createGUI(boolean show) {
        createController();

        GUIPreferences.getInstance().addPreferenceChangeListener(this);
        TokenMotion.setReduced(GUIPreferences.getInstance().getReduceMotion());
        applyPalette();

        // TODO : Move Theme setup to MegaMek::initializeSuiteSetups as part of implementing it in SuiteOptions
        updateGuiScaling();

        // TODO : Move ToolTip setup to MegaMek::initializeSuiteSetups as part of implementing them in SuiteOptions
        ToolTipManager.sharedInstance().setInitialDelay(GUIPreferences.getInstance().getTooltipDelay());
        if (GUIPreferences.getInstance().getTooltipDismissDelay() >= 0) {
            ToolTipManager.sharedInstance().setDismissDelay(GUIPreferences.getInstance().getTooltipDismissDelay());
        }

        frame = new JFrame("MegaMek");
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                quit();
            }
        });

        List<Image> iconList = new ArrayList<>();
        iconList.add(frame.getToolkit()
              .getImage(new MegaMekFile(Configuration.miscImagesDir(), FILENAME_ICON_16X16).toString()));
        iconList.add(frame.getToolkit()
              .getImage(new MegaMekFile(Configuration.miscImagesDir(), FILENAME_ICON_32X32).toString()));
        iconList.add(frame.getToolkit()
              .getImage(new MegaMekFile(Configuration.miscImagesDir(), FILENAME_ICON_48X48).toString()));
        iconList.add(frame.getToolkit()
              .getImage(new MegaMekFile(Configuration.miscImagesDir(), FILENAME_ICON_256X256).toString()));
        frame.setIconImages(iconList);

        installBugReportButtonOnErrorDialogs();
        GpuStartWindow.install(frame, this::perform, this::host);
        // init the cache
        MekSummaryCache.getInstance();

        if (show) {
            GpuStartWindow.open(true);
            // Show licensing/welcome dialog once the greeting has played
            GpuStartWindow.whenSettled(() -> LicensingDialog.showIfNeeded(frame));
        }
    }

    /**
     * Puts a "Report a Bug" button on every error dialog the program raises, including the one shown for an uncaught
     * exception.
     *
     * <p>A crash report is worth most at the moment it happens, while the player still knows what they were doing and
     * before the logs are overwritten by the next run. Offering the reporting tools on the dialog itself saves them
     * having to find the Help menu afterwards - and after an uncaught exception, the rest of the interface may not be
     * usable anyway.</p>
     *
     * <p>The current client is resolved when the button is pressed, so a crash during a game can still package the
     * game up, while a crash at the main menu quietly produces a logs-only archive.</p>
     */
    private void installBugReportButtonOnErrorDialogs() {
        MMLogger.setErrorDialogButton(new MMLogger.ErrorDialogButton(
              new BugReportMessages().get("package.reportBug"),
              Messages.getString("Okay"),
              () -> BugReportDialog.showWithGameTools(frame, this::currentTotalWarfareClient)));
    }

    /**
     * @return the running client when it is a Total Warfare client the bug report packager can save, otherwise
     *       {@code null}; other client types have no save the packager understands
     */
    private @Nullable Client currentTotalWarfareClient() {
        return (client instanceof Client totalWarfareClient) ? totalWarfareClient : null;
    }

    public void createController() {
        KeyboardFocusManager keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        // Remove any previously registered controller first. Each call to this method (e.g. once per MekHQ scenario)
        // otherwise adds another dispatcher to the global KeyboardFocusManager that is never removed, leaking
        // dispatchers that keep catching key events for games that have already ended. See issue #8888.
        if (controller != null) {
            keyboardFocusManager.removeKeyEventDispatcher(controller);
        }
        controller = new MegaMekController();
        controller.megaMekGUI = this;
        keyboardFocusManager.addKeyEventDispatcher(controller);
        KeyBindParser.parseKeyBindings(controller);
    }

    public static MegaMekController getKeyDispatcher() {
        return controller;
    }

    /**
     * Display the board editor.
     */
    void showEditor() {
        megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.openEditor(frame, null, false);
    }

    /**
     * Display the board editor and load the given board
     */
    void showEditor(String boardFile) {
        megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.openEditor(frame, new File(boardFile), false);
    }

    void showSkinEditor() {
        int response = JOptionPane.showConfirmDialog(frame,
              "The skin editor is currently " +
                    "in beta and is a work in progress.  There are likely to " +
                    "be issues. \nContinue?",
              "Continue?",
              JOptionPane.OK_CANCEL_OPTION);
        if (response == JOptionPane.CANCEL_OPTION) {
            return;
        }
        SkinEditorMainGUIPanel skinEditor = new SkinEditorMainGUIPanel();
        skinEditor.initialize();
        launch(skinEditor.getFrame());
    }

    /**
     * Display the board editor and open an "open" dialog.
     */
    void showEditorOpen() {
        megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.openEditor(frame, null, true);
    }

    /** Starts the server and the client for the game the start screen's Host page describes. */
    private void host(GpuStartWindow.HostRequest request) {
        if (server != null) {
            // A second click queued behind the first; that game is already starting
            return;
        }
        ClientPreferences preferences = PreferenceManager.getClientPreferences();
        preferences.setLastPlayerName(request.playerName());
        preferences.setLastConnectPort(request.port());
        preferences.setLastServerPass(request.password());
        preferences.setValue("megamek.megamek.metaservername", request.metaserver());
        startHost(request.password(),
              request.port(),
              request.register(),
              request.register() ? request.metaserver() : "",
              null,
              null,
              request.playerName());
    }

    public void startHost(@Nullable String serverPassword, int port, boolean isRegister, @Nullable String metaServer,
          @Nullable String mailPropertiesFileName, @Nullable File saveGame, String playerName) {
        if (!startServer(serverPassword, port, isRegister, metaServer, mailPropertiesFileName, saveGame)) {
            return;
        }

        startClient(playerName, MMConstants.LOCALHOST, server.getPort());
    }

    public boolean startServer(@Nullable String serverPassword, int port, boolean isRegister,
          @Nullable String metaServer, @Nullable String mailPropertiesFileName, @Nullable File saveGameFile) {
        return startServer(serverPassword,
              port,
              isRegister,
              metaServer,
              mailPropertiesFileName,
              saveGameFile,
              GameType.TW);
    }

    public boolean startServer(@Nullable String serverPassword, int port, boolean isRegister,
          @Nullable String metaServer, @Nullable String mailPropertiesFileName, @Nullable File saveGameFile,
          GameType gameType) {
        try {
            serverPassword = Server.validatePassword(serverPassword);
            port = Server.validatePort(port);
        } catch (Exception ex) {
            LOGGER.error("Failed to start Server", ex);
            GpuStartWindow.reopen();
            return false;
        }

        EmailService mailer = null;
        if (!StringUtility.isNullOrBlank(mailPropertiesFileName)) {
            File propsFile = new File(mailPropertiesFileName);
            try (var propsReader = new FileReader(propsFile)) {
                var mailProperties = new Properties();
                mailProperties.load(propsReader);
                mailer = new EmailService(mailProperties);
            } catch (Exception ex) {
                LOGGER.errorDialog(ex,
                      Messages.getFormattedString("MegaMek.StartServerError", port, ex.getMessage()),
                      Messages.getString("MegaMek.LoadGameAlert.title"));
                return false;
            }
        }

        // kick off a RNG check
        d6();

        // start server
        try {
            gameManager = getGameManager(gameType);
            server = new Server(serverPassword, port, gameManager, isRegister, metaServer, mailer, false);
        } catch (Exception ex) {
            LOGGER.errorDialog(ex,
                  Messages.getFormattedString("MegaMek.StartServerError", port, ex.getMessage()),
                  Messages.getString("MegaMek.LoadGameAlert.title"));
            return false;
        }

        if (saveGameFile != null && !server.loadGame(saveGameFile)) {
            LOGGER.errorDialog(Messages.getString("MegaMek.LoadGameAlert.title"),
                  Messages.getFormattedString("MegaMek.LoadGameAlert.message", saveGameFile.getAbsolutePath()));
            server.die();
            server = null;
            return false;
        }

        return true;
    }

    private IGameManager getGameManager(GameType gameType) {
        if (gameType == GameType.SBF) {
            return new SBFGameManager();
        }

        return new TWGameManager();
    }

    private IClientGUI getClientGUI(GameType gameType, IClient client, MegaMekController controller) {
        if (gameType == GameType.SBF) {
            return new SBFClientGUI((SBFClient) client, controller);
        }

        return new ClientGUI((Client) client, controller);
    }

    private IClient getClient(GameType gameType, String playerName, String host, int port) {
        if (gameType == GameType.SBF) {
            return new SBFClient(playerName, host, port);
        }

        return new Client(playerName, host, port);
    }

    public void startClient(String playerName, String serverAddress, int port) {
        startClient(playerName, serverAddress, port, GameType.TW);
    }

    public void startClient(String playerName, String serverAddress, int port, GameType gameType) {
        try {
            playerName = Server.validatePlayerName(playerName);
            serverAddress = Server.validateServerAddress(serverAddress);
            port = Server.validatePort(port);
        } catch (Exception ex) {
            LOGGER.errorDialog(ex,
                  Messages.getFormattedString("MegaMek.ServerConnectionError", serverAddress, port),
                  Messages.getString("MegaMek.LoadGameAlert.title"));
            return;
        }

        // delete PilotToolTip cache
        PilotToolTip.deleteImageCache();
        client = getClient(gameType, playerName, serverAddress, port);
        handOffStartBounds();
        IClientGUI gui = getClientGUI(gameType, client, controller);
        controller.clientGUI = gui;
        frame.setCursor(new Cursor(Cursor.WAIT_CURSOR));
        gui.initialize();
        AbstractClientGUI.setOpenMaximized(false);
        frame.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
        if (!client.connect()) {
            LOGGER.errorDialog(Messages.getString("MegaMek.LoadGameAlert.title"),
                  Messages.getFormattedString("MegaMek.ServerConnectionError", serverAddress, port));
            client.die();
            gui.die();
            return;
        }

        // free some memory that's only needed in lounge This normally happens in the deployment phase in Client, but
        // if we are loading a game, this phase may not be reached
        MekFileParser.dispose();
        // We must do this last, as the name and unit generators can create a new instance if they are running
        MekSummaryCache.dispose();

        launch(gui.getFrame());
    }

    /**
     * Opens the next client window where the start screen was, so going from the start screen to the lobby doesn't
     * move or resize the window under the player.
     */
    private static void handOffStartBounds() {
        Rectangle bounds = GpuStartWindow.lastSwingBounds();
        if (bounds != null) {
            GUIPreferences guip = GUIPreferences.getInstance();
            guip.setWindowPosX(bounds.x);
            guip.setWindowPosY(bounds.y);
            guip.setWindowSizeWidth(bounds.width);
            guip.setWindowSizeHeight(bounds.height);
        }
        AbstractClientGUI.setOpenMaximized(GpuStartWindow.lastMaximized());
    }

    void loadGame() {
        JFileChooser fc = new JFileChooser(MMConstants.SAVEGAME_DIR);
        fc.setLocation(frame.getLocation().x + 150, frame.getLocation().y + 100);
        fc.setDialogTitle(Messages.getString("MegaMek.SaveGameDialog.title"));
        fc.setFileFilter(new FileFilter() {
            @Override
            public boolean accept(File dir) {
                return dir.getName().endsWith(MMConstants.SAVE_FILE_EXT) ||
                      dir.getName().endsWith(MMConstants.SAVE_FILE_GZ_EXT) ||
                      dir.isDirectory();
            }

            @Override
            public String getDescription() {
                return "Savegames";
            }
        });
        int returnVal = fc.showOpenDialog(frame);
        if ((returnVal != JFileChooser.APPROVE_OPTION) || (fc.getSelectedFile() == null)) {
            return;
        }

        final Vector<String> playerNames = new Vector<>();

        // Hand rolled extraction, as we require Server initialization to use XStream and don't need the additional
        // overhead of initializing everything twice
        try (InputStream is = new FileInputStream(fc.getSelectedFile())) {
            InputStream gzi;

            if (fc.getSelectedFile().getName().toLowerCase().endsWith(".gz")) {
                gzi = new GZIPInputStream(is);
            } else {
                gzi = is;
            }

            // Parse to a DOM representation, raising the JAXP entity size limit if a large save exceeds it
            final Document xmlDocument = MMXMLUtility.parseDocument(gzi);

            final Element gameElement = xmlDocument.getDocumentElement();
            gameElement.normalize();

            final NodeList nl = gameElement.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                final Node n = nl.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }

                switch (n.getNodeName()) {
                    case "version":
                        if (!validateSaveVersion(n)) {
                            return;
                        }
                        break;
                    case "players":
                        parsePlayerNames(n, playerNames);
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception ex) {
            LOGGER.errorDialog(ex,
                  Messages.getFormattedString("MegaMek.LoadGameAlert.message", fc.getSelectedFile().getAbsolutePath()),
                  Messages.getString("MegaMek.LoadGameAlert.title"));
            return;
        }

        HostDialog hd = new HostDialog(frame, playerNames);
        hd.setVisible(true);

        if (!hd.dataValidation("MegaMek.LoadGameAlert.title")) {
            return;
        }

        startHost(hd.getServerPass(),
              hd.getPort(),
              hd.isRegister(),
              hd.isRegister() ? hd.getMetaserver() : "",
              null,
              fc.getSelectedFile(),
              hd.getPlayerName());
    }

    private boolean validateSaveVersion(final Node n) {
        String ignoreVersionValidation = System.getenv("IGNORE_VERSION_VALIDATION");
        if (ignoreVersionValidation != null && ignoreVersionValidation.equalsIgnoreCase("true")) {
            return true;
        }

        if (!n.hasChildNodes()) {
            final String message = String.format(Messages.getString("MegaMek.LoadGameMissingVersion.message"),
                  SuiteConstants.VERSION);
            JOptionPane.showMessageDialog(frame,
                  message,
                  Messages.getString("MegaMek.LoadGameAlert.title"),
                  JOptionPane.ERROR_MESSAGE);
            LOGGER.error(message);
            return false;
        }

        final Version version = getVersion(n);
        if (SuiteConstants.VERSION.is(version)) {
            return true;
        } else if (version.toString().toLowerCase().contains("nightly") &&
              (version.getMajor() == SuiteConstants.VERSION.getMajor() &&
                    version.getMinor() == SuiteConstants.VERSION.getMinor() &&
                    version.getPatch() == SuiteConstants.VERSION.getPatch())
              && version.toString().contains(SuiteConstants.VERSION.toString())) {
            // Nightly version of current development version
            return true;
        } else {
            LOGGER.errorDialog(Messages.getString("MegaMek.LoadGameAlert.title"),
                  Messages.getString("MegaMek.LoadGameIncorrectVersion.message"),
                  version,
                  SuiteConstants.VERSION);
            return false;
        }
    }

    /**
     * Rebuilds the {@link Version} a save game was written by, from the components stored in its version node.
     *
     * @param versionNode the save game's version node
     *
     * @return the version the save game was written by
     */
    private static Version getVersion(Node versionNode) {
        final NodeList versionChildren = versionNode.getChildNodes();
        String major = null;
        String minor = null;
        String patch = null;
        String revision = null;
        String extra = null;
        for (int childIndex = 0; childIndex < versionChildren.getLength(); childIndex++) {
            final Node versionComponent = versionChildren.item(childIndex);
            if (versionComponent.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            switch (versionComponent.getNodeName()) {
                case "major":
                    major = versionComponent.getTextContent();
                    break;
                case "minor":
                    minor = versionComponent.getTextContent();
                    break;
                case "patch":
                    patch = versionComponent.getTextContent();
                    break;
                // Absent from saves written by an ordinary release, and by every release before the revision
                // component existed.
                case "revision":
                    revision = versionComponent.getTextContent();
                    break;
                case "snapshot":
                case "extra":
                    extra = versionComponent.getTextContent();
                    break;
                default:
                    break;
            }
        }

        return new Version(major, minor, patch, revision, extra);
    }

    private void parsePlayerNames(final Node nodePlayers, final Vector<String> playerNames) {
        if (!nodePlayers.hasChildNodes()) {
            return;
        }

        final NodeList nodePlayersChildren = nodePlayers.getChildNodes();
        for (int i = 0; i < nodePlayersChildren.getLength(); i++) {
            final Node nodeEntry = nodePlayersChildren.item(i);
            if ((nodeEntry.getNodeType() != Node.ELEMENT_NODE) ||
                  !nodeEntry.hasChildNodes() ||
                  !"entry".equals(nodeEntry.getNodeName())) {
                continue;
            }

            final NodeList nodeEntryChildren = nodeEntry.getChildNodes();
            for (int k = 0; k < nodeEntryChildren.getLength(); k++) {
                final Node nodePlayerClass = nodeEntryChildren.item(k);
                if ((nodePlayerClass.getNodeType() != Node.ELEMENT_NODE) ||
                      !nodePlayerClass.hasChildNodes() ||
                      !Player.class.getName().equals(nodePlayerClass.getNodeName())) {
                    continue;
                }

                final NodeList nodePlayerClassChildren = nodePlayerClass.getChildNodes();
                for (int j = 0; j < nodePlayerClassChildren.getLength(); j++) {
                    final Node n3 = nodePlayerClassChildren.item(j);
                    if (n3.getNodeType() != Node.ELEMENT_NODE) {
                        continue;
                    }

                    if ("name".equals(n3.getNodeName())) {
                        playerNames.add(n3.getTextContent());
                    }
                }
            }
        }
    }

    /**
     * Developer Utility: Loads "quicksave.sav.gz" with the last used connection settings.
     */
    public void quickLoadGame() {
        File file = MegaMek.getQuickSaveFile();
        if (!file.exists() || !file.canRead()) {
            JOptionPane.showMessageDialog(frame,
                  Messages.getFormattedString("MegaMek.LoadGameAlert.message", file.getAbsolutePath()),
                  Messages.getString("MegaMek.LoadGameAlert.title"),
                  JOptionPane.ERROR_MESSAGE);
            GpuStartWindow.reopen();
            return;
        }

        startHost("", 0, false, "", null, file, PreferenceManager.getClientPreferences().getLastPlayerName());
    }

    /**
     * Loads a specific save game file without showing the HostDialog. Used when loading from the lobby where server
     * settings are not needed.
     *
     * @param file The save game file to load
     */
    public void loadGameFile(File file) {
        if (file == null || !file.exists() || !file.canRead()) {
            JOptionPane.showMessageDialog(frame,
                  Messages.getFormattedString("MegaMek.LoadGameAlert.message",
                        file != null ? file.getAbsolutePath() : "null"),
                  Messages.getString("MegaMek.LoadGameAlert.title"),
                  JOptionPane.ERROR_MESSAGE);
            GpuStartWindow.reopen();
            return;
        }

        startHost("", 0, false, "", null, file, PreferenceManager.getClientPreferences().getLastPlayerName());
    }

    /**
     * Host a game constructed from a scenario file
     */
    void scenario(String fileName) {
        String chosenFileName;
        if (fileName.isBlank()) {
            ScenarioChooserDialog scenarioChooser = new ScenarioChooserDialog(frame);
            scenarioChooser.setVisible(true);
            chosenFileName = scenarioChooser.getSelectedScenarioFilename();
            if (chosenFileName == null) {
                return;
            }
            PreferenceManager.getClientPreferences().setLastScenario(chosenFileName);
        } else {
            chosenFileName = fileName;
        }

        Scenario scenario;
        IGame game;
        try {
            ScenarioLoader sl = new ScenarioLoader(new File(chosenFileName));
            scenario = sl.load();
            game = scenario.createGame();
        } catch (Exception e) {
            LOGGER.errorDialog(e,
                  Messages.getString("MegaMek.HostScenarioAlert.message", e.getMessage()),
                  Messages.getString("MegaMek.HostScenarioAlert.title"));
            return;
        }

        // popup options dialog
        if (!scenario.hasFixedGameOptions() && game instanceof Game twGame) {
            GameOptionsDialog god = new GameOptionsDialog(frame, twGame.getOptions(), false);
            god.update(twGame.getOptions());
            god.setEditable(true);
            // the options the scenario calls its mission stay as written; the rest are the player's
            god.lockOptions(scenario.lockedGameOptions());
            god.setVisible(true);
            for (IBasicOption opt : god.getOptions()) {
                IOption orig = game.getOptions().getOption(opt.getName());
                orig.setValue(opt.getValue());
            }
        }

        // popup planetary conditions dialog
        if ((game instanceof PlanetaryConditionsUsing plGame) && !scenario.hasFixedPlanetaryConditions()) {
            PlanetaryConditionsDialog pcd = new PlanetaryConditionsDialog(frame, plGame.getPlanetaryConditions());
            pcd.update(plGame.getPlanetaryConditions());
            pcd.setVisible(true);
            plGame.setPlanetaryConditions(pcd.getConditions());
        }

        int port = MMConstants.DEFAULT_PORT;
        String serverPW = "";
        Player[] pa = game.getPlayersList().toArray(new Player[0]);
        int[] playerTypes = new int[pa.length];
        String playerName = pa[0].getName();
        String localName = playerName;
        boolean hasSlot = scenario.isSinglePlayer();

        // get player types and colors set
        if (!scenario.isSinglePlayer()) {
            ScenarioDialog sd = new ScenarioDialog(frame, pa);
            sd.setVisible(true);
            if (!sd.bSet) {
                return;
            }

            HostDialog hd = new HostDialog(frame);
            if (!sd.localName.isEmpty()) {
                hasSlot = true;
            }
            hd.setPlayerName(sd.localName);
            hd.setVisible(true);

            if (!hd.dataValidation("MegaMek.HostScenarioAlert.title")) {
                return;
            }

            sd.localName = hd.getPlayerName();
            localName = hd.getPlayerName();
            playerName = hd.getPlayerName();
            port = hd.getPort();
            serverPW = hd.getServerPass();
            playerTypes = Arrays.copyOf(sd.playerTypes, playerTypes.length);

        } else {
            playerTypes[0] = 0;
            for (int i = 1; i < playerTypes.length; i++) {
                playerTypes[i] = ScenarioDialog.T_BOT;
            }
        }

        // kick off a RNG check
        Compute.d6();

        // start server
        if (!startServer(serverPW, port, false, null, null, null, scenario.getGameType())) {
            return;
        }
        server.setGame(game);
        scenario.applyDamage(gameManager);

        if (!localName.isBlank()) {
            startClient(playerName, MMConstants.LOCALHOST, port, scenario.getGameType());
        }

        gameManager.calculatePlayerInitialCounts();

        // Setup bots; currently, we have no bot that supports anything other than TW
        if (scenario.getGameType() == GameType.TW) {
            for (int x = 0; x < pa.length; x++) {
                if (playerTypes[x] == ScenarioDialog.T_BOT) {
                    AIType aiType = AIType.PRINCESS;
                    BehaviorSettings scenarioBehavior = null;
                    if (scenario.hasBotInfo(pa[x].getName()) &&
                          scenario.getBotInfo(pa[x].getName()) instanceof BotParser.PrincessRecord(
                                AIType recordAIType, BehaviorSettings behaviorSettings
                          )) {
                        aiType = recordAIType;
                        scenarioBehavior = behaviorSettings;
                    }
                    LOGGER.info("Adding bot {} as {}", pa[x].getName(), aiType);
                    BotClient botClient = BotFactory.createBot(aiType, pa[x].getName(), MMConstants.LOCALHOST, port);
                    if (scenarioBehavior != null) {
                        botClient.setBehaviorSettings(scenarioBehavior);
                    }
                    botClient.getGame().addGameListener(new BotGUI(frame, botClient));
                    if (!botClient.connect()) {
                        LOGGER.error("Bot {} failed to connect; shutting it down", pa[x].getName());
                        botClient.die();
                    }
                }
            }
        }

        // If he didn't have a name when hasSlot was set, then the host should be an
        // observer.
        if (!hasSlot) {
            for (Player player : server.getGame().getPlayersList()) {
                if (player.getName().equals(localName)) {
                    player.setObserver(true);
                }
            }
        }
    }

    /**
     * Connect to an existing game
     */
    void connect() {
        var cd = new ConnectDialog(frame);
        cd.setVisible(true);
        if (cd.isConfirmed() && cd.dataValidation("MegaMek.ConnectDialog.title")) {
            startClient(cd.getPlayerName(), cd.getServerAddress(), cd.getPort());
        }
    }

    /**
     * Connect to an existing game
     */
    void connectSbf() {
        var cd = new ConnectDialog(frame);
        cd.setVisible(true);
        if (cd.isConfirmed() && cd.dataValidation("MegaMek.ConnectDialog.title")) {
            startClient(cd.getPlayerName(), cd.getServerAddress(), cd.getPort(), GameType.SBF);
        }
    }

    /**
     * Connect to a game as Princess
     */
    void connectBot() {
        var cd = new ConnectDialog(frame);
        cd.setVisible(true);
        if (!cd.isConfirmed() || !cd.dataValidation("MegaMek.ConnectDialog.title")) {
            return;
        }

        // initialize game
        var bcd = new BotConfigDialog(frame, cd.getPlayerName());
        bcd.setVisible(true);
        if (bcd.getResult() == DialogResult.CANCELLED) {
            return;
        }
        client = BotFactory.createBot(bcd.getSelectedAIType(),
              bcd.getBotName(),
              cd.getServerAddress(),
              cd.getPort(),
              bcd.getBehaviorSettings());
        client.getGame().addGameListener(new BotGUI(frame, (BotClient) client));
        handOffStartBounds();
        ClientGUI gui = new ClientGUI((Client) client, controller);
        controller.clientGUI = gui;
        gui.initialize();
        AbstractClientGUI.setOpenMaximized(false);
        if (!client.connect()) {
            JOptionPane.showMessageDialog(frame,
                  Messages.getFormattedString("MegaMek.ServerConnectionError", cd.getServerAddress(), cd.getPort()),
                  Messages.getString("MegaMek.ConnectDialog.title"),
                  JOptionPane.ERROR_MESSAGE);
            client.die();
        }
        launch(gui.getFrame());
    }

    private void showSkinningHowTo() {
        try {
            // Get the correct help file.
            StringBuilder helpPath = new StringBuilder("file:///");
            helpPath.append(System.getProperty("user.dir"));
            if (!helpPath.toString().endsWith(File.separator)) {
                helpPath.append(File.separator);
            }
            helpPath.append(Messages.getString("ClientGUI.skinningHelpPath"));
            URL helpUrl = new URL(helpPath.toString());

            // Launch the help dialog.
            HelpDialog helpDialog = new HelpDialog(Messages.getString("ClientGUI.skinningHelpPath.title"),
                  helpUrl,
                  frame);
            helpDialog.setVisible(true);
        } catch (MalformedURLException e) {
            LOGGER.error(e);
        }
    }

    /**
     * Called when the user selects the "View->Client Settings" menu item.
     */
    void showSettings() {
        // Do we need to create the "settings" dialog?
        if (settingsDialog == null) {
            settingsDialog = new CommonSettingsDialog(frame);
        }

        // Show the settings dialog.
        settingsDialog.setVisible(true);
    }

    /**
     * Called when the quit buttons is pressed or the main menu is closed.
     */
    static void quit() {
        MegaMek.getMMPreferences().saveToFile(SuiteConstants.MM_PREFERENCES_FILE);
        PreferenceManager.getInstance().save();

        try {
            WeaponOrderHandler.saveWeaponOrderFile();
        } catch (IOException e) {
            LOGGER.error(e, "Error saving custom weapon orders!");
        }

        try {
            SensorChoiceHandler.saveSensorChoiceFile();
        } catch (IOException exception) {
            LOGGER.error(exception, "Error saving custom sensor choices!");
        }
        System.exit(0);
    }

    /**
     * Hides this window for later. Listens to the frame until it closes, then calls unlaunch().
     */
    private void launch(JFrame launched) {
        // clean up disposable things
        if (randomArmyDialog != null) {
            randomArmyDialog.dispose();
            randomArmyDialog = null;
        }
        // listen to new frame
        launched.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                unlaunch();
            }
        });
        // close the start screen; it reopens when the launched window closes
        GpuStartWindow.closeThen(() -> { });
    }

    /**
     * Un-hides the main menu and tries to clean up the client or server.
     */
    void unlaunch() {
        // clean up server, if we have one
        if (server != null) {
            server.die();
            server = null;
        }
        // show the start screen again, without its greeting
        GpuStartWindow.reopen();

        // Check for post-unlaunch action (e.g., load game from lobby)
        Runnable postAction = controller.consumePostUnlaunchAction();
        if (postAction != null) {
            SwingUtilities.invokeLater(postAction);
        }

        // on Mac, override auto-added "Quit MM" behavior; in the main menu, it can simply quit
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            Desktop.getDesktop().setQuitHandler(null);
        }

        // just to free some memory
        client = null;
        System.gc();
    }

    /** Carries out what the player picked on the start screen. Runs on the event dispatch thread. */
    private void perform(GpuStartWindow.Action action) {
        switch (action) {
            case LOAD -> loadGame();
            case QUICK_LOAD -> quickLoadGame();
            case SCENARIO -> scenario("");
            // As a dev convenience, start the last scenario again
            case LAST_SCENARIO -> scenario(PreferenceManager.getClientPreferences().getLastScenario());
            case CONNECT -> connect();
            case CONNECT_BOT -> connectBot();
            case MAP_EDITOR -> showEditor();
            case OPEN_MAP -> showEditorOpen();
            case SKIN_EDITOR -> showSkinEditor();
            case UNIT_BROWSER -> {
                UnitLoadingDialog unitLoadingDialog = new UnitLoadingDialog(frame);
                if (!MekSummaryCache.getInstance().isInitialized()) {
                    unitLoadingDialog.setVisible(true);
                }
                MainMenuUnitBrowserDialog unitSelectorDialog = new MainMenuUnitBrowserDialog(frame, unitLoadingDialog);
                new Thread(unitSelectorDialog, "Mek Selector Dialog").start();
                unitSelectorDialog.setVisible(true);
            }
            case RANDOM_ARMY -> {
                if (randomArmyDialog == null) {
                    randomArmyDialog = new MMMainMenuRandomArmyDialog(frame);
                }
                randomArmyDialog.setVisible(true);
            }
            case SETTINGS -> showSettings();
            case HELP_CONTENTS -> new MMReadMeHelpDialog(frame).setVisible(true);
            case HELP_SKINNING -> showSkinningHowTo();
            case REPORT_BUG -> BugReportDialog.showWithGameTools(frame, this::currentTotalWarfareClient);
            case COPY_SYSTEM_DATA -> new CopySystemDataAction().actionPerformed(null);
            case RESET_PROMPTS -> MegaMek.getMMOptions().setNagDialogIgnore(MMConstants.NAG_BOT_README, false);
            case ABOUT -> new MMAboutDialog(frame).show();
            case QUIT -> quit();
        }
    }

    private void showNetworkInformation() {
        // Display the network information screen
        getNetworkInformationDialog().refresh();
        getNetworkInformationDialog().pack();
        getNetworkInformationDialog().setVisible(true);
    }

    public NetworkInformationDialog getNetworkInformationDialog() {
        if (networkInformationDialog == null) {
            networkInformationDialog = new NetworkInformationDialog(this.frame);
        }
        return networkInformationDialog;
    }

    @Override
    public void preferenceChange(PreferenceChangeEvent evt) {
        switch (evt.getName()) {
            case GUIPreferences.UI_THEME:
                setLookAndFeel();
                break;
            case GUIPreferences.GUI_SCALE:
                updateGuiScaling();
                GpuStartWindow.refresh();
                break;
            case GUIPreferences.REDUCE_MOTION:
                TokenMotion.setReduced(GUIPreferences.getInstance().getReduceMotion());
                break;
            case GUIPreferences.COLOR_BLIND_PALETTE:
                applyPalette();
                setLookAndFeel();
                break;
        }
    }

    /** Sets the design tokens' palette from the color vision setting; the look and feel picks it up when set. */
    private static void applyPalette() {
        DesignTokens.setPalette(GUIPreferences.getInstance().getColorBlindPalette() ? Palette.COLORBLIND
              : Palette.STANDARD);
    }

    public static void updateGuiScaling() {
        System.setProperty("flatlaf.uiScale", Double.toString(GUIPreferences.getInstance().getGUIScale()));
        setLookAndFeel();
    }

    private static void setLookAndFeel() {
        try {
            UIManager.setLookAndFeel(GUIPreferences.getInstance().getUITheme());
            UIUtil.updateAfterUiChange();
        } catch (Exception ex) {
            LOGGER.error("setLookAndFeel() Exception", ex);
        }
    }
}
