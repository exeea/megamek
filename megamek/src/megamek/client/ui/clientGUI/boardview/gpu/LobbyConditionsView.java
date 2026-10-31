/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import jakarta.annotation.Nullable;
import megamek.client.lobby.PlanetaryConditionsForm;
import megamek.client.lobby.PlanetaryConditionsForm.Field;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiChoice;
import megamek.client.ui.gdx.UiDialog;
import megamek.client.ui.gdx.UiFormField;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiSelect;
import megamek.client.ui.gdx.UiTextField;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Weather;
import megamek.common.planetaryConditions.Wind;
import megamek.common.planetaryConditions.WindDirection;

/**
 * The lobby's planetary conditions in a {@link UiDialog}, the three sections of the Swing
 * {@code PlanetaryConditionsDialog} in its order: general (temperature, gravity, light, atmosphere and its taint,
 * EMI, terrain affected by the weather), weather (weather, wind and its direction, fog, blowing sand) and the dynamic
 * wind (shifting direction and strength, and the strength's range). The values, the adaptations a weather or an
 * atmosphere makes and the validation are a {@link PlanetaryConditionsForm}'s; a field with a problem shows it under
 * itself and OK stays disabled meanwhile.
 * <p>
 * GPU thread. The confirmed conditions are sent on the Swing thread through {@link Host#send}.
 */
final class LobbyConditionsView {
    /** What the view asks of the window. */
    interface Host {
        /** Swing thread: sends the confirmed conditions to the server. */
        void send(PlanetaryConditions conditions);

        /** Runs {@code work} on the Swing thread, then {@code then} back on the GPU thread. */
        void edt(Runnable work, Runnable then);
    }

    private final UiKit kit;
    private final UiDialog dialog;
    private final PlanetaryConditionsForm form;
    private final Host host;
    private final Runnable close;
    private final Map<Field, UiFormField> fields = new EnumMap<>(Field.class);
    /** Shows the form's value in each field's control, without a change event. */
    private final Map<Field, Runnable> shows = new EnumMap<>(Field.class);
    private final UiButton ok;
    private @Nullable Table grid;
    private boolean syncing;
    private boolean busy;

