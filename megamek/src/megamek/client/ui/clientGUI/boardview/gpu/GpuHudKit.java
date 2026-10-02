/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.UnitStatusWords;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBattleStatus.UnitStatus;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiKit.Tone;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.Facing;
import megamek.common.enums.GamePhase;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;

/**
 * The battle HUD's own widgets that two or more HUD components share (rebuild plan C.3): unit sprites and rows with the
 * unit lists' grouping and search, letter squares and heat bars, beside {@link #ui}, the toolkit's generic widgets. The
 * kit owns one atlas of the unit sprite masks its owner references and nothing else.
 */
final class GpuHudKit implements Disposable {
    /** A friendly unit's sprite mask (.row .spr); an enemy's is CORAL. */
    static final Color FRIEND_SPRITE = Color.valueOf("E4EBE7");
    /**
     * The order of the weight-class groups: the group of the heaviest unit first, sensor contacts last. The class codes
     * are no order by weight (a support vehicle's lies above ASSAULT's).
     */
    static final Comparator<UnitStatus> HEAVIEST_FIRST = Comparator.comparing(UnitStatus::sensorContact)
          .thenComparing(Comparator.comparingDouble(UnitStatus::tons).reversed());
    private static final String UNIDENTIFIED = "GpuBoard.hud.common.unidentified";
    private static final String SENSOR_CONTACT = "GpuBoard.hud.common.sensorContact";
    // Heat bar segments (.heatfc .segs i) and heat tick marks (.meter .bar u); the bar's track is UiTheme.TRACK.
    private static final Color SEGMENT = new Color(1, 1, 1, .09f);
    private static final Color TICK = new Color(0, 0, 0, .7f);

    /** Letter squares: a primary target, a secondary one, a new assignment, and the focused pill's letter. */
    enum Letter { PRIMARY, SECONDARY, NEW, FOCUSED }

    /** The toolkit's widgets over the same skin; components build their generic widgets with it. */
    final UiKit ui;
    private final GpuTextures<BoardScene.Pixels> masks = new GpuTextures<>(true);
    /** Each referenced sprite and its white copy, kept so that an unchanged frame hands the atlas the same images. */
    private final Map<BoardScene.Pixels, BoardScene.Pixels> whiteCopies = new HashMap<>();

    /** The skin belongs to the window's GpuBoardSkin; the kit only reads it. */
    GpuHudKit(Skin skin) {
        ui = new UiKit(skin);
    }

    /** A letter square: 19 units in a target pill (.pill i), 24 on a target card (.tcard .L). */
    Container<Label> letter(String letter, Letter kind, boolean card) {
        Color text = switch (kind) {
            case PRIMARY -> Color.valueOf("111111");
            case SECONDARY -> Color.valueOf("1D1413");
            case NEW -> UiTheme.MUTED;
            case FOCUSED -> Color.WHITE;
        };
        String background = switch (kind) {
            case PRIMARY -> "letter-primary";
            case SECONDARY -> "letter";
            case NEW -> "letter-new";
            case FOCUSED -> "letter-on";
        };
        Label label = ui.label(letter, "hud-name", card ? 13 : 11, text);
        label.setAlignment(Align.center);
        Container<Label> tile = new Container<>(label).size(card ? 24 : 19);
        tile.setBackground(ui.skin.getDrawable(background));
        return tile;
    }

    /** A unit's sprite in a box of the given size: a mask filled with a color (.spr .mask), or a contact's "?". */
    UnitSprite sprite(float width, float height) {
        return new UnitSprite(width, height);
    }

    /** A unit row: 40 x 34 sprites in the forces list (.row), 34 x 30 in the fire preview (.prow). */
    UnitRow unitRow(float spriteWidth, float spriteHeight) {
        return new UnitRow(spriteWidth, spriteHeight);
    }

    /** The heat bar: continuous (.meter .bar) or one segment per heat point (.heatfc .segs). */
    HeatBar heatBar(boolean segments) {
        return new HeatBar(segments);
    }

    /** A target number as shown: an automatic success reads 2+, which every 2d6 roll makes. */
    static int shown(int value) {
        return value == TargetRoll.AUTOMATIC_SUCCESS ? 2 : value;
    }

