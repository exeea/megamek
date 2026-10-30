/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static com.badlogic.gdx.scenes.scene2d.actions.Actions.forever;
import static com.badlogic.gdx.scenes.scene2d.actions.Actions.run;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import megamek.client.bot.AIType;
import megamek.client.bot.princess.CardinalEdge;
import megamek.client.bot.princess.CombatPosture;
import megamek.client.lobby.BotSetupForm;
import megamek.client.lobby.BotSetupForm.Flag;
import megamek.client.lobby.BotSetupForm.HexTarget;
import megamek.client.lobby.BotSetupForm.Preset;
import megamek.client.lobby.BotSetupForm.PresetKind;
import megamek.client.lobby.BotTargetPicker;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiFormField;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiSliderInput;
import megamek.client.ui.gdx.UiTabs;
import megamek.client.ui.gdx.UiTextField;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.gdx.UiTree;

/**
 * The lobby's bot setup in a {@link UiDialog}, the Swing {@code BotConfigDialog}'s settings on two tabs. Above them
 * the bot's name, for a new bot only, and its AI, locked for an existing bot. Behaviour: the presets and
 * configurations list with Save, Save as new and Delete preset, the sliders, the combat posture and switches, and the
 * retreat options (forced withdrawal and auto flee, each with its edge). Strategic targets: the priority units picked
 * by owner and force, and the hex targets, typed or chosen from the board's buildings. The values are held by a
 * {@link BotSetupForm}; OK stays disabled while the name is wrong.
 * <p>
 * GPU thread. The preset file is written on the Swing thread through {@link Host#edt}.
 */
final class LobbyBotSetupView {
    /** What the view asks of the window. */
    interface Host {
        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);

        /** Opens the Princess help. */
        void help();