    LobbyConditionsView(UiKit kit, UiDialog dialog, PlanetaryConditionsForm form, Host host, Runnable close) {
        this.kit = kit;
        this.dialog = dialog;
        this.form = form;
        this.host = host;
        this.close = close;
        dialog.body().defaults().left().growX();

        section("PlanetaryConditionsDialog.header.general", true,
              number(Field.TEMPERATURE, form::temperature, form::temperature, true),
              number(Field.GRAVITY, form::gravity, form::gravity, false),
              select(Field.LIGHT, Light.values(), form::light, form::light),
              select(Field.ATMOSPHERE, Atmosphere.values(), form::atmosphere, form::atmosphere),
              select(Field.TAINT, AtmosphericTaint.values(), form::taint, form::taint),
              null,
              check(Field.EMI, form::emi, form::emi),
              check(Field.TERRAIN_AFFECTED, form::terrainAffected, form::terrainAffected));
        section("PlanetaryConditionsDialog.header.weather", false,
              select(Field.WEATHER, Weather.values(), form::weather, form::weather),
              select(Field.WIND, Wind.values(), form::wind, form::wind),
              select(Field.WIND_DIRECTION, WindDirection.values(), form::windDirection, form::windDirection),
              select(Field.FOG, Fog.values(), form::fog, form::fog),
              check(Field.BLOWING_SAND, form::blowingSand, form::blowingSand));
        section("PlanetaryConditionsDialog.header.dynamic", false,
              check(Field.SHIFT_DIRECTION, form::shiftDirection, form::shiftDirection),
              check(Field.SHIFT_STRENGTH, form::shiftStrength, form::shiftStrength),
              select(Field.WIND_FROM, Wind.values(), form::windFrom, form::windFrom),
              select(Field.WIND_TO, Wind.values(), form::windTo, form::windTo));

        ok = dialog.primary(Messages.getString("Ok.text"), this::confirm);
        ok.setName("conditions-ok");
        dialog.cancelButton(Messages.getString("Cancel.text")).setName("conditions-cancel");
        refresh();
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    private static String name(Field field) {
        return "conditions-" + field.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * A captioned section of fields two to a row; a null leaves its cell empty. The sections share one grid, and the
     * gutter is split between the columns, so the columns are equal and line up from section to section.
     */
    private void section(String key, boolean first, @Nullable UiFormField... cells) {
        if (grid == null) {
            grid = new Table();
            grid.top().left();
            dialog.body().add(grid).growX().row();
        }
        grid.add(kit.caption(Messages.getString(key), true)).colspan(2).left().padTop(first ? 0 : space("xl"))
              .row();
        float half = space("md") / 2;
        for (int index = 0; index < cells.length; index++) {
            boolean left = index % 2 == 0;
            Cell<?> cell = cells[index] == null ? grid.add() : grid.add(cells[index]);
            cell.growX().uniformX().top().padTop(space("sm")).padLeft(left ? 0 : half).padRight(left ? half : 0);
            if (!left) {
                grid.row();
            }
        }
        if (cells.length % 2 != 0) {
            grid.add().growX().uniformX().padLeft(half).row();
        }
    }

    private UiFormField field(Field field, Actor input) {
        String label = PlanetaryConditionsForm.label(field);
        return field(field, input, label.endsWith(":") ? label.substring(0, label.length() - 1) : label);
    }

    /** A field under {@code label}; a check box carries its own label, so it goes under a blank one, aligned. */
    private UiFormField field(Field field, Actor input, String label) {
        UiFormField formField = kit.formField(label, input);
        fields.put(field, formField);
        return formField;
    }

    private UiFormField number(Field field, Supplier<String> value, Consumer<String> change, boolean whole) {
        UiTextField input = kit.input(value.get(), true);
        input.setName(name(field));
        input.setTextFieldFilter((text, typed) -> Character.isDigit(typed) || typed == '-'
              || (!whole && typed == '.'));
        UiKit.onChange(input, () -> {
            if (!syncing) {
                change.accept(input.getText());
                refresh();
            }
        });
        shows.put(field, () -> {
            if (!input.getText().equals(value.get())) {
                input.setText(value.get());
            }
        });
        return field(field, input);
    }

    private <T> UiFormField select(Field field, T[] values, Supplier<T> value, Consumer<T> change) {
        String[] names = new String[values.length];
        for (int index = 0; index < values.length; index++) {
            names[index] = String.valueOf(values[index]);
        }
        UiSelect select = kit.tokenSelect(true, names);
        select.setName(name(field));
        UiKit.onChange(select, () -> {
            if (!syncing) {
                change.accept(values[select.getSelectedIndex()]);
                refresh();
            }
        });
        shows.put(field, () -> {
            T current = value.get();
            for (int index = 0; index < values.length; index++) {
                if (values[index] == current && select.getSelectedIndex() != index) {
                    select.select(index);
                }
            }
        });
        return field(field, select);
    }

    private UiFormField check(Field field, Supplier<Boolean> value, Consumer<Boolean> change) {
        UiChoice check = kit.check(PlanetaryConditionsForm.label(field), value.get());
        check.setName(name(field));
        UiKit.onChange(check, () -> {
            if (!syncing) {
                change.accept(check.isChecked());
                refresh();
            }
        });
        shows.put(field, () -> {
            if (check.isChecked() != value.get()) {
                check.setChecked(value.get());
            }
        });
        UiFormField formField = field(field, check, "");
        formField.getCell(check).fill(false, false).left();
        return formField;
    }

    /** Shows the form: each field's value, whether it applies, its problems, and whether OK may be pressed. */
    private void refresh() {
        syncing = true;
        try {
            shows.values().forEach(Runnable::run);
            fields.forEach((field, formField) -> {
                boolean enabled = form.enabled(field);
                switch (formField.input()) {
                    case UiTextField input -> input.setDisabled(!enabled);
                    case UiSelect select -> select.setDisabled(!enabled);
                    case UiChoice check -> check.setDisabled(!enabled);
                    default -> {
                    }
                }
                String problems = form.errorText(field);
                formField.error(problems.isEmpty() ? null : problems);
            });
        } finally {
            syncing = false;
        }
        ok.setDisabled(busy || !form.valid());
    }

    private void confirm() {
        if (busy || !form.valid()) {
            return;
        }
        busy = true;
        refresh();
        PlanetaryConditions conditions = form.result();
        host.edt(() -> host.send(conditions), close);
    }

    // ---- For the probe and the tests ----

    PlanetaryConditionsForm form() {
        return form;
    }

    UiDialog dialog() {
        return dialog;
    }

    @Nullable UiFormField field(Field field) {
        return fields.get(field);
    }
}