    /** Odds of 0 to 100 as the prototype shows them, "{p}%". */
    static String percent(double odds) {
        return Messages.getString("GpuBoard.hud.common.percent", Math.round(odds));
    }

    /** A modifier with its sign, a minus sign for a negative one. */
    static String signed(int value) {
        return value < 0 ? "\u2212" + -value : "+" + value;
    }

    /** A hex facing's compass name (N, NE, ...), or nothing for none. */
    static String facing(int facing) {
        return facing < 0 ? "" : Facing.valueOfInt(Math.floorMod(facing, 6)).name();
    }

    /**
     * The formation group that the unit lists name a unit by (plan C4): its formation, "Unassigned" without one, and
     * for an ally "Allies · {player}" with the player of {@code players} (its formation while that list lacks the
     * owner). A sensor contact, and under double blind every enemy, is "Unidentified" until the client is known to
     * filter enemy force names (plan F.2 item 7); the status's turn order flag is that double-blind option.
     */
    static String formation(UnitStatus unit, GpuBattleStatus.Snapshot status, GpuPlayers.Snapshot players) {
        if (unit.sensorContact() || unit.side() == GpuBattleStatus.Side.ENEMY && status.turnOrderHidden()) {
            return Messages.getString(UNIDENTIFIED);
        }
        String name = unit.formation().isEmpty() ? Messages.getString("GpuBoard.hud.forces.unassigned")
              : unit.formation();
        if (unit.side() != GpuBattleStatus.Side.ALLY) {
            return name;
        }
        String player = players.players().stream().filter(row -> row.id() == unit.ownerId())
              .map(GpuPlayers.PlayerRow::name).findFirst().orElse(name);
        return Messages.getString("GpuBoard.hud.forces.allies", player);
    }

    /**
     * The unit lists' search (plan C3): the name, chassis, model, {@code formation} as the list names it (so double
     * blind keeps an enemy's hidden) and the weight class; a sensor contact matches its "Sensor contact" and
     * "Unidentified" labels. {@code query} is lower case; an empty one matches every unit.
     */
    static boolean matches(UnitStatus unit, String formation, String query) {
        String haystack = unit.sensorContact()
              ? Messages.getString(SENSOR_CONTACT) + " " + Messages.getString(UNIDENTIFIED)
              : String.join(" ", unit.name(), unit.chassis(), unit.model(), formation, unit.weightClass());
        return haystack.toLowerCase(Locale.ROOT).contains(query);
    }

    /**
     * The units by the group {@code key} names, each group's units in the units' order. The groups follow their units
     * as {@code order} sorts them; the sort is stable, so an order that ties keeps the order the units come in.
     */
    static Map<String, List<UnitStatus>> groups(List<UnitStatus> units, Function<UnitStatus, String> key,
          Comparator<UnitStatus> order) {
        Map<String, List<UnitStatus>> groups = new LinkedHashMap<>();
        units.stream().sorted(order).forEach(unit -> groups.putIfAbsent(key.apply(unit), new ArrayList<>()));
        units.forEach(unit -> groups.get(key.apply(unit)).add(unit));
        return groups;
    }

    /**
     * Keeps one mipmapped atlas of the sprite masks that the owner's current snapshots reference, as GpuBattleView
     * keeps its unit textures. The owner calls it once per frame, before drawing. A mask lives while it is
     * referenced, whether or not a widget draws it in that frame.
     */
    void update(Set<BoardScene.Pixels> referenced) {
        whiteCopies.keySet().retainAll(referenced);
        referenced.forEach(pixels -> whiteCopies.computeIfAbsent(pixels, GpuHudKit::whiteCopy));
        masks.update(whiteCopies);
    }