        /** Takes the confirmed setup; the dialog closes. */
        void confirm(LobbyPlayerController.BotSetup setup);
    }

    /** Below this body width the presets list goes above the sliders. */
    private static final float STACK_BELOW = 600;

    private final UiKit kit;
    private final UiDialog dialog;
    private final BotSetupForm form;
    private final Host host;
    private final Runnable close;
    private final boolean stacked;
    private final Map<String, Preset> presetIds = new HashMap<>();
    private final Map<BotSetupForm.Slider, Slider> sliders = new EnumMap<>(BotSetupForm.Slider.class);
    private final Map<BotSetupForm.Slider, Label> sliderTitles = new EnumMap<>(BotSetupForm.Slider.class);
    private final Map<Flag, UiChoice> flags = new EnumMap<>(Flag.class);
    private final Container<Table> page = new Container<>();
    private final Table pickerList = new Table();
    private final Table hexList = new Table();
    private final UiButton ok;
    private UiFormField nameField;
    private UiTabs tabs;
    private Table behaviorPage;
    private Table targetsPage;
    private UiTree presetTree;
    private UiButton save;
    private UiButton saveNew;
    private UiButton delete;
    private UiTextField newName;
    private UiFormField newNameField;
    private UiSelect posture;
    private UiSelect retreatEdge;
    private UiSelect fleeEdge;
    private Label pickerCount;
    private UiFormField hexField;
    private UiTextField hexInput;
    private boolean busy;
    private boolean pickerDue;

    /**
     * Fills {@code dialog}'s body and footer.
     *
     * @param bodyWidth the width the body will have in this window
     * @param close     closes the dialog
     */
    LobbyBotSetupView(UiKit kit, UiDialog dialog, BotSetupForm form, float bodyWidth, Host host, Runnable close) {
        this.kit = kit;
        this.dialog = dialog;
        this.form = form;
        this.host = host;
        this.close = close;
        stacked = bodyWidth < STACK_BELOW;
        Table body = dialog.body();
        body.defaults().left().growX();

        body.add(header()).row();
        tabs = kit.tokenTabs(true, Messages.getString("BotConfigDialog.behaviorSection"),
              Messages.getString("BotConfigDialog.targetsSection"));
        tabs.setName("bot-tabs");
        tabs.option(0).setName("bot-tab-behavior");
        tabs.option(1).setName("bot-tab-targets");
        UiKit.onChange(tabs, this::showPage);
        body.add(tabs).fill(false, false).padTop(space("lg")).row();
        behaviorPage = behavior();
        targetsPage = targets();
        page.fill().align(Align.topLeft);
        body.add(page).growX().padTop(space("md")).row();

        ok = dialog.primary(Messages.getString("Ok.text"), this::confirm);
        ok.setName("bot-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("bot-cancel");
        UiButton help = dialog.tool(Messages.getString("BotConfigDialog.help"), host::help);
        help.setName("bot-help");

        showPage();
        showValues();
        refresh();
        // An actor of its own: the layer's actions are its fade, which showing it clears
        Actor pump = new Actor();
        pump.addAction(forever(run(this::drain)));
        dialog.layer().addActor(pump);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static float size(String name) {
        return DesignTokens.shipped().size().get(name);
    }

    private static String colonless(String key) {
        String text = Messages.getString(key).strip();
        return text.endsWith(":") ? text.substring(0, text.length() - 1) : text;
    }

    private void tip(Actor actor, String text) {
        kit.tip(actor, true).getActor().setText(GpuBoardWindow.plainText(text));
    }

    // ---- Name and AI ----

    private Table header() {
        Table header = new Table();
        header.top().left();
        if (form.newBot()) {
            UiTextField name = kit.input(form.name(), true);
            name.setName("bot-name");
            UiKit.onChange(name, () -> {
                form.name(name.getText());
                refresh();
            });
            nameField = kit.formField(Messages.getString("Lobby.bot.name"), name);
            header.add(nameField).growX().uniformX().top();
        } else {
            Table named = new Table();
            named.left();
            named.add(kit.typeLabel(form.name(), "title", "text.primary")).left();
            header.add(named).growX().uniformX().top().left();
        }
        Table types = new Table();
        types.top().left().defaults().left();
        types.add(kit.typeLabel(colonless("BotConfigDialog.aiTypeLabel"), "small", "text.secondary")).row();
        Table radios = new Table();
        radios.left();
        List<UiChoice> choices = new ArrayList<>();
        for (AIType type : form.types()) {
            UiChoice radio = kit.radio(Messages.getString("BotConfigDialog.aiType." + type.name()),
                  type == form.type());
            radio.setName("bot-ai-" + type.name());
            if (form.typeLocked()) {
                radio.setDisabled(true);
                tip(radio, Messages.getString("BotConfigDialog.aiTypeLocked.tooltip"));
            } else if (Messages.keyExists("BotConfigDialog.aiType." + type.name() + ".tooltip")) {
                tip(radio, Messages.getString("BotConfigDialog.aiType." + type.name() + ".tooltip"));
            }
            UiKit.onChange(radio, () -> {
                if (radio.isChecked()) {
                    form.type(type);
                }
            });
            radios.add(radio).padRight(space("lg"));
            choices.add(radio);
        }
        kit.radioGroup(choices.toArray(UiChoice[]::new));
        types.add(radios).padTop(space("xs")).row();
        header.add(types).growX().uniformX().top().padLeft(space("md"));
        return header;
    }

    private void showPage() {
        page.setActor(tabs.getSelectedIndex() == 0 ? behaviorPage : targetsPage);
    }

    // ---- Behaviour ----

    private Table behavior() {
        Table content = new Table();
        content.top().left();
        Table presets = presets();
        Table settings = new Table();
        settings.top().left().defaults().left().growX();
        List<BotSetupForm.Slider> shown = form.sliders();
        for (int index = 0; index < shown.size(); index += 2) {
            Table first = slider(shown.get(index));
            Table second = index + 1 < shown.size() ? slider(shown.get(index + 1)) : null;
            Table row = new Table();
            row.top().left();
            row.add(first).growX().uniformX().top();
            if (second != null) {
                row.add(second).growX().uniformX().top().padLeft(space("lg"));
            } else {
                row.add().growX().uniformX();
            }
            settings.add(row).padTop(index == 0 ? 0 : space("md")).row();
        }
        if (stacked) {
            content.add(presets).growX().row();
            content.add(settings).growX().padTop(space("lg")).row();
        } else {
            content.add(presets).width(size("dialog.sm") / 2).top();
            content.add(settings).growX().top().padLeft(space("lg")).row();
        }

        CombatPosture[] postures = CombatPosture.values();
        posture = kit.tokenSelect(true, names(postures));
        posture.setName("bot-posture");
        tip(posture, Messages.getString("BotConfigDialog.postureTooltip"));
        UiKit.onChange(posture, () -> {
            form.posture(postures[posture.getSelectedIndex()]);
            refresh();
        });
        Table switches = new Table();
        switches.top().left().defaults().left();
        switches.add(kit.formField(colonless("BotConfigDialog.postureLabel"), posture)).row();
        Table checks = new Table();
        checks.left().defaults().left();
        List<Flag> presetFlags = form.presetFlags();
        for (int index = 0; index < presetFlags.size(); index++) {
            checks.add(flag(presetFlags.get(index))).padRight(space("lg")).padTop(space("xs"));
            if (index % 2 == 1) {
                checks.row();
            }
        }
        switches.add(checks).padTop(space("sm")).row();
        content.add(switches).colspan(2).growX().padTop(space("lg")).row();

        content.add(kit.caption(Messages.getString("BotConfigDialog.retreatSection"), true)).colspan(2).left()
              .padTop(space("xl")).row();
        retreatEdge = edgeSelect("bot-retreat-edge", "BotConfigDialog.retreatEdgeTooltip",
              edge -> form.retreatEdge(edge));
        fleeEdge = edgeSelect("bot-flee-edge", "BotConfigDialog.homeEdgeTooltip", edge -> form.fleeEdge(edge));
        Table retreat = new Table();
        retreat.left().defaults().left();
        retreat.add(flag(Flag.FORCED_WITHDRAWAL));
        retreat.add(kit.typeLabel(Messages.getString("BotConfigDialog.retreatEdgeLabel"), "body", "text.secondary"))
              .padLeft(space("md"));
        retreat.add(retreatEdge).padLeft(space("sm")).row();
        retreat.add(flag(Flag.AUTO_FLEE)).padTop(space("sm"));
        retreat.add(kit.typeLabel(Messages.getString("BotConfigDialog.homeEdgeLabel"), "body", "text.secondary"))
              .padLeft(space("md")).padTop(space("sm"));
        retreat.add(fleeEdge).padLeft(space("sm")).padTop(space("sm")).row();
        content.add(retreat).colspan(2).left().padTop(space("sm")).row();
        return content;
    }

    private UiSelect edgeSelect(String name, String tipKey, java.util.function.Consumer<CardinalEdge> chosen) {
        List<CardinalEdge> edges = BotSetupForm.edges();
        UiSelect select = kit.tokenSelect(true, names(edges.toArray()));
        select.setName(name);
        tip(select, Messages.getString(tipKey));
        UiKit.onChange(select, () -> {
            chosen.accept(edges.get(select.getSelectedIndex()));
            refresh();
        });
        return select;
    }

    private static String[] names(Object[] values) {
        String[] names = new String[values.length];
        for (int index = 0; index < values.length; index++) {
            names[index] = String.valueOf(values[index]);
        }
        return names;
    }

    private UiChoice flag(Flag flag) {
        UiChoice check = kit.check(flag.label(), form.flag(flag));
        check.setName("bot-flag-" + flag.name().toLowerCase(java.util.Locale.ROOT));
        tip(check, flag.tip());
        UiKit.onChange(check, () -> {
            form.flag(flag, check.isChecked());
            refresh();
        });
        flags.put(flag, check);
        return check;
    }

    private Table slider(BotSetupForm.Slider kind) {
        Slider slider = new Slider(kind.min(), kind.max(), 1, false, kit.tokenSliderStyle());
        slider.setName("bot-slider-" + kind.name().toLowerCase(java.util.Locale.ROOT));
        slider.setProgrammaticChangeEvents(false);
        slider.addListener(new UiSliderInput(slider));
        Label title = kit.typeLabel(kind.title(form.value(kind)), "small", "text.secondary");
        UiKit.onChange(slider, () -> {
            form.value(kind, Math.round(slider.getValue()));
            title.setText(kind.title(form.value(kind)));
            refresh();
        });
        tip(slider, kind.tip());
        tip(title, kind.tip());
        sliders.put(kind, slider);
        sliderTitles.put(kind, title);
        Table row = new Table();
        row.top().left();
        row.add(title).left().colspan(2).row();
        row.add(slider).growX().colspan(2).padTop(space("xs")).row();
        Label min = kit.typeLabel(kind.minText(), "small", "text.muted");
        Label max = kit.typeLabel(kind.maxText(), "small", "text.muted");
        min.setEllipsis(true);
        max.setEllipsis(true);
        max.setAlignment(Align.right);
        row.add(min).left().growX().uniformX().minWidth(0);
        row.add(max).right().growX().uniformX().minWidth(0).padLeft(space("sm"));
        return row;
    }

    private Table presets() {
        Table content = new Table();
        content.top().left().defaults().left().growX();
        content.add(kit.typeLabel(Messages.getString("Lobby.bot.presets"), "small", "text.secondary")).row();
        presetTree = new UiTree(kit, UiTheme.TOKEN);
        presetTree.setName("bot-presets");
        presetTree.onSelect(id -> {
            Preset preset = presetIds.get(id);
            if (preset != null && !busy) {
                form.select(preset);
                showValues();
                refresh();
            }
        });
        fillPresets();
        ScrollPane scroll = kit.scrollList(presetTree, true);
        scroll.setScrollingDisabled(true, false);
        content.add(scroll).height(size("dialog.sm") / 2).padTop(space("xs")).row();

        save = kit.button("button-secondary-compact", null, Messages.getString("BotConfigDialog.save"), null);
        save.setName("bot-save");
        tip(save, Messages.getString("BotConfigDialog.saveTip"));
        UiKit.onChange(save, () -> presetWork(form::savePreset));
        delete = kit.button("button-quiet-compact", null, Messages.getString("Lobby.bot.deletePreset"), null);
        delete.setName("bot-delete");
        tip(delete, Messages.getString("Lobby.bot.deletePresetTip"));
        UiKit.onChange(delete, () -> {
            Preset chosen = form.selected();
            if (chosen != null && chosen.kind() == PresetKind.STORED) {
                presetWork(() -> form.deletePreset(chosen.name()));
            }
        });
        Table buttons = new Table();
        buttons.left();
        buttons.add(save);
        buttons.add(delete).padLeft(space("sm"));
        content.add(buttons).fill(false, false).padTop(space("sm")).row();

        newName = kit.input("", true);
        newName.setName("bot-preset-name");
        UiKit.onChange(newName, this::refresh);
        newNameField = kit.formField(Messages.getString("Lobby.bot.presetName"), newName);
        saveNew = kit.button("button-secondary-compact", null, Messages.getString("Lobby.bot.saveNew"), null);
        saveNew.setName("bot-save-new");
        tip(saveNew, Messages.getString("BotConfigDialog.saveNewTip"));
        UiKit.onChange(saveNew, () -> {
            String name = newName.getText();
            if (form.canSaveNew(name)) {
                presetWork(() -> form.saveNewPreset(name));
                newName.setText("");
            }
        });
        content.add(newNameField).padTop(space("md")).row();
        content.add(saveNew).fill(false, false).padTop(space("sm")).row();
        return content;
    }

    private void fillPresets() {
        presetIds.clear();
        List<UiTree.Node> nodes = new ArrayList<>();
        for (Preset preset : form.presets()) {
            String id = "bot-preset-" + preset.kind().name().toLowerCase(java.util.Locale.ROOT) + "-" + preset.name();
            presetIds.put(id, preset);
            UiTree.Node node = new UiTree.Node(id, preset.label());
            if (preset.kind() == PresetKind.PREVIOUS) {
                node = node.icon("undo");
            } else if (preset.kind() == PresetKind.BOT) {
                node = node.icon("bot");
            }
            nodes.add(node);
        }
        presetTree.nodes(nodes);
        Preset selected = form.selected();
        presetIds.forEach((id, preset) -> {
            if (preset.equals(selected)) {
                presetTree.select(id);
            }
        });
    }

    /** Writes the preset file on the Swing thread, holding the buttons meanwhile, then shows the result. */
    private void presetWork(Runnable work) {
        if (busy) {
            return;
        }
        busy = true;
        refresh();
        host.edt(work, () -> {
            busy = false;
            fillPresets();
            showValues();
            refresh();
        });
    }

    // ---- Strategic targets ----

    private Table targets() {
        Table content = new Table();
        content.top().left().defaults().left().growX();
        content.add(kit.caption(Messages.getString("Lobby.bot.unitTargets"), true)).row();
        UiKit.TokenSearch search = kit.tokenSearch(Messages.getString("BotConfigDialog.pickerSearchHint"), true);
        search.field.setName("bot-target-search");
        tip(search.field, Messages.getString("BotConfigDialog.pickerSearchTip"));
        UiKit.onChange(search.field, () -> {
            form.picker().search(search.field.getText());
            pickerDue = true;
        });
        content.add(search).padTop(space("sm")).row();
        pickerList.top().left();
        ScrollPane scroll = kit.scrollList(pickerList, true);
        scroll.setScrollingDisabled(true, false);
        scroll.setFadeScrollBars(false);
        content.add(scroll).height(size("dialog.sm") * 0.6f).padTop(space("sm")).row();
        pickerCount = kit.typeLabel("", "small", "text.secondary");
        pickerCount.setName("bot-target-count");
        content.add(pickerCount).padTop(space("xs")).row();
        Label hint = kit.typeLabel(Messages.getString("BotConfigDialog.pickerPriorityHint"), "small", "text.muted");
        hint.setWrap(true);
        content.add(hint).padTop(space("xxs")).row();
        fillPicker();

        content.add(kit.caption(colonless("BotConfigDialog.hexTargets"), true)).padTop(space("xl")).row();
        hexList.top().left().defaults().left();
        content.add(hexList).padTop(space("sm")).row();
        fillHexes();

        hexInput = kit.input("", true);
        hexInput.setName("bot-hex-coords");
        hexInput.setTextFieldFilter((text, typed) -> Character.isDigit(typed) || typed == ',' || typed == ' ');
        UiKit.onChange(hexInput, () -> hexField.error(null));
        hexField = kit.formField(Messages.getString("Lobby.bot.hexCoords"), hexInput);
        tip(hexInput, Messages.getString("BotConfigDialog.hexCoordsTip"));
        UiButton addHex = kit.button("button-secondary-compact", "add", Messages.getString("Lobby.bot.add"), null);
        addHex.setName("bot-hex-add");
        UiKit.onChange(addHex, () -> {
            if (form.addHex(hexInput.getText())) {
                hexInput.setText("");
                hexField.error(null);
                fillHexes();
            } else {
                hexField.error(Messages.getString("Lobby.bot.hexUnreadable"));
            }
        });
        Table typed = new Table();
        typed.bottom().left();
        typed.add(hexField).width(size("dialog.sm") / 2).bottom();
        typed.add(addHex).bottom().padLeft(space("sm"));
        typed.add().growX();

        Table chosen = new Table();
        chosen.bottom().left();
        List<HexTarget> buildings = form.buildingHexes();
        if (buildings.isEmpty()) {
            chosen.add(kit.typeLabel(Messages.getString("Lobby.bot.buildingNone"), "small", "text.muted")).left();
        } else {
            UiSelect building = kit.tokenSelect(true, buildings.stream().map(HexTarget::text).toArray(String[]::new));
            building.setName("bot-building");
            UiButton addBuilding = kit.button("button-secondary-compact", "add", Messages.getString("Lobby.bot.add"),
                  null);
            addBuilding.setName("bot-building-add");
            UiKit.onChange(addBuilding, () -> {
                form.addHex(buildings.get(building.getSelectedIndex()).coords());
                fillHexes();
            });
            chosen.add(kit.formField(Messages.getString("Lobby.bot.building"), building)).growX().minWidth(0)
                  .bottom();
            chosen.add(addBuilding).bottom().padLeft(space("sm"));
        }
        if (stacked) {
            content.add(typed).padTop(space("md")).row();
            content.add(chosen).padTop(space("sm")).row();
        } else {
            Table both = new Table();
            both.bottom().left();
            both.add(typed).growX().uniformX().bottom();
            both.add(chosen).growX().uniformX().bottom().padLeft(space("lg"));
            content.add(both).padTop(space("md")).row();
        }
        return content;
    }

    private void fillPicker() {
        pickerList.clearChildren();
        BotTargetPicker picker = form.picker();
        float indent = space("lg");
        for (BotTargetPicker.Row row : picker.rows()) {
            Table line = new Table();
            line.left();
            float pad = row.depth() * indent;
            switch (row.kind()) {
                case OWNER, NO_FORCE -> {
                    line.add(fold(row)).padLeft(pad);
                    line.add(kit.typeLabel(row.text(), row.kind() == BotTargetPicker.Kind.OWNER ? "body.strong"
                          : "body", "text.primary")).left().padLeft(space("xs"));
                }
                case FORCE -> {
                    line.add(fold(row)).padLeft(pad);
                    line.add(tick(row)).left().padLeft(space("xs"));
                }
                case UNIT -> line.add(tick(row)).left().padLeft(pad + size("control.sm") + space("xs"));
                case OTHER -> line.add(kit.typeLabel(row.text(), "body.strong", "text.primary")).left();
                case EMPTY -> line.add(kit.typeLabel(row.text(), "small", "text.muted")).left();
            }
            if (row.tip() != null && row.kind() == BotTargetPicker.Kind.OTHER) {
                tip(line, row.tip());
            }
            line.add().growX();
            if (row.priority() > 0) {
                UiSelect priority = kit.tokenSelect(true, "1", "2", "3", "4", "5");
                priority.setName("bot-prio-" + row.id());
                priority.select(row.priority() - 1);
                priority.setDisabled(!row.priorityEnabled());
                tip(priority, Messages.getString("BotConfigDialog.pickerPriorityTip"));
                UiKit.onChange(priority, () -> {
                    picker.priority(row.id(), priority.getSelectedIndex() + 1);
                    pickerDue = true;
                });
                line.add(priority).right().padLeft(space("sm"));
            }
            pickerList.add(line).growX().padTop(space("xxs")).row();
        }
        pickerCount.setText(picker.count());
    }

    private UiButton fold(BotTargetPicker.Row row) {
        UiButton fold = kit.button("button-icon-compact", row.open() ? "chevron-down" : "chevron-right", null, null);
        fold.setName("bot-fold-" + row.id());
        UiKit.onChange(fold, () -> {
            form.picker().toggle(row.id());
            pickerDue = true;
        });
        return fold;
    }

    private UiChoice tick(BotTargetPicker.Row row) {
        UiChoice tick = kit.check(row.text(), row.checked());
        tick.setName("bot-tick-" + row.id());
        tick.setDisabled(!row.enabled());
        if (row.tip() != null) {
            tip(tick, row.tip());
        }
        UiKit.onChange(tick, () -> {
            form.picker().tick(row.id(), tick.isChecked());
            pickerDue = true;
        });
        return tick;
    }

    private void fillHexes() {
        hexList.clearChildren();
        List<HexTarget> hexes = form.hexTargets();
        if (hexes.isEmpty()) {
            hexList.add(kit.typeLabel(Messages.getString("Lobby.bot.hexNone"), "small", "text.muted")).left().row();
        }
        for (int index = 0; index < hexes.size(); index++) {
            HexTarget hex = hexes.get(index);
            Label line = kit.typeLabel(hex.text(), "body", hex.valid() ? "text.primary" : "status.warning");
            line.setEllipsis(true);
            UiButton remove = kit.button("button-icon-compact", "close", null, null);
            remove.setName("bot-hex-remove-" + index);
            tip(remove, Messages.getString("Lobby.bot.removeHex", hex.text()));
            UiKit.onChange(remove, () -> {
                form.removeHex(hex.coords());
                fillHexes();
            });
            hexList.add(line).growX().minWidth(0).left();
            hexList.add(remove).padLeft(space("sm")).row();
        }
    }

    // ---- State ----

    /** GPU thread, each frame: rebuilds the unit list after a click changed it, outside that click's event. */
    private void drain() {
        if (pickerDue) {
            pickerDue = false;
            fillPicker();
        }
    }

    /** Puts the form's values into the controls, as after loading a preset. */
    private void showValues() {
        sliders.forEach((kind, slider) -> {
            slider.setValue(form.value(kind));
            sliderTitles.get(kind).setText(kind.title(form.value(kind)));
        });
        flags.forEach((flag, check) -> check.setChecked(form.flag(flag)));
        posture.select(List.of(CombatPosture.values()).indexOf(form.posture()));
        retreatEdge.select(Math.max(0, BotSetupForm.edges().indexOf(form.retreatEdge())));
        fleeEdge.select(Math.max(0, BotSetupForm.edges().indexOf(form.fleeEdge())));
        fillHexes();
        pickerDue = true;
    }

    /** Shows what may be pressed and what is wrong. */
    private void refresh() {
        if (nameField != null) {
            nameField.error(form.nameError().map(Messages::getString).orElse(null));
        }
        retreatEdge.setDisabled(!form.flag(Flag.FORCED_WITHDRAWAL));
        fleeEdge.setDisabled(!form.flag(Flag.AUTO_FLEE));
        Preset chosen = form.selected();
        save.setDisabled(busy || !form.changedPreset());
        delete.setDisabled(busy || chosen == null || chosen.kind() != PresetKind.STORED);
        String typed = newName.getText();
        newNameField.error(form.newPresetError(typed).map(Messages::getString).orElse(null));
        saveNew.setDisabled(busy || !form.canSaveNew(typed));
        ok.setDisabled(busy || !form.valid());
    }

    private void confirm() {
        if (busy || !form.valid()) {
            return;
        }
        host.confirm(new LobbyPlayerController.BotSetup(form.name(), form.type(), form.behavior()));
        close.run();
    }

    // ---- For the probe and the tests ----

    BotSetupForm form() {
        return form;
    }

    UiDialog dialog() {
        return dialog;
    }

    boolean busy() {
        return busy;
    }
}