    /** A sprite's coverage in white, so that the batch color fills it (the SpriteBatch shader multiplies RGB). */
    private static BoardScene.Pixels whiteCopy(BoardScene.Pixels pixels) {
        int[] argb = new int[pixels.width() * pixels.height()];
        for (int index = 0; index < argb.length; index++) {
            // rgba() holds the alpha in its low byte.
            argb[index] = pixels.rgba(index) << 24 | 0xFFFFFF;
        }
        BufferedImage image = new BufferedImage(pixels.width(), pixels.height(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, pixels.width(), pixels.height(), argb, 0, pixels.width());
        return new BoardScene.Pixels(image);
    }

    private TextureRegion mask(BoardScene.Pixels pixels) {
        if (!whiteCopies.containsKey(pixels)) {
            throw new IllegalStateException("A sprite is drawn whose image its owner did not pass to update()");
        }
        return masks.region(pixels);
    }

    @Override
    public void dispose() {
        masks.dispose();
        whiteCopies.clear();
    }

    /**
     * A unit's sprite fitted into its box as a mask filled with a color. A null image is a sensor contact, shown as
     * the prototype's "?" (.q: 18 units) in the color. The overview card and the forces grid tile shrink that glyph in
     * the mock; their components replace the label with {@link #setActor}.
     */
    final class UnitSprite extends Container<Label> {
        private final float width;
        private final float height;
        private BoardScene.Pixels pixels;
        private Color tint = Color.WHITE;

        private UnitSprite(float width, float height) {
            super(ui.label("?", "hud-heading", 18, Color.WHITE));
            this.width = width;
            this.height = height;
            getActor().setVisible(false);
        }

        // The box keeps its size; the "?" keeps its own and is centered in it.
        @Override
        public float getMinWidth() {
            return width;
        }

        @Override
        public float getMinHeight() {
            return height;
        }

        @Override
        public float getPrefWidth() {
            return width;
        }

        @Override
        public float getPrefHeight() {
            return height;
        }

        UnitSprite set(BoardScene.Pixels image, Color color) {
            pixels = image;
            tint = color;
            getActor().setColor(color);
            getActor().setVisible(image == null);
            return this;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            if (pixels != null) {
                float fit = Math.min(getWidth() / pixels.width(), getHeight() / pixels.height());
                float drawnWidth = pixels.width() * fit;
                float drawnHeight = pixels.height() * fit;
                Color color = getColor();
                float previous = batch.getPackedColor();
                batch.setColor(tint.r * color.r, tint.g * color.g, tint.b * color.b, tint.a * color.a * parentAlpha);
                batch.draw(mask(pixels), getX() + (getWidth() - drawnWidth) / 2,
                      getY() + (getHeight() - drawnHeight) / 2, drawnWidth, drawnHeight);
                batch.setPackedColor(previous);
            }
            super.draw(batch, parentAlpha);
        }
    }

    /**
     * A unit row (.row, .prow): the sprite, the model line, the upper-case name, a status line, a fourth line that
     * shows only with a text, and a slot at the right. Selected is the pressed state; an inspected unit gets coral
     * edges (.row.insp), mint ones for a friendly unit, which never takes the enemy's colour (the user's decision of
     * 2026-10-02). Done and destroyed rows fade through the row's color alpha. A null image shows a sensor
     * contact's "?". {@link #show} fills a row of the forces or contacts list from the unit's status; the static
     * helpers give both lists and the forces grid the same lines, colors, tooltips and unit menu (C5, C6, C12, C13).
     */
    final class UnitRow extends UiButton {
        /** The board-label status words of a prone and of a shut-down unit ({@code UnitStatusWords.statusWords} keys). */
        private static final String PRONE = "PRONE";
        private static final String SHUTDOWN = "SHUTDOWN";
        final UnitSprite sprite;
        final Label model = ui.label("", "hud-small", 10, UiTheme.MUTED);
        final Label name = new Label("", ui.skin, "hud-name");
        final Label line = ui.label("", "hud-small", 11, Color.WHITE);
        /** The fourth line (.prow .em.inc), hidden until {@link #extra} gives it a text; its component colors it. */
        final Label extra = ui.label("", "hud-small", 10.5f, Color.WHITE);
        final Container<Actor> right = new Container<>();
        private final Drawable foe = ui.skin.getDrawable("row-foe");
        private final Drawable friend = ui.skin.getDrawable("row-friend");
        private final Cell<Label> nameCell;
        private final Cell<Label> extraCell;
        private TextTooltip tooltip;
        private boolean inspected;
        /** The shown unit is the local player's or an ally's; its inspected edges are mint. */
        private boolean friendly;

        /** A status line and its color (r1 section 3.3, plan C6). */
        record Line(String text, Tone tone) { }

        private UnitRow(float spriteWidth, float spriteHeight) {
            super(ui, "hud-row");
            sprite = new UnitSprite(spriteWidth, spriteHeight);
            // Lines too long for the row end in an ellipsis instead of running into the right slot.
            model.setEllipsis(true);
            name.setEllipsis(true);
            line.setEllipsis(true);
            extra.setEllipsis(true);
            extra.setVisible(false);
            Table text = new Table();
            text.defaults().left().growX().minWidth(0);
            // CSS line-height 1.25 of each line's font size.
            text.add(model).height(12.5f).row();
            nameCell = text.add(name).height(17);
            text.row();
            text.add(line).height(13.75f).row();
            extraCell = text.add(extra).height(0);
            add(sprite).padRight(11);
            add(text).growX().minWidth(0);
            add(right).padLeft(11);
        }

        UnitRow set(BoardScene.Pixels pixels, Color spriteColor, String model, String name, String status, Tone tone) {
            sprite.set(pixels, spriteColor);
            this.model.setText(model);
            this.name.setText(UiTheme.upper(name));
            line.setText(status);
            line.setColor(tone.color);
            return this;
        }

        UnitRow inspected(boolean value) {
            inspected = value;
            return this;
        }

        /** The name at another size on another line height, as the fire preview's rows have it (.prow .nm b). */
        UnitRow nameSize(float size, float lineHeight) {
            UiKit.size(name, "hud-name", size);
            nameCell.height(lineHeight);
            return this;
        }

        /** The fourth line (.prow .em.inc: 10.5 units on a 14-unit line) with {@code text}, or none for null. */
        UnitRow extra(String text) {
            extra.setText(text == null ? "" : text);
            extra.setVisible(text != null);
            extraCell.height(text == null ? 0 : 14);
            extraCell.getTable().invalidateHierarchy();
            return this;
        }

        /**
         * Shows a unit of the forces or contacts list (C5, C12): a sensor contact's "?", or the unit's sprite in its
         * side's color, with the model, the name and {@code line}; the tooltip; the fade of a unit that has acted in
         * the phase (.72) or is destroyed (.4); coral edges while it is {@code inspecting}; and at the right
         * destroyed, acted, heat effects, or an identified enemy's distance from {@code from}, the acting or own focus
         * unit (null: none).
         */
        UnitRow show(UnitStatus unit, Line line, GamePhase phase, boolean inspecting, UnitStatus from) {
            if (unit.sensorContact()) {
                set(null, UiTheme.BLIP, Messages.getString("GpuBoard.hud.common.unidentified"),
                      Messages.getString("GpuBoard.hud.common.sensorContact"), line.text(), line.tone());
            } else {
                set(unit.icon(), spriteColor(unit), unit.model(), unit.chassis(), line.text(), line.tone());
            }
            boolean acted = acted(unit, phase);
            friendly = unit.side() != GpuBattleStatus.Side.ENEMY;
            inspected(inspecting);
            getColor().a = unit.destroyed() ? .4f : acted ? .72f : 1;
            right.setActor(slot(unit, acted, from));
            if (tooltip == null) {
                tooltip = ui.tip(this);
            }
            tooltip.getActor().setText(tip(unit, line));
            return this;
        }

        /** The right slot (.st): destroyed, acted, heat effects, or an identified enemy's distance from a unit. */
        private Actor slot(UnitStatus unit, boolean acted, UnitStatus from) {
            if (unit.destroyed()) {
                return ui.icon("close", 15, UiTheme.MUTED);
            } else if (acted) {
                return ui.icon("check", 15, UiTheme.MUTED);
            } else if (!unit.heatEffects().isEmpty()) {
                // The status carries the heat table's text only for a heat level with effects (C5).
                return ui.icon("flame", 15, UiTheme.AMBER);
            } else if (unit.side() == GpuBattleStatus.Side.ENEMY && !unit.sensorContact() && from != null
                  && from.position() != null && unit.position() != null && from.boardId() == unit.boardId()) {
                return ui.label(Messages.getString("GpuBoard.hud.common.hexDistance",
                      from.position().distance(unit.position())), "hud-medium", 11, UiTheme.MUTED);
            }
            return null;
        }

        @Override
        protected Drawable getBackgroundDrawable() {
            return !inspected ? super.getBackgroundDrawable() : friendly ? friend : foe;
        }

        /**
         * The status line of an enemy, and of every sensor contact and destroyed unit (r1 section 3.3, plan C6): the
         * sensor return, destroyed, in the movement phase moved (coral when prone or shut down), acting now or
         * pending, and otherwise prone, shut down or a visual contact. The forces list gives the other lines.
         */
        static Line contactLine(UnitStatus unit, GpuBattleStatus.Snapshot status) {
            if (unit.sensorContact()) {
                return new Line(Messages.getString("GpuBoard.hud.status.sensorReturn"), Tone.NORMAL);
            } else if (unit.destroyed()) {
                return new Line(Messages.getString("GpuBoard.hud.state.destroyed"), Tone.BAD);
            }
            GamePhase phase = status.phase();
            boolean prone = prone(unit);
            boolean shutDown = shutDown(unit);
            // The opponent's current turn, when it names this unit.
            int slot = status.turnIndex();
            boolean acting = !status.myTurn() && slot >= 0 && slot < status.turns().size()
                  && status.turns().get(slot).entityId() == unit.id();
            if (phase.isMovement() && unit.done()) {
                return new Line(Messages.getString("GpuBoard.hud.status.moved", moved(unit, phase)),
                      prone || shutDown ? Tone.BAD : Tone.NORMAL);
            } else if (phase.isMovement() && (acting || unit.pending())) {
                return acting ? new Line(Messages.getString("GpuBoard.hud.status.actingNow"), Tone.OK)
                      : new Line(Messages.getString("GpuBoard.hud.status.pending"), Tone.WARN);
            }
            return prone ? new Line(Messages.getString("GpuBoard.hud.state.prone"), Tone.BAD)
                  : shutDown ? new Line(Messages.getString("GpuBoard.hud.state.shutDown"), Tone.BAD)
                  : new Line(Messages.getString("GpuBoard.hud.status.visual"), Tone.NORMAL);
        }

        /**
         * The movement summary (status.moveSummary: type, MP, facing), or "held position" once the unit's movement is
         * over without a move; empty before it moves.
         */
        static String moved(UnitStatus unit, GamePhase phase) {
            if (!unit.moved().isEmpty()) {
                return Messages.getString("GpuBoard.hud.status.moveSummary", unit.moved(), unit.mpUsed(),
                      GpuHudKit.facing(unit.facing()));
            }
            // The enum lists a round's phases in order, then the setup phases.
            boolean over = phase.isDuringOrAfter(GamePhase.MOVEMENT_REPORT)
                  && phase.isBefore(GamePhase.DEPLOY_MINEFIELDS);
            return over || (phase.isMovement() && unit.done())
                  ? Messages.getString("GpuBoard.hud.status.heldPosition") : "";
        }

        /** The unit's board label says it is shut down ({@code UnitStatusWords.statusWords}). */
        static boolean shutDown(UnitStatus unit) {
            return word(unit, SHUTDOWN);
        }

        /** The unit's board label says it is prone ({@code UnitStatusWords.statusWords}). */
        static boolean prone(UnitStatus unit) {
            return word(unit, PRONE);
        }

        /** The unit has acted in this phase, one in which units act in turn (movement, attacks, physical). */
        static boolean acted(UnitStatus unit, GamePhase phase) {
            return unit.done() && (phase.isMovement() || phase.isFiring() || phase.isTargeting() || phase.isOffboard()
                  || phase.isPhysical());
        }

        /** The acting unit during the local turn, otherwise the own focus unit (C.5): the prototype's acting(). */
        static int acting(GpuBattleStatus.Snapshot status, int focus) {
            return status.myTurn() && status.actorId() != Entity.NONE ? status.actorId() : focus;
        }

        /** A unit's sprite color (.spr): coral for an enemy, the friendly mask color otherwise. */
        static Color spriteColor(UnitStatus unit) {
            return unit.side() == GpuBattleStatus.Side.ENEMY ? UiTheme.CORAL : FRIEND_SPRITE;
        }

        /** The tooltip of a unit's row or tile (A.19): its chassis and model, or a sensor contact, and its line. */
        static String tip(UnitStatus unit, Line line) {
            return unit.sensorContact() ? Messages.getString("GpuBoard.hud.common.contactTip", line.text())
                  : Messages.getString("GpuBoard.hud.common.unitTip", unit.chassis(), unit.model(), line.text());
        }

        /**
         * Opens the menu of unit {@code id} at the pointer (C13) when a right click is released over the actor that
         * listens, unless that actor is a disabled button; it never changes orders. {@code units} gives the unit's
         * current status, whose hex the menu names.
         */
        static InputListener menuOpener(GpuContextMenu menu, int id, IntFunction<UnitStatus> units) {
            return new InputListener() {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                    return button == Input.Buttons.RIGHT
                          && !(event.getListenerActor() instanceof Button target && target.isDisabled());
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                    if (button == Input.Buttons.RIGHT && event.getListenerActor().hit(x, y, true) != null) {
                        UnitStatus unit = units.apply(id);
                        menu.open(unit == null ? null : unit.position(), id, event.getStageX(), event.getStageY());
                    }
                }
            };
        }

        private static boolean word(UnitStatus unit, String key) {
            return unit.statusWords().stream().anyMatch(word -> word.key().equals(key));
        }
    }

    /**
     * The heat bar. Continuous (the unit panel's .hbar: 7 units, 11 where its cell makes it taller): the current heat
     * in amber, the forecast hatched after it, and 1-unit tick marks at 30 % white that reach 2 units past the bar, the
     * next one above the current heat in full white and 3 units past it. Segmented (7 units): one segment per heat
     * point, amber up to the current
     * heat, hatched up to the forecast, and a dark 2-unit edge left of each tick. The component supplies the scale and
     * the ticks from the unit's heat table; a meter shows it under its caption ({@code ui.meter(caption, heatBar)}).
     */
    final class HeatBar extends Widget {
        private final boolean segments;
        private final Drawable hatch = ui.skin.getDrawable("hatch");
        private int heat;
        private int forecast;
        private int scale = 1;
        private List<Integer> ticks = List.of();

        private HeatBar(boolean segments) {
            this.segments = segments;
        }

        HeatBar set(int current, int predicted, int maximum, List<Integer> marks) {
            heat = current;
            forecast = predicted;
            scale = Math.max(1, maximum);
            ticks = List.copyOf(marks);
            return this;
        }

        @Override
        public float getPrefHeight() {
            return 7;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            float y = getY();
            float height = getHeight();
            if (segments) {
                float width = (getWidth() - 2 * (scale - 1)) / scale;
                for (int point = 0; point < scale; point++) {
                    float x = getX() + point * (width + 2);
                    if (ticks.contains(point)) {
                        ui.fill(batch, TICK, alpha, x - 2, y, 2, height);
                    }
                    if (point < heat) {
                        ui.fill(batch, UiTheme.AMBER, alpha, x, y, width, height);
                    } else if (point < forecast) {
                        hatched(batch, alpha, x, width);
                    } else {
                        ui.fill(batch, SEGMENT, alpha, x, y, width, height);
                    }
                }
            } else {
                float current = getWidth() * Math.min(heat, scale) / scale;
                float predicted = getWidth() * Math.min(forecast, scale) / scale;
                ui.fill(batch, UiTheme.TRACK, alpha, getX(), y, getWidth(), height);
                ui.fill(batch, UiTheme.AMBER, alpha, getX(), y, current, height);
                hatched(batch, alpha, getX() + current, predicted - current);
                int next = ticks.stream().filter(tick -> tick > heat).findFirst().orElse(-1);
                for (int tick : ticks) {
                    float reach = tick == next ? 3 : 2;
                    ui.fill(batch, Color.WHITE, alpha * (tick == next ? 1 : .3f),
                          getX() + Math.round(getWidth() * tick / scale), y - reach, 1, height + 2 * reach);
                }
            }
            batch.setPackedColor(previous);
        }

        private void hatched(Batch batch, float alpha, float x, float width) {
            if (width > 0) {
                batch.setColor(1, 1, 1, alpha);
                hatch.draw(batch, x, getY(), width, getHeight());
            }
        }
    }
}
